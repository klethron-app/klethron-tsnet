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
			if response := peerDNSAnswer(buffer[:n], ip, true); response != nil {
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
		response := peerDNSAnswer(query, ip, false)
		if response == nil {
			return
		}
		packet := binary.BigEndian.AppendUint16(nil, uint16(len(response)))
		_, _ = conn.Write(append(packet, response...))
	})
	control.SetDNSConfig(&tailcfg.DNSConfig{
		Proxied: true,
		Routes: map[string][]*dnstype.Resolver{
			"split.test": {{Addr: address}},
			"MiXeD.TeSt": {{Addr: address}},
		},
	})
	return nil
}

func peerDNSAnswer(query []byte, ip netip.Addr, udp bool) []byte {
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
	if !response.Truncated && question.Type == dnsmessage.TypeA {
		response.Answers = []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET},
			Body:   &dnsmessage.AResource{A: ip.As4()},
		}}
	}
	packet, _ := response.Pack()
	return packet
}
