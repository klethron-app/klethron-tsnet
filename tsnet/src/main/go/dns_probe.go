//go:build klethron_test

package main

import (
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/dnstype"
)

// The resolver is reachable only through the tailnet. UDP either answers, truncates, or stays
// silent according to the query name; TCP answers all but the UDP-only query.
func startPeerDNS(peer *loopbackTailnet, server *tsnet.Server, ip netip.Addr, control *testcontrol.Server) error {
	_, ip6 := server.TailscaleIPs()
	address := netip.AddrPortFrom(ip, 53).String()
	udp, err := server.ListenPacket("udp", address)
	if err != nil {
		return err
	}
	peer.closers = append(peer.closers, func() { _ = udp.Close() })
	tcp, err := server.Listen("tcp", address)
	if err != nil {
		return err
	}
	peer.closers = append(peer.closers, func() { _ = tcp.Close() })
	go func() {
		buffer := make([]byte, 1500)
		for {
			n, source, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			if response := peerDNSAnswer(buffer[:n], ip, ip6, true); response != nil {
				_, _ = udp.WriteTo(response, source)
			}
		}
	}()
	go serve(tcp, func(conn net.Conn) {
		_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
		var length uint16
		if binary.Read(conn, binary.BigEndian, &length) != nil {
			return
		}
		query := make([]byte, length)
		if _, err := io.ReadFull(conn, query); err != nil {
			return
		}
		response := peerDNSAnswer(query, ip, ip6, false)
		if response == nil {
			return
		}
		packet := binary.BigEndian.AppendUint16(nil, uint16(len(response)))
		_, _ = conn.Write(append(packet, response...))
	})
	addPeers(control, "") // The first fallback answers belong to this unreachable peer.
	control.SetDNSConfig(&tailcfg.DNSConfig{
		Proxied: true,
		ExtraRecords: []tailcfg.DNSRecord{
			{Name: "dual.service.test", Value: ip.String()},
			{Name: "dual.service.test", Value: ip6.String()},
			{Name: "reverse.service.test", Value: ip6.String()},
			{Name: "reverse.service.test", Value: ip.String()},
			{Name: "same-family.service.test", Value: "100.101.0.1"},
			{Name: "same-family.service.test", Value: ip.String()},
		},
		Routes: map[string][]*dnstype.Resolver{
			"split.test": {{Addr: address}},
			"MiXeD.TeSt": {{Addr: address}},
		},
	})
	return nil
}

func peerDNSAnswer(query []byte, ip, ip6 netip.Addr, udp bool) []byte {
	var request dnsmessage.Message
	if request.Unpack(query) != nil || len(request.Questions) != 1 {
		return nil
	}
	question := request.Questions[0]
	name := strings.ToLower(question.Name.String())
	if (udp && strings.HasPrefix(name, "timeout.")) || (!udp && strings.HasPrefix(name, "udp.")) {
		return nil
	}
	response := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID: request.ID, Response: true, RecursionDesired: request.RecursionDesired,
			RecursionAvailable: true, Truncated: udp && strings.HasPrefix(name, "tcp."),
		},
		Questions: request.Questions,
	}
	addresses := []netip.Addr{ip}
	switch name {
	case "fallback4.split.test.":
		addresses = []netip.Addr{netip.MustParseAddr("100.101.0.1"), ip}
	case "fallback6.split.test.":
		addresses = []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::101:1"), ip6}
	case "dual.split.test.":
		addresses = []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::101:1"), ip}
	case "mixed.split.test.":
		addresses = []netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.AddrFrom16(ip.As16()), ip, ip}
	case "blocked.split.test.":
		addresses = []netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.MustParseAddr("::1")}
	}
	if !response.Truncated {
		for _, address := range addresses {
			answer := dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET}}
			switch {
			case question.Type == dnsmessage.TypeA && address.Is4():
				answer.Body = &dnsmessage.AResource{A: address.As4()}
			case question.Type == dnsmessage.TypeAAAA && address.Is6():
				answer.Body = &dnsmessage.AAAAResource{AAAA: address.As16()}
			default:
				continue
			}
			response.Answers = append(response.Answers, answer)
		}
	}
	packet, _ := response.Pack()
	return packet
}
