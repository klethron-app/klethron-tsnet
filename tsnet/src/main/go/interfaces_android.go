package main

/*
#include <ifaddrs.h>
#include <netinet/in.h>
*/
import "C"

import (
	"errors"
	"fmt"
	"net"
	"unsafe"

	"golang.org/x/sys/unix"
	"tailscale.com/net/netmon"
)

func init() {
	// Android app UIDs cannot use Go's RTM_GETLINK interface discovery.
	// Register once, before tsnet can create any monitors or goroutines.
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

// androidInterfaces uses bionic's public getifaddrs implementation, which can
// enumerate IPv4/IPv6 addresses without RTM_GETLINK. Snapshot addresses as well
// as metadata: falling back to net.Interface.Addrs would re-enter Go's netlink path.
func androidInterfaces() ([]netmon.Interface, error) {
	var head *C.struct_ifaddrs
	if result, err := C.getifaddrs(&head); result != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(head)

	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("interface socket: %w", err)
	}
	defer unix.Close(fd)

	var result []netmon.Interface
	byName := make(map[string]int)
	for entry := head; entry != nil; entry = entry.ifa_next {
		if entry.ifa_name == nil || entry.ifa_addr == nil || entry.ifa_netmask == nil {
			continue
		}
		ip := interfaceIP(entry.ifa_addr)
		mask := net.IPMask(interfaceIP(entry.ifa_netmask))
		if ip == nil || len(ip) != len(mask) {
			continue
		}
		if _, bits := mask.Size(); bits != len(ip)*8 {
			continue // Not a prefix mask.
		}
		name := C.GoString(entry.ifa_name)
		index, found := byName[name]
		if !found {
			req, err := unix.NewIfreq(name)
			if err != nil {
				return nil, err
			}
			iface := &net.Interface{Name: name}
			var flags uint16
			err = unix.IoctlIfreq(fd, unix.SIOCGIFINDEX, req)
			if err == nil {
				iface.Index = int(req.Uint32())
				err = unix.IoctlIfreq(fd, unix.SIOCGIFMTU, req)
			}
			if err == nil {
				iface.MTU = int(req.Uint32())
				// Query flags explicitly: bionic can return success even if its
				// own flag lookup failed, leaving ifa_flags unset.
				err = unix.IoctlIfreq(fd, unix.SIOCGIFFLAGS, req)
				flags = req.Uint16()
			}
			if errors.Is(err, unix.ENODEV) {
				byName[name] = -1 // Removed since getifaddrs, as during network changes.
				continue
			}
			if err != nil {
				return nil, fmt.Errorf("interface %s: %w", name, err)
			}
			for _, flag := range []struct {
				native uint16
				goFlag net.Flags
			}{
				{unix.IFF_UP, net.FlagUp}, {unix.IFF_BROADCAST, net.FlagBroadcast},
				{unix.IFF_LOOPBACK, net.FlagLoopback}, {unix.IFF_POINTOPOINT, net.FlagPointToPoint},
				{unix.IFF_MULTICAST, net.FlagMulticast}, {unix.IFF_RUNNING, net.FlagRunning},
			} {
				if flags&flag.native != 0 {
					iface.Flags |= flag.goFlag
				}
			}
			index = len(result)
			byName[name] = index
			result = append(result, netmon.Interface{Interface: iface, AltAddrs: []net.Addr{}})
		}
		if index < 0 {
			continue
		}
		result[index].AltAddrs = append(result[index].AltAddrs, &net.IPNet{IP: ip, Mask: mask})
	}
	return result, nil
}

// Copy the network-order bytes before freeifaddrs releases their storage.
// net.IPNet carries no IPv6 zone; the enclosing interface supplies its index.
func interfaceIP(addr *C.struct_sockaddr) net.IP {
	switch addr.sa_family {
	case C.AF_INET:
		v4 := (*C.struct_sockaddr_in)(unsafe.Pointer(addr))
		return C.GoBytes(unsafe.Pointer(&v4.sin_addr), 4)
	case C.AF_INET6:
		v6 := (*C.struct_sockaddr_in6)(unsafe.Pointer(addr))
		return C.GoBytes(unsafe.Pointer(&v6.sin6_addr), 16)
	default:
		return nil
	}
}
