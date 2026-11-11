//go:debug x509usefallbackroots=1

package main

import (
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sync"
)

// Android 14 moved the updatable system CA store into the Conscrypt APEX. Go's crypto/x509 reads only
// the system image copy, missing Mainline root additions and removals, and ignores roots the user
// disabled in Settings. The directive above lets the platform store replace Go's on-disk discovery.
// Like Android's default for apps targeting API 24+, user-added CAs are not trusted.
var rootDirectories = []string{"/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"}

// usePlatformRoots must run before any TLS verification in this library: Go loads system roots once.
var usePlatformRoots = sync.OnceFunc(func() {
	pool := x509.NewCertPool()
	for _, root := range loadPlatformRoots() {
		pool.AddCert(root)
	}
	// An empty pool fails closed instead of falling back to the system image copy.
	x509.SetFallbackRoots(pool)
})

// loadPlatformRoots mirrors Android's TrustedCertificateStore: use the first existing system
// directory, without certificates the current user disabled.
func loadPlatformRoots() []*x509.Certificate {
	removed := make(map[string]bool)
	// Android users have UID ranges of 100000.
	for _, cert := range readCertificates(fmt.Sprintf("/data/misc/user/%d/cacerts-removed", os.Getuid()/100000)) {
		removed[string(cert.Raw)] = true
	}
	for _, directory := range rootDirectories {
		if _, err := os.Stat(directory); err == nil {
			return slices.DeleteFunc(readCertificates(directory), func(cert *x509.Certificate) bool { return removed[string(cert.Raw)] })
		}
	}
	return nil
}

func readCertificates(directory string) (certs []*x509.Certificate) {
	entries, _ := os.ReadDir(directory)
	for _, entry := range entries {
		data, err := os.ReadFile(filepath.Join(directory, entry.Name()))
		if err != nil {
			continue
		}
		for block, rest := pem.Decode(data); block != nil; block, rest = pem.Decode(rest) {
			if block.Type == "CERTIFICATE" {
				if cert, err := x509.ParseCertificate(block.Bytes); err == nil {
					certs = append(certs, cert)
				}
			}
		}
	}
	return certs
}
