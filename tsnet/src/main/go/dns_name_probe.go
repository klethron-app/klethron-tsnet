//go:build klethron_test

package main

import "tailscale.com/util/dnsname"

func probeDNSName(name string) string {
	fqdn, err := dnsname.ToFQDN(name)
	if err != nil {
		return "invalid"
	}
	return fqdn.WithTrailingDot()
}
