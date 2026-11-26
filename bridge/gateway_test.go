package tsbridge

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

// A NAS file server behind the gateway: Range must come back as 206 with the
// upstream's Content-Range and Content-Type, on the label host (gateway URL)
// and on the forward proxy (the WebView's proxy override).
func TestGatewayPassesRange(t *testing.T) {
	body := bytes.Repeat([]byte("0123456789"), 100000)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		http.ServeContent(w, r, "v.mp4", time.Unix(0, 0), bytes.NewReader(body))
	}))
	defer up.Close()
	gw := testGateway(t)
	_, upPort, _ := net.SplitHostPort(up.Listener.Addr().String())

	label, _ := http.NewRequest("GET", gw.URL+"/d/v.mp4", nil)
	label.Host = fmt.Sprintf("127-0-0-1-%s.localhost", upPort)
	proxied := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(mustURL(t, gw.URL))}}
	forward, _ := http.NewRequest("GET", up.URL+"/d/v.mp4", nil)

	for name, c := range map[string]struct {
		client *http.Client
		req    *http.Request
	}{"label": {http.DefaultClient, label}, "forward": {proxied, forward}} {
		c.req.Header.Set("Range", "bytes=500000-501023")
		resp, err := c.client.Do(c.req)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		got, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusPartialContent ||
			resp.Header.Get("Content-Range") != "bytes 500000-501023/1000000" ||
			resp.Header.Get("Content-Type") != "video/mp4" ||
			!bytes.Equal(got, body[500000:501024]) {
			t.Fatalf("%s: %d %v, %d bytes", name, resp.StatusCode, resp.Header, len(got))
		}
	}
}

// A long response streams: the first bytes reach the client while the
// upstream is still writing.
func TestGatewayStreams(t *testing.T) {
	release := make(chan struct{})
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		w.Write(make([]byte, 64<<10))
		w.(http.Flusher).Flush()
		<-release
	}))
	defer up.Close()
	defer close(release)
	gw := testGateway(t)
	_, upPort, _ := net.SplitHostPort(up.Listener.Addr().String())
	req, _ := http.NewRequest("GET", gw.URL+"/big.mp4", nil)
	req.Host = fmt.Sprintf("127-0-0-1-%s.localhost", upPort)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := http.DefaultClient.Do(req.WithContext(ctx))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if _, err := io.ReadFull(resp.Body, make([]byte, 64<<10)); err != nil {
		t.Fatalf("the first 64 KiB did not stream: %v", err)
	}
}

func testGateway(t *testing.T) *httptest.Server {
	g := &gateway{}
	g.setup(func(ctx context.Context, addr string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "tcp", addr)
	})
	srv := httptest.NewServer(g)
	t.Cleanup(srv.Close)
	return srv
}

func mustURL(t *testing.T, raw string) *url.URL {
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	return u
}

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
