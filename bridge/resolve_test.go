package tsbridge

import (
	"net/netip"
	"testing"
	"time"
)

// engineWithNames returns an engine whose peer table is already fresh, so
// name lookups never ask the tailnet for status.
func engineWithNames(names map[string]string, prefixes ...netip.Prefix) *engine {
	if names == nil {
		names = map[string]string{}
	}
	e := &engine{}
	e.names.byName = names
	e.names.prefixes = prefixes
	e.names.updated = time.Now()
	return e
}

func TestResolveHostKeepsIPLiterals(t *testing.T) {
	e := engineWithNames(map[string]string{"nas": "100.64.0.5"})
	for _, in := range []string{"100.64.0.8", "[fd7a::1]", "fd7a::1"} {
		if got := e.resolveHost(in); got != in {
			t.Fatalf("resolveHost(%q) = %q, want it unchanged", in, got)
		}
	}
}

func TestResolveHostMapsPeerNames(t *testing.T) {
	e := engineWithNames(map[string]string{"nas": "100.64.0.5"})
	for _, in := range []string{"nas", "NAS", "nas."} {
		if got := e.resolveHost(in); got != "100.64.0.5" {
			t.Fatalf("resolveHost(%q) = %q, want 100.64.0.5", in, got)
		}
	}
}

func TestResolveHostLeavesUnknownNames(t *testing.T) {
	e := engineWithNames(map[string]string{"nas": "100.64.0.5"})
	if got := e.resolveHost("printer.lan"); got != "printer.lan" {
		t.Fatalf("resolveHost(printer.lan) = %q", got)
	}
}

func TestResolveHostPort(t *testing.T) {
	e := engineWithNames(map[string]string{"nas": "100.64.0.5"})
	for in, want := range map[string]string{
		"nas:5000":       "100.64.0.5:5000",
		"printer.lan:80": "printer.lan:80",
		"nas":            "nas",
		"[fd7a::1]:22":   "[fd7a::1]:22",
	} {
		if got := e.resolveHostPort(in); got != want {
			t.Fatalf("resolveHostPort(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestInTailnetUsesPeerAddressesAndSubnetRoutes(t *testing.T) {
	e := engineWithNames(nil,
		netip.MustParsePrefix("100.64.0.0/10"),
		netip.MustParsePrefix("192.168.1.0/24"),
	)
	for ip, want := range map[string]bool{
		"100.100.1.2":         true,
		"192.168.1.20":        true,
		"::ffff:192.168.1.20": true,
		"8.8.8.8":             false,
	} {
		if got := e.inTailnet(netip.MustParseAddr(ip)); got != want {
			t.Fatalf("inTailnet(%s) = %v, want %v", ip, got, want)
		}
	}
}
