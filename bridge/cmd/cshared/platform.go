package main

import (
	"encoding/json"
	"net"
	"strings"
)

// goPlatform supplies the interfaces, default route and DNS lookups that the
// Android app takes from Java. Go's own APIs are enough on desktop.
type goPlatform struct{}

type ifaceJSON struct {
	Name     string   `json:"name"`
	Index    int      `json:"index"`
	MTU      int      `json:"mtu"`
	Up       bool     `json:"up"`
	Loopback bool     `json:"loopback"`
	Addrs    []string `json:"addrs"`
}

func (goPlatform) Interfaces() string {
	list, err := net.Interfaces()
	if err != nil {
		return "[]"
	}
	out := make([]ifaceJSON, 0, len(list))
	for _, it := range list {
		item := ifaceJSON{
			Name:     it.Name,
			Index:    it.Index,
			MTU:      it.MTU & 0xffff,
			Up:       it.Flags&net.FlagUp != 0,
			Loopback: it.Flags&net.FlagLoopback != 0,
			Addrs:    []string{},
		}
		addrs, err := it.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipnet, ok := a.(*net.IPNet); ok {
				item.Addrs = append(item.Addrs, ipnet.String())
			}
		}
		out = append(out, item)
	}
	b, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// DefaultInterface matches the local address of a route lookup against the
// interface list. A UDP dial sends nothing, so this works offline.
func (goPlatform) DefaultInterface() string {
	if name := defaultInterfaceFromRoute(); name != "" {
		return name
	}
	list, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, it := range list {
		if it.Flags&net.FlagUp != 0 && it.Flags&net.FlagLoopback == 0 {
			return it.Name
		}
	}
	return ""
}

func defaultInterfaceFromRoute() string {
	conn, err := net.Dial("udp4", "1.1.1.1:53")
	if err != nil {
		return ""
	}
	defer conn.Close()
	local, ok := conn.LocalAddr().(*net.UDPAddr)
	if !ok || local.IP == nil {
		return ""
	}
	list, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, it := range list {
		addrs, err := it.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipnet, ok := a.(*net.IPNet); ok && ipnet.IP.Equal(local.IP) {
				return it.Name
			}
		}
	}
	return ""
}

func (goPlatform) Lookup(host string) string {
	ips, err := net.LookupIP(host)
	if err != nil {
		return ""
	}
	out := make([]string, 0, len(ips))
	for _, ip := range ips {
		out = append(out, ip.String())
	}
	return strings.Join(out, ",")
}
