package main

import "C"

import (
	"crypto/x509"
	"unsafe"

	"tailscale.com/net/tlsdial"
)

var platformRoots = x509.NewCertPool()
var platformCertificates []*x509.Certificate

func init() { tlsdial.SystemRoots = platformRoots }

// Java supplies AndroidCAStore's enabled system roots before any node starts.
//
//export klethron_set_roots
func klethron_set_roots(data *C.char, length C.int) C.int {
	roots, err := x509.ParseCertificates(C.GoBytes(unsafe.Pointer(data), length))
	if err != nil {
		return -1
	}
	platformCertificates = roots
	for _, root := range roots {
		platformRoots.AddCert(root)
	}
	return 0
}
