//go:build klethron_test

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"sort"
)

// This debug-only bridge reports SHA-256 fingerprints of public root certificates.
//
//export klethron_roots_probe
func klethron_roots_probe() *C.char {
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
	return C.CString(string(data))
}
