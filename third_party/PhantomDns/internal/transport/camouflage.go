package transport

import (
	"context"
	cryptorand "crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	mathrand "math/rand"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"

	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

// ==============================================================================
// 1. UTLS FINGERPRINT DICTIONARY
// ==============================================================================
var utlsClientHelloIDMap = []struct {
	Label string
	ID    *utls.ClientHelloID
}{
	{"random", &utls.HelloRandomizedALPN},
	{"Firefox", &utls.HelloFirefox_Auto},
	{"Firefox_120", &utls.HelloFirefox_120},
	{"Chrome", &utls.HelloChrome_Auto},
	{"Chrome_120", &utls.HelloChrome_120},
	{"iOS", &utls.HelloIOS_Auto},
	{"iOS_14", &utls.HelloIOS_14},
}

func UTLSLookup(label string) *utls.ClientHelloID {
	for _, entry := range utlsClientHelloIDMap {
		if strings.EqualFold(label, entry.Label) {
			return entry.ID
		}
	}
	return nil
}

// ==============================================================================
// 2. WEIGHTED RANDOM SELECTOR (For rotating fingerprints)
// ==============================================================================
type cryptoSource struct{}

func (s cryptoSource) Seed(_ int64) {}
func (s cryptoSource) Int63() int64 {
	var n int64
	binary.Read(cryptorand.Reader, binary.BigEndian, &n)
	return n & ((1 << 63) - 1)
}

func sampleWeighted(weights []uint32) int {
	var sum int64 = 0
	for _, w := range weights {
		sum += int64(w)
	}
	if sum == 0 {
		return 0
	}
	r := uint64(mathrand.New(&cryptoSource{}).Int63n(sum))
	for i, w := range weights {
		if r < uint64(w) {
			return i
		}
		r -= uint64(w)
	}
	return 0
}

func SampleUTLSDistribution(spec string) (*utls.ClientHelloID, error) {
	var weights []uint32
	var labels []string

	parts := strings.Split(spec, ",")
	for _, part := range parts {
		if part == "" {
			continue
		}
		subParts := strings.SplitN(part, "*", 2)
		if len(subParts) == 2 {
			w, _ := strconv.ParseUint(subParts[0], 10, 32)
			weights = append(weights, uint32(w))
			labels = append(labels, subParts[1])
		} else {
			weights = append(weights, 1)
			labels = append(labels, subParts[0])
		}
	}

	if len(labels) == 0 {
		return nil, nil
	}

	selectedLabel := labels[sampleWeighted(weights)]
	if selectedLabel == "none" {
		return nil, nil
	}
	
	id := UTLSLookup(selectedLabel)
	if id == nil {
		return nil, fmt.Errorf("unknown TLS fingerprint %q", selectedLabel)
	}
	return id, nil
}

// ==============================================================================
// 3. UTLS DIALER & ROUNDTRIPPER (The core HTTP Camouflage)
// ==============================================================================
func UTLSDialContext(ctx context.Context, network, addr string, config *utls.Config, id *utls.ClientHelloID) (*utls.UConn, error) {
	if config == nil {
		config = &utls.Config{}
	}
	if config.ServerName == "" {
		config = config.Clone()
		host, _, _ := net.SplitHostPort(addr)
		config.ServerName = host
	}
	dialer := &net.Dialer{}
	conn, err := dialer.DialContext(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	uconn := utls.UClient(conn, config, *id)
	if err := uconn.Handshake(); err != nil {
		uconn.Close()
		return nil, err
	}
	return uconn, nil
}

type utlsRoundTripper struct {
	clientHelloID *utls.ClientHelloID
	config        *utls.Config
	innerLock     sync.Mutex
	inner         http.RoundTripper
}

func NewUTLSRoundTripper(config *utls.Config, id *utls.ClientHelloID) http.RoundTripper {
	return &utlsRoundTripper{
		clientHelloID: id,
		config:        config,
	}
}

func (rt *utlsRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.URL.Scheme != "https" {
		return http.DefaultTransport.RoundTrip(req)
	}

	rt.innerLock.Lock()
	if rt.inner == nil {
		rt.inner, _ = makeRoundTripper(req, rt.config, rt.clientHelloID)
	}
	rt.innerLock.Unlock()

	if rt.inner == nil {
		return nil, fmt.Errorf("failed to initialize uTLS roundtripper")
	}
	return rt.inner.RoundTrip(req)
}

func makeRoundTripper(req *http.Request, config *utls.Config, id *utls.ClientHelloID) (http.RoundTripper, error) {
	host := req.URL.Hostname()
	port := req.URL.Port()
	if port == "" {
		port = "443"
	}
	addr := net.JoinHostPort(host, port)

	bootstrapConn, err := UTLSDialContext(req.Context(), "tcp", addr, config, id)
	if err != nil {
		return nil, err
	}

	protocol := bootstrapConn.ConnectionState().NegotiatedProtocol
	var lock sync.Mutex

	dialTLSContext := func(ctx context.Context, network, addr string) (net.Conn, error) {
		lock.Lock()
		defer lock.Unlock()

		if bootstrapConn != nil {
			uconn := bootstrapConn
			bootstrapConn = nil
			return uconn, nil
		}

		uconn, err := UTLSDialContext(ctx, "tcp", addr, config, id)
		if err != nil {
			return nil, err
		}
		if uconn.ConnectionState().NegotiatedProtocol != protocol {
			return nil, fmt.Errorf("unexpected switch from ALPN %q to %q", protocol, uconn.ConnectionState().NegotiatedProtocol)
		}
		return uconn, nil
	}

	switch protocol {
	case http2.NextProtoTLS:
		return &http2.Transport{
			DialTLS: func(network, addr string, _ *tls.Config) (net.Conn, error) {
				return dialTLSContext(context.Background(), network, addr)
			},
		}, nil
	default:
		tr := http.DefaultTransport.(*http.Transport).Clone()
		tr.DialTLSContext = dialTLSContext
		return tr, nil
	}
}