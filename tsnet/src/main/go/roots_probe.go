//go:build klethron_test

package main

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"sort"
)

// probeRoots reports SHA-256 fingerprints of public root certificates.
func probeRoots(string) string {
	usePlatformRoots() // As node creation does.
	result := struct {
		Roots      []string
		SystemPool bool
	}{}
	pool := x509.NewCertPool()
	for _, root := range loadPlatformRoots() {
		pool.AddCert(root)
		sum := sha256.Sum256(root.Raw)
		result.Roots = append(result.Roots, hex.EncodeToString(sum[:]))
	}
	system, err := x509.SystemCertPool()
	result.SystemPool = err == nil && system.Equal(pool)
	sort.Strings(result.Roots)
	data, _ := json.Marshal(result) // Strings and booleans only; cannot fail.
	return string(data)
}
