//go:build klethron_test

package main

import (
	"bytes"
	"context"
	"crypto/x509"
	"io"
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

// Exercise the upstream DoH transport, including TLS verification, without public DNS or disk roots.
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
	dialer := tsdial.NewDialer(netmon.NewStatic())
	dialer.SetBus(bus)
	defer dialer.Close()
	for _, trusted := range []bool{false, true} {
		roots := x509.NewCertPool()
		if trusted {
			roots.AddCert(server.Certificate())
		}
		tlsdial.SystemRoots = roots
		dns := resolver.New(logger.Discard, nil, dialer, health.NewTracker(bus), nil)
		if err := dns.SetConfig(resolver.Config{Routes: map[dnsname.FQDN][]*dnstype.Resolver{
			".": {{Addr: server.URL + "/dns-query"}},
		}}); err != nil {
			dns.Close()
			return "resolver configuration failed"
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		response, err := dns.Query(ctx, query, "udp", netip.MustParseAddrPort("100.64.0.1:1234"))
		cancel()
		dns.Close()
		if err != nil || response == nil {
			return "DoH query failed"
		}
		var message dnsmessage.Message
		if err := message.Unpack(response.Bs); err != nil {
			return "invalid DoH response"
		}
		if trusted {
			if !bytes.Equal(response.Bs, answer) || requests.Load() != 1 {
				return "DoH ignored the supplied root"
			}
		} else if message.RCode != dnsmessage.RCodeServerFailure || requests.Load() != 0 {
			return "DoH accepted an untrusted certificate"
		}
	}
	return "ok"
}
