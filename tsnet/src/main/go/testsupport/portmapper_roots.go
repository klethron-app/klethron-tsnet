//go:build klethron_test

// Copied into the pinned portmapper package only for Android instrumentation.
package portmapper

import (
	"context"
	"crypto/x509"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync/atomic"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/net/tlsdial"
	"tailscale.com/types/logger"
)

// KlethronProbeRoots checks HTTPS device discovery and SOAP, including calls whose context no
// longer carries the discovery transport (as when releasing a mapping). Run without other nodes.
func KlethronProbeRoots() string {
	const description = `<root xmlns="urn:schemas-upnp-org:device-1-0"><device><serviceList><service>` +
		`<serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>` +
		`<serviceId>urn:upnp-org:serviceId:WANIPConn1</serviceId><controlURL>/control</controlURL>` +
		`</service></serviceList></device></root>`
	const response = `<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>` +
		`<u:GetExternalIPAddressResponse xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">` +
		`<NewExternalIPAddress>203.0.113.1</NewExternalIPAddress>` +
		`</u:GetExternalIPAddressResponse></s:Body></s:Envelope>`
	var documents, actions atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/xml")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/root.xml":
			documents.Add(1)
			_, _ = io.WriteString(w, description)
		case r.Method == http.MethodPost && r.URL.Path == "/control":
			actions.Add(1)
			_, _ = io.WriteString(w, response)
		default:
			w.WriteHeader(http.StatusBadRequest)
		}
	}))
	defer server.Close()
	previous := tlsdial.SystemRoots
	defer func() { tlsdial.SystemRoots = previous }()
	monitor := netmon.NewStatic()
	defer monitor.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	trustedRoots := x509.NewCertPool()
	trustedRoots.AddCert(server.Certificate())
	tlsdial.SystemRoots = trustedRoots
	client := &Client{netMon: monitor, logf: logger.Discard}
	httpClient := client.upnpHTTPClientLocked()
	defer httpClient.CloseIdleConnections()
	discoveryCtx := upnpHTTPClientKey.WithValue(ctx, httpClient)
	root, location, err := getUPnPRootDevice(discoveryCtx, logger.Discard, DebugKnobs{},
		netip.MustParseAddr("127.0.0.1"), uPnPDiscoResponse{Location: server.URL + "/root.xml"})
	if err != nil || root == nil || documents.Load() != 1 {
		return "UPnP discovery ignored the supplied root"
	}
	service, _, err := selectBestService(discoveryCtx, logger.Discard, root, location)
	if err != nil || service == nil {
		return "UPnP service selection failed"
	}
	// No transport in ctx: the selected SOAP client must retain it.
	if ip, err := service.GetExternalIPAddressCtx(ctx); err != nil || ip != "203.0.113.1" || actions.Load() != 1 {
		return "UPnP SOAP lost the supplied root outside discovery"
	}

	// Empty explicit roots must reject both paths without consulting the filesystem root store.
	tlsdial.SystemRoots = x509.NewCertPool()
	untrusted := &Client{netMon: monitor, logf: logger.Discard}
	untrustedHTTP := untrusted.upnpHTTPClientLocked()
	defer untrustedHTTP.CloseIdleConnections()
	untrustedCtx := upnpHTTPClientKey.WithValue(ctx, untrustedHTTP)
	if _, _, err := getUPnPRootDevice(untrustedCtx, logger.Discard, DebugKnobs{},
		netip.MustParseAddr("127.0.0.1"), uPnPDiscoResponse{Location: server.URL + "/root.xml"}); err == nil || documents.Load() != 1 {
		return "UPnP discovery accepted an untrusted certificate"
	}
	service, _, err = selectBestService(untrustedCtx, logger.Discard, root, location)
	if err != nil || service == nil {
		return "UPnP untrusted service selection failed"
	}
	if _, err := service.GetExternalIPAddressCtx(ctx); err == nil || actions.Load() != 1 {
		return "UPnP SOAP accepted an untrusted certificate"
	}
	return "ok"
}
