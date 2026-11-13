//go:build klethron_test

package main

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
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
