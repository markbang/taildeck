package tsbridge

import "testing"

func TestTargetLabelRoundTrips(t *testing.T) {
	for _, tg := range []target{
		{host: "nas", port: 5000},
		{host: "nas", port: 443, https: true},
		{host: "my-nas", port: 80},
		{host: "100.64.0.8", port: 8080},
	} {
		got, ok := parseLabel(tg.label() + ".localhost")
		if !ok || got != tg {
			t.Fatalf("label %q parsed to %+v, %v; want %+v", tg.label(), got, ok, tg)
		}
	}
}

func TestTargetLabelIsLowercase(t *testing.T) {
	if got := (target{host: "NAS", port: 80}).label(); got != "nas-80" {
		t.Fatalf("label = %q", got)
	}
}

func TestParseLabelIgnoresHostPort(t *testing.T) {
	got, ok := parseLabel("nas-80.localhost:43210")
	if !ok || got != (target{host: "nas", port: 80}) {
		t.Fatalf("parseLabel = %+v, %v", got, ok)
	}
}

func TestParseLabelRejectsOtherHosts(t *testing.T) {
	for _, h := range []string{
		"nas.localhost",
		"a.b-80.localhost",
		"nas-0.localhost",
		"nas-70000.localhost",
		"nas-80.example.com",
	} {
		if got, ok := parseLabel(h); ok {
			t.Fatalf("parseLabel(%q) = %+v, want rejected", h, got)
		}
	}
}

func TestTargetOrigin(t *testing.T) {
	for tg, want := range map[target]string{
		{host: "nas", port: 80}:                   "http://nas",
		{host: "nas", port: 443, https: true}:     "https://nas",
		{host: "nas", port: 8080}:                 "http://nas:8080",
		{host: "fd7a::1", port: 443, https: true}: "https://[fd7a::1]",
	} {
		if got := tg.origin(); got != want {
			t.Fatalf("origin(%+v) = %q, want %q", tg, got, want)
		}
	}
}
