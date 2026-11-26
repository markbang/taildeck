package tsbridge

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

// 1x1 PNG, so a test can prove which candidate answered.
var testPNG = func() []byte {
	img := image.NewRGBA(image.Rect(0, 0, 1, 1))
	img.SetRGBA(0, 0, color.RGBA{R: 1, G: 2, B: 3, A: 255})
	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		panic(err)
	}
	return out.Bytes()
}()

func serveFavicon(t *testing.T, handler http.HandlerFunc) (host string, port int, client *http.Client) {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	u := strings.TrimPrefix(srv.URL, "http://")
	h, p, _ := strings.Cut(u, ":")
	n, err := strconv.Atoi(p)
	if err != nil {
		t.Fatalf("bad test server url %q: %v", srv.URL, err)
	}
	return h, n, &http.Client{Timeout: 2 * time.Second}
}

func lookup(t *testing.T, client *http.Client, host string, port int) FaviconResult {
	t.Helper()
	res, err := fetchFavicon(context.Background(), client, host, []int{port}, "http")
	if err != nil {
		t.Fatalf("fetchFavicon: %v", err)
	}
	return res
}

func decode(t *testing.T, res FaviconResult) []byte {
	t.Helper()
	data, err := base64.StdEncoding.DecodeString(res.Bytes)
	if err != nil {
		t.Fatalf("base64: %v", err)
	}
	return data
}

func writePNG(w http.ResponseWriter, data []byte) {
	w.Header().Set("Content-Type", "image/png")
	w.Write(data)
}

// A relative <link rel="icon"> resolves against the page and wins over the
// ico fallback.
func TestFaviconLinkTag(t *testing.T) {
	host, port, client := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			w.Header().Set("Content-Type", "text/html")
			w.Write([]byte(`<html><head>
<link rel="shortcut icon" href="/old.ico">
<link rel="icon" type="image/png" sizes="192x192" href="assets/icon-192.png">
</head></html>`))
		case "/assets/icon-192.png":
			writePNG(w, testPNG)
		case "/favicon.ico":
			t.Error("the ico fallback must not run when a link tag answers")
		default:
			http.NotFound(w, r)
		}
	})
	res := lookup(t, client, host, port)
	if res.ContentType != "image/png" || res.Source != "link" {
		t.Fatalf("want a png from a link tag, got %+v", res)
	}
	if !strings.HasSuffix(res.URL, "/assets/icon-192.png") {
		t.Fatalf("relative href not resolved: %s", res.URL)
	}
	if got := decode(t, res); !bytes.Equal(got, testPNG) {
		t.Fatal("wrong bytes")
	}
}

// Manifest icons are used when the page advertises none, preferring purpose
// any and a phone-sized icon.
func TestFaviconManifestIcons(t *testing.T) {
	host, port, client := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			w.Write([]byte(`<html><head><link rel="manifest" href="/app.webmanifest"></head></html>`))
		case "/app.webmanifest":
			w.Header().Set("Content-Type", "application/manifest+json")
			w.Write([]byte(`{"icons":[
				{"src":"/mask-512.png","sizes":"512x512","type":"image/png","purpose":"maskable"},
				{"src":"/small-96.png","sizes":"96x96","type":"image/png","purpose":"any"},
				{"src":"/icon-192.png","sizes":"192x192","type":"image/png","purpose":"any"}
			]}`))
		case "/icon-192.png":
			writePNG(w, testPNG)
		default:
			t.Errorf("unexpected request %s", r.URL.Path)
		}
	})
	res := lookup(t, client, host, port)
	if res.Source != "manifest" || !strings.HasSuffix(res.URL, "/icon-192.png") {
		t.Fatalf("want /icon-192.png from the manifest, got %+v", res)
	}
}

// No tags at all: /favicon.ico, and its BMP entry is turned into a PNG.
func TestFaviconIcoFallback(t *testing.T) {
	ico := makeICO(t, 2, 2, func(x, y int) color.RGBA {
		if x == 0 {
			return color.RGBA{R: 200, A: 255}
		}
		return color.RGBA{B: 200, A: 255}
	})
	host, port, client := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/favicon.ico" {
			w.Header().Set("Content-Type", "text/plain")
			w.Write(ico)
			return
		}
		http.NotFound(w, r)
	})
	res := lookup(t, client, host, port)
	if res.ContentType != "image/png" || res.Source != "ico" {
		t.Fatalf("want the ico fallback converted to png, got %+v", res)
	}
	img, err := png.Decode(bytes.NewReader(decode(t, res)))
	if err != nil {
		t.Fatalf("converted png does not decode: %v", err)
	}
	if b := img.Bounds(); b.Dx() != 2 || b.Dy() != 2 {
		t.Fatalf("want 2x2, got %v", b)
	}
	if r, g, _, _ := img.At(0, 0).RGBA(); r>>8 != 200 || g>>8 != 0 {
		t.Fatalf("top-left pixel is wrong: %v", img.At(0, 0))
	}
}

// An SVG link is returned untouched: the app renders it as a vector.
func TestFaviconSVG(t *testing.T) {
	svg := []byte(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><rect width="8" height="8" fill="#f00"/></svg>`)
	host, port, client := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			w.Write([]byte(`<link rel="icon" type="image/svg+xml" href="/logo.svg">`))
		case "/logo.svg":
			w.Header().Set("Content-Type", "image/svg+xml")
			w.Write(svg)
		default:
			http.NotFound(w, r)
		}
	})
	res := lookup(t, client, host, port)
	if res.ContentType != "image/svg+xml" {
		t.Fatalf("want svg, got %+v", res)
	}
	if got := decode(t, res); !bytes.Equal(got, svg) {
		t.Fatal("svg bytes changed")
	}
}

// A site that hangs is cut off, not waited on.
func TestFaviconTimeout(t *testing.T) {
	host, port, _ := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(2 * time.Second)
		w.Write([]byte("late"))
	})
	client := &http.Client{Timeout: 100 * time.Millisecond}
	start := time.Now()
	_, err := fetchFavicon(context.Background(), client, host, []int{port}, "http")
	if err == nil {
		t.Fatal("want a timeout error")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("timeout not applied: %s", elapsed)
	}
}

// An icon over the 1 MB cap is refused instead of filling memory.
func TestFaviconSizeCap(t *testing.T) {
	host, port, client := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		chunk := make([]byte, 64<<10)
		copy(chunk, testPNG)
		for i := 0; i < 20; i++ {
			if _, err := w.Write(chunk); err != nil {
				return
			}
		}
	})
	_, err := fetchFavicon(context.Background(), client, host, []int{port}, "http")
	if err == nil {
		t.Fatal("want a size-cap error")
	}
}

// The page itself is capped too: a giant HTML body is a broken site, not an
// icon source.
func TestFaviconHTMLCap(t *testing.T) {
	host, port, client := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/favicon.ico" {
			writePNG(w, testPNG)
			return
		}
		w.Write(bytes.Repeat([]byte("a"), int(faviconHTMLMax)+1))
	})
	res := lookup(t, client, host, port)
	if res.Source != "ico" {
		t.Fatalf("want the ico fallback after a huge page, got %+v", res)
	}
}

// An HTML error page posing as an icon must not become a service icon.
func TestFaviconRejectsHTMLIcon(t *testing.T) {
	host, port, client := serveFavicon(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Write([]byte("<html><body>not found</body></html>"))
	})
	if _, err := fetchFavicon(context.Background(), client, host, []int{port}, "http"); err == nil {
		t.Fatal("want an error for an HTML body")
	}
}

func TestIconScoring(t *testing.T) {
	cases := []struct {
		rel, typ, sizes, target string
		wantAbove               string
	}{
		{"icon", "image/svg+xml", "", "/a.svg", "icon"},
		{"icon", "image/png", "512x512", "/a.png", "icon"},
		{"icon", "image/png", "16x16", "/a.png", ""},
		{"mask-icon", "image/svg+xml", "", "/a.svg", ""},
	}
	for _, c := range cases {
		got := iconScore(c.rel, c.typ, c.sizes, c.target)
		if c.wantAbove == "icon" && got < 80 {
			t.Fatalf("%s %s %s: score %d is too low", c.rel, c.typ, c.sizes, got)
		}
	}
	if iconScore("icon", "image/svg+xml", "", "/a.svg") <= iconScore("icon", "image/png", "16x16", "/a.png") {
		t.Fatal("svg must outrank a 16 px png")
	}
	if iconScore("mask-icon", "image/svg+xml", "", "/a.svg") >= iconScore("icon", "image/png", "512x512", "/a.png") {
		t.Fatal("mask-icon must rank below a real png")
	}
}

// makeICO builds a 32 bpp ICO whose single entry is a BMP DIB with an AND mask.
func makeICO(t *testing.T, w, h int, pixel func(x, y int) color.RGBA) []byte {
	t.Helper()
	stride := ((w*32 + 31) / 32) * 4
	maskStride := ((w + 31) / 32) * 4
	dib := make([]byte, 40+stride*h+maskStride*h)
	binary.LittleEndian.PutUint32(dib[0:4], 40)
	binary.LittleEndian.PutUint32(dib[4:8], uint32(w))
	binary.LittleEndian.PutUint32(dib[8:12], uint32(h*2))
	binary.LittleEndian.PutUint16(dib[12:14], 1)
	binary.LittleEndian.PutUint16(dib[14:16], 32)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			c := pixel(x, y)
			at := 40 + (h-1-y)*stride + 4*x
			dib[at], dib[at+1], dib[at+2], dib[at+3] = c.B, c.G, c.R, c.A
		}
	}
	// Everything opaque in the mask (bit 0), matching 32 bpp alpha.
	out := make([]byte, 6+16)
	binary.LittleEndian.PutUint16(out[2:4], 1)
	binary.LittleEndian.PutUint16(out[4:6], 1)
	out[6] = byte(w)
	out[7] = byte(h)
	binary.LittleEndian.PutUint16(out[10:12], 1)
	binary.LittleEndian.PutUint16(out[12:14], 32)
	binary.LittleEndian.PutUint32(out[14:18], uint32(len(dib)))
	binary.LittleEndian.PutUint32(out[18:22], 22)
	return append(out, dib...)
}
