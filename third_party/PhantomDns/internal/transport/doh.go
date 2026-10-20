package transport

import (
	"bytes"
	"io"
	"net"
	"net/http"
	"sync"
	"time"
)

type DoHPacketConn struct {
	client    *http.Client
	urlString string
	recvQueue chan []byte
	closed    chan struct{}
	closeOnce sync.Once
}

func NewDoHPacketConn(rt http.RoundTripper, urlString string) *DoHPacketConn {
	return &DoHPacketConn{
		client: &http.Client{
			Transport: rt,
			Timeout:   15 * time.Second,
		},
		urlString: urlString,
		recvQueue: make(chan []byte, 1024),
		closed:    make(chan struct{}),
	}
}

func (c *DoHPacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
	select {
	case <-c.closed:
		return 0, nil, net.ErrClosed
	case msg := <-c.recvQueue:
		n = copy(p, msg)
		dummyAddr := &net.UDPAddr{IP: net.ParseIP("1.1.1.1"), Port: 443}
		return n, dummyAddr, nil
	}
}

func (c *DoHPacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	buf := make([]byte, len(p))
	copy(buf, p)
	go c.sendHTTP(buf)
	
	return len(p), nil
}

func (c *DoHPacketConn) sendHTTP(p []byte) {
	req, err := http.NewRequest("POST", c.urlString, bytes.NewReader(p))
	if err != nil {
		return
	}
	
	// هدرهای استاندارد DoH (RFC 8484)
	req.Header.Set("Accept", "application/dns-message")
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("User-Agent", "")

	resp, err := c.client.Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK && resp.Header.Get("Content-Type") == "application/dns-message" {
		body, err := io.ReadAll(io.LimitReader(resp.Body, 65536))
		if err == nil && len(body) > 0 {
			select {
			case <-c.closed:
			case c.recvQueue <- body:
			default:
			}
		}
	}
}

func (c *DoHPacketConn) Close() error {
	c.closeOnce.Do(func() {
		close(c.closed)
	})
	return nil
}
func (c *DoHPacketConn) LocalAddr() net.Addr                { return &net.UDPAddr{IP: net.ParseIP("0.0.0.0"), Port: 0} }
func (c *DoHPacketConn) SetDeadline(t time.Time) error      { return nil }
func (c *DoHPacketConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *DoHPacketConn) SetWriteDeadline(t time.Time) error { return nil }