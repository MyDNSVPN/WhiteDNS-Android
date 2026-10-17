package main

import (
	"context"
	"crypto/x509"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/getlantern/soar"
)

func TestResolverPlanPreservesNetworkDNSAndCountryPriority(t *testing.T) {
	cfg := defaults()
	cfg.SystemResolvers = []string{"192.168.1.1", "192.168.1.1:53"}
	var configured []string
	for i := 1; i <= 200; i++ {
		configured = append(configured, fmt.Sprintf("192.0.2.%d:53", i))
	}
	initial, candidates := cfg.resolverPlan(configured)
	if len(initial) != 8 || initial[0] != "192.168.1.1:53" {
		t.Fatalf("bad initial set: %v", initial)
	}
	if candidates[0] != "185.212.51.144:53" {
		t.Fatal("Iran candidate was starved by a large untested list")
	}
	global, extra := -1, -1
	seen := map[string]bool{}
	for index, resolver := range append(append([]string{}, initial...), candidates...) {
		if seen[resolver] {
			t.Fatal("duplicate path", resolver)
		}
		seen[resolver] = true
		if resolver == "1.1.1.1:53" {
			global = index
		}
		if resolver == configured[199] {
			extra = index
		}
	}
	if global < 0 || extra <= global {
		t.Fatal("global fallback was starved")
	}
	cfg.Soar.Country = "TH"
	_, candidates = cfg.resolverPlan(configured)
	if candidates[0] != "1.1.1.1:53" {
		t.Fatal("unknown countries must use global candidates")
	}
	cfg.Soar.Discovery = false
	initial, candidates = cfg.resolverPlan([]string{"127.0.0.1:5353"})
	if len(initial) != 1 || initial[0] != "127.0.0.1:5353" || len(candidates) != 0 {
		t.Fatal("manual profile changed")
	}
}

func TestScannerRequiresHTTPSRatherThanOnlyAuthentication(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "ok") }))
	defer origin.Close()
	client, cfg := tunnel(t, func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, origin.Listener.Addr().String())
	})
	resolver := client.PathStats()[0].Resolver
	roots := x509.NewCertPool()
	roots.AddCert(origin.Certificate())
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if !probeResolver(ctx, cfg, resolver, roots) {
		t.Fatal("valid HTTPS resolver rejected")
	}
	if probeResolver(ctx, cfg, resolver, x509.NewCertPool()) {
		t.Fatal("scanner accepted a path without trusted HTTPS")
	}
}

// Forward a single client's DNS datagrams. Large responses are dropped and
// every forwarded packet is delayed; blocked can disable a path mid-session.
func restrictedResolver(t *testing.T, server string, blocked *atomic.Bool) string {
	t.Helper()
	pc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	destination, err := net.ResolveUDPAddr("udp", server)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	t.Cleanup(func() { close(done); pc.Close() })
	go func() {
		var client *net.UDPAddr
		buf := make([]byte, 65536)
		for {
			n, from, err := pc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if blocked.Load() {
				continue
			}
			target := destination
			if from.String() == server {
				if n > 512 || client == nil {
					continue
				}
				target = client
			} else {
				client = from
			}
			data := append([]byte{}, buf[:n]...)
			time.AfterFunc(40*time.Millisecond, func() {
				select {
				case <-done:
					return
				default:
				}
				if !blocked.Load() {
					_, _ = pc.WriteToUDP(data, target)
				}
			})
		}
	}()
	return pc.LocalAddr().String()
}

func TestConservativeHTTPSRecoversAfterOneDNSPathIsBlocked(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, strings.Repeat("payload", 300)) }))
	defer origin.Close()
	direct, cfg := tunnel(t, func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, origin.Listener.Addr().String())
	})
	server := direct.PathStats()[0].Resolver
	var firstBlocked, secondBlocked atomic.Bool
	first, second := restrictedResolver(t, server, &firstBlocked), restrictedResolver(t, server, &secondBlocked)
	cfg.Soar.MaxAnswer, cfg.Soar.MaxQName, cfg.Soar.MaxInflight, cfg.Soar.MaxPathWindow = 512, 101, 32, 8
	client, err := soar.NewClient(cfg.clientConfig([]string{first, second}))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	roots := x509.NewCertPool()
	roots.AddCert(origin.Certificate())
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
	defer cancel()
	if err := startupHTTPS(ctx, client.DialContext, roots); err != nil {
		t.Fatal("restricted DNS path failed", err)
	}
	firstBlocked.Store(true)
	if err := startupHTTPS(ctx, client.DialContext, roots); err != nil {
		t.Fatal("second resolver did not recover", err)
	}
}
