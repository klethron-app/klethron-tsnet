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

// SELinux denies app UIDs the RTM_GETLINK of Go's interface discovery. Register before tsnet runs.
func init() { netmon.RegisterInterfaceGetter(androidInterfaces) }

// androidInterfaces lists interfaces and their addresses with bionic's getifaddrs, which needs no
// RTM_GETLINK. Returning the addresses avoids net.Interface.Addrs, which takes Go's netlink path.
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
			err = unix.IoctlIfreq(fd, unix.SIOCGIFINDEX, req)
			if err == nil {
				iface.Index = int(req.Uint32())
				err = unix.IoctlIfreq(fd, unix.SIOCGIFMTU, req)
			}
			if err == nil {
				iface.MTU = int(req.Uint32())
				// bionic can leave ifa_flags unset yet return success.
				err = unix.IoctlIfreq(fd, unix.SIOCGIFFLAGS, req)
			}
			if errors.Is(err, unix.ENODEV) {
				continue // Removed since getifaddrs, as during network changes.
			}
			if err != nil {
				return nil, fmt.Errorf("interface %s: %w", name, err)
			}
			for bit, native := range [...]uint16{unix.IFF_UP, unix.IFF_BROADCAST, unix.IFF_LOOPBACK,
				unix.IFF_POINTOPOINT, unix.IFF_MULTICAST, unix.IFF_RUNNING} {
				if req.Uint16()&native != 0 {
					iface.Flags |= net.FlagUp << bit
				}
			}
			index = len(result)
			byName[name] = index
			result = append(result, netmon.Interface{Interface: iface, AltAddrs: []net.Addr{}})
		}
		result[index].AltAddrs = append(result[index].AltAddrs, &net.IPNet{IP: ip, Mask: mask})
	}
	return result, nil
}

// interfaceIP copies addr's IP before freeifaddrs frees it. IPv6 zones come from the interface index.
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
