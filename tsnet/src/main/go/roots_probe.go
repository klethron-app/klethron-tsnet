//go:build klethron_test

package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"net/http/httptest"
	"strings"
	"time"

	"tailscale.com/net/tlsdial"
)

// Fingerprint the actual roots used by upstream TLS verification (complete DER certificates).
func probeRoots(string) string {
	var roots []string
	for _, cert := range platformCertificates {
		sum := sha256.Sum256(cert.Raw)
		roots = append(roots, hex.EncodeToString(sum[:]))
	}
	return strings.Join(roots, "\n")
}

// DERP can send one TLS server name while verifying a different control-supplied CertName.
// Exercise both verification paths with a private root, including rejection of a wrong name.
func probeTLSRoots(string) string {
	server := httptest.NewTLSServer(nil)
	defer server.Close()
	previous := tlsdial.SystemRoots
	defer func() { tlsdial.SystemRoots = previous }()
	for _, trusted := range []bool{false, true} {
		roots := x509.NewCertPool()
		if trusted {
			roots.AddCert(server.Certificate())
		}
		tlsdial.SystemRoots = roots
		for _, expectedName := range []string{"", "example.com", "wrong.invalid"} {
			config := tlsdial.Config(nil, nil)
			config.ServerName = "example.com" // httptest's certificate name.
			if expectedName != "" {
				config.ServerName = "front.invalid"
				tlsdial.SetConfigExpectedCert(config, expectedName)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			conn, err := (&tls.Dialer{Config: config}).DialContext(ctx, "tcp", server.Listener.Addr().String())
			cancel()
			if conn != nil {
				_ = conn.Close()
			}
			if wantSuccess := trusted && expectedName != "wrong.invalid"; (err == nil) != wantSuccess {
				return "TLS root or certificate-name verification failed: " + expectedName
			}
		}
	}
	return "ok"
}
