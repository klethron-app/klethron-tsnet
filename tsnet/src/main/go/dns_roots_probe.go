//go:build klethron_test

package main

import (
	"bytes"
	"context"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync/atomic"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/health"
	"tailscale.com/net/dns/resolver"
	"tailscale.com/net/netmon"
	"tailscale.com/net/tlsdial"
	"tailscale.com/net/tsdial"
	"tailscale.com/types/dnstype"
	"tailscale.com/types/logger"
	"tailscale.com/util/dnsname"
	"tailscale.com/util/eventbus"
)

// Exercise DoH TLS verification and tailnet/system dial dispatch without public DNS or disk roots.
func probeDNSRoots(string) string {
	question := dnsmessage.Question{Name: dnsmessage.MustNewName("roots.test."),
		Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}
	query, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 1, RecursionDesired: true},
		Questions: []dnsmessage.Question{question}}).Pack()
	if err != nil {
		return "query encoding failed"
	}
	answer, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 1, Response: true},
		Questions: []dnsmessage.Question{question}, Answers: []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: question.Name, Type: question.Type, Class: question.Class},
			Body:   &dnsmessage.AResource{A: [4]byte{100, 64, 0, 99}},
		}}}).Pack()
	if err != nil {
		return "answer encoding failed"
	}
	var requests atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		body, err := io.ReadAll(r.Body)
		if err != nil || r.Method != http.MethodPost || r.URL.Path != "/dns-query" || !bytes.Equal(body, query) {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(answer)
	}))
	defer server.Close()
	previous := tlsdial.SystemRoots
	defer func() { tlsdial.SystemRoots = previous }()
	bus := eventbus.New()
	defer bus.Close()
	for _, useNetstack := range []bool{false, true} {
		for _, trusted := range []bool{false, true} {
			roots := x509.NewCertPool()
			if trusted {
				roots.AddCert(server.Certificate())
			}
			tlsdial.SystemRoots = roots
			upstream := &dnstype.Resolver{Addr: server.URL + "/dns-query"}
			target := netip.MustParseAddrPort(server.Listener.Addr().String())
			if useNetstack {
				// Bootstrap to a synthetic tailnet IP while keeping the TLS name unchanged.
				target = netip.AddrPortFrom(netip.MustParseAddr("100.64.0.53"), target.Port())
				upstream.BootstrapResolution = []netip.Addr{target.Addr()}
			}
			var systemDials, netstackDials atomic.Int32
			dial := func(ctx context.Context, network, address string) (net.Conn, error) {
				if address != target.String() {
					return nil, errors.New("unexpected DoH dial destination")
				}
				return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
			}
			dialer := tsdial.NewDialer(netmon.NewStatic())
			dialer.SetBus(bus)
			defer dialer.Close()
			dialer.UseNetstackForIP = func(ip netip.Addr) bool { return useNetstack && ip == target.Addr() }
			dialer.NetstackDialTCP = func(ctx context.Context, address netip.AddrPort) (net.Conn, error) {
				netstackDials.Add(1)
				return dial(ctx, "tcp", address.String())
			}
			dialer.SetSystemDialer(func(ctx context.Context, network, address string) (net.Conn, error) {
				systemDials.Add(1)
				return dial(ctx, network, address)
			})
			dns := resolver.New(logger.Discard, nil, dialer, health.NewTracker(bus), nil)
			if err := dns.SetConfig(resolver.Config{Routes: map[dnsname.FQDN][]*dnstype.Resolver{
				".": {upstream},
			}}); err != nil {
				dns.Close()
				return "resolver configuration failed"
			}
			before := requests.Load()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			response, err := dns.Query(ctx, query, "udp", netip.MustParseAddrPort("100.64.0.1:1234"))
			cancel()
			dns.Close()
			if err != nil || response == nil {
				return "DoH query failed"
			}
			if useNetstack && (netstackDials.Load() == 0 || systemDials.Load() != 0) {
				return "DoH bypassed netstack for a tailnet resolver"
			}
			if !useNetstack && (systemDials.Load() == 0 || netstackDials.Load() != 0) {
				return "DoH ignored the system dialer for a host resolver"
			}
			var message dnsmessage.Message
			if err := message.Unpack(response.Bs); err != nil {
				return "invalid DoH response"
			}
			if trusted {
				if !bytes.Equal(response.Bs, answer) || requests.Load() != before+1 {
					return "DoH ignored the supplied root"
				}
			} else if message.RCode != dnsmessage.RCodeServerFailure || requests.Load() != before {
				return "DoH accepted an untrusted certificate"
			}
		}
	}
	return "ok"
}
