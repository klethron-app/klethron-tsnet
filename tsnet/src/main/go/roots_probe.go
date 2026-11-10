//go:build klethron_test

package main

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"strings"
)

// probeRoots returns the SHA-256 hex fingerprints of the platform roots, one per line, once they
// replace Go's system pool, or a fixed diagnostic.
func probeRoots(string) string {
	usePlatformRoots() // As node creation does.
	var roots []string
	pool := x509.NewCertPool()
	for _, root := range loadPlatformRoots() {
		pool.AddCert(root)
		sum := sha256.Sum256(root.Raw)
		roots = append(roots, hex.EncodeToString(sum[:]))
	}
	if system, err := x509.SystemCertPool(); err != nil || !system.Equal(pool) {
		return "platform roots did not replace Go's system pool"
	}
	return strings.Join(roots, "\n")
}
