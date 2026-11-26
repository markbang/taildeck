package tsbridge

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/html"
)

const (
	faviconHTMLMax  = 512 << 10
	faviconIconMax  = 1 << 20
	faviconManifest = 512 << 10
	// One HTTP request, redirects included.
	faviconRequestTimeout = 5 * time.Second
	// The whole lookup: page, manifest and a few icon candidates.
	faviconLookupTimeout = 8 * time.Second
	faviconAgent         = "Mozilla/5.0 (Taildeck favicon)"
)

// FaviconResult is what the UI draws and caches. Bytes are base64 so the C ABI
// stays one JSON string. ICO arrives as PNG; SVG passes through for the app's
// vector decoder.
type FaviconResult struct {
	URL          string `json:"url"`
	ContentType  string `json:"contentType"`
	Bytes        string `json:"bytes"`
	ETag         string `json:"etag,omitempty"`
	LastModified string `json:"lastModified,omitempty"`
	Source       string `json:"source,omitempty"`
}

// Favicon fetches the site icon of host:port through the tailnet node. Only
// the peer is contacted: no third-party favicon service sees the address.
func Favicon(host, ports, scheme string) (string, error) {
	e, err := current()
	if err != nil {
		return "", err
	}
	host = strings.TrimSpace(host)
	if host == "" {
		return "", errors.New("favicon: host is required")
	}
	scheme = strings.ToLower(strings.TrimSpace(scheme))
	if scheme != "https" {
		scheme = "http"
	}
	list := parsePorts(ports)
	if len(list) == 0 {
		if scheme == "https" {
			list = []int{443}
		} else {
			list = []int{80}
		}
	}
	client := &http.Client{
		Timeout: faviconRequestTimeout,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
				return e.dialAny(ctx, addr)
			},
			// The gateway skips verification too: peers use self-signed certs.
			TLSClientConfig:     &tls.Config{InsecureSkipVerify: true},
			ForceAttemptHTTP2:   true,
			MaxIdleConnsPerHost: 2,
			IdleConnTimeout:     30 * time.Second,
		},
	}
	res, err := fetchFavicon(context.Background(), client, host, list, scheme)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(res)
	return string(b), err
}

type iconRef struct {
	url   string
	kind  string
	score int
}

// fetchFavicon tries the ports in order; the first one that yields an icon
// wins. Discovery and icon fetch share one overall deadline.
func fetchFavicon(ctx context.Context, client *http.Client, host string, ports []int, scheme string) (FaviconResult, error) {
	ctx, cancel := context.WithTimeout(ctx, faviconLookupTimeout)
	defer cancel()
	var lastErr error
	for _, port := range ports {
		base := &url.URL{Scheme: scheme, Host: net.JoinHostPort(host, strconv.Itoa(port)), Path: "/"}
		res, err := discover(ctx, client, base)
		if err == nil {
			return res, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = errors.New("favicon: no port to try")
	}
	return FaviconResult{}, lastErr
}

// discover reads the page, ranks the icons it advertises and falls back to
// /favicon.ico. A page that does not load still gets the fallback: many
// services answer icons but no HTML.
func discover(ctx context.Context, client *http.Client, base *url.URL) (FaviconResult, error) {
	var refs []iconRef
	if page, err := getLimited(ctx, client, base.String(), faviconHTMLMax); err == nil {
		refs = append(refs, parseIconLinks(page.body, page.url)...)
		if manifest := manifestHref(page.body, page.url); manifest != "" {
			refs = append(refs, manifestIcons(ctx, client, manifest)...)
		}
	}
	sort.SliceStable(refs, func(i, j int) bool { return refs[i].score > refs[j].score })

	var lastErr error
	for _, ref := range refs {
		res, err := fetchIcon(ctx, client, ref)
		if err == nil {
			return res, nil
		}
		lastErr = err
	}
	fallback := base.ResolveReference(&url.URL{Path: "/favicon.ico"})
	res, err := fetchIcon(ctx, client, iconRef{url: fallback.String(), kind: "ico"})
	if err == nil {
		return res, nil
	}
	if lastErr != nil {
		return FaviconResult{}, lastErr
	}
	return FaviconResult{}, err
}

type httpBody struct {
	body []byte
	url  *url.URL
}

func getLimited(ctx context.Context, client *http.Client, target string, limit int64) (*httpBody, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", faviconAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/json,image/*;q=0.8,*/*;q=0.5")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("GET %s: %s", target, resp.Status)
	}
	body, err := readLimited(resp.Body, limit)
	if err != nil {
		return nil, err
	}
	final := resp.Request.URL
	if final == nil {
		final, _ = url.Parse(target)
	}
	return &httpBody{body: body, url: final}, nil
}

var errTooLarge = errors.New("response is larger than the limit")

func readLimited(r io.Reader, limit int64) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, errTooLarge
	}
	return body, nil
}

// parseIconLinks ranks <link rel="icon">, apple-touch-icon and mask-icon tags.
// Scores sit above the manifest range so a page's own tags always win.
func parseIconLinks(doc []byte, base *url.URL) []iconRef {
	var refs []iconRef
	z := html.NewTokenizer(bytes.NewReader(doc))
	for {
		switch z.Next() {
		case html.ErrorToken:
			return refs
		case html.StartTagToken, html.SelfClosingTagToken:
			name, hasAttr := z.TagName()
			if string(name) != "link" || !hasAttr {
				continue
			}
			rel, href, sizes, typ := "", "", "", ""
			for {
				key, value, more := z.TagAttr()
				switch string(key) {
				case "rel":
					rel = strings.ToLower(strings.TrimSpace(string(value)))
				case "href":
					href = strings.TrimSpace(string(value))
				case "sizes":
					sizes = strings.ToLower(strings.TrimSpace(string(value)))
				case "type":
					typ = strings.ToLower(strings.TrimSpace(string(value)))
				}
				if !more {
					break
				}
			}
			if href == "" || !isIconRel(rel) {
				continue
			}
			if resolved := resolveURL(base, href); resolved != "" {
				refs = append(refs, iconRef{url: resolved, kind: "link", score: 200 + iconScore(rel, typ, sizes, resolved)})
			}
		}
	}
}

func isIconRel(rel string) bool {
	for _, field := range strings.Fields(rel) {
		switch field {
		case "icon", "shortcut", "apple-touch-icon", "apple-touch-icon-precomposed", "mask-icon":
			return true
		}
	}
	return false
}

// iconScore ranks one tag: an SVG or a large PNG beats a 16 px ico.
func iconScore(rel, typ, sizes, target string) int {
	path := strings.ToLower(target)
	svg := strings.Contains(typ, "svg") || strings.HasSuffix(path, ".svg")
	switch {
	case svg && strings.Contains(rel, "mask-icon"):
		return 30
	case svg:
		return 90
	case strings.Contains(rel, "apple-touch-icon"):
		return 70
	}
	largest := largestSize(sizes)
	switch {
	case strings.Contains(typ, "png") || strings.HasSuffix(path, ".png"):
		switch {
		case largest >= 512:
			return 88
		case largest >= 192:
			return 86
		case largest >= 128:
			return 84
		case largest >= 64:
			return 82
		case largest >= 32:
			return 60
		case largest > 0:
			return 50
		}
		return 56
	case strings.Contains(typ, "webp") || strings.HasSuffix(path, ".webp"):
		return largest/16 + 55
	case strings.Contains(typ, "ico") || strings.HasSuffix(path, ".ico"):
		if largest >= 64 {
			return 64
		}
		if largest >= 32 {
			return 58
		}
		return 40
	}
	return largest / 16
}

// largestSize reads a sizes attribute ("32x32 192x192"); 0 when unknown.
func largestSize(sizes string) int {
	best := 0
	for _, field := range strings.Fields(sizes) {
		side, _, ok := strings.Cut(field, "x")
		if !ok {
			continue
		}
		if n, err := strconv.Atoi(side); err == nil && n > best {
			best = n
		}
	}
	return best
}

func resolveURL(base *url.URL, href string) string {
	if strings.HasPrefix(href, "data:") {
		return ""
	}
	ref, err := url.Parse(href)
	if err != nil {
		return ""
	}
	if ref.Scheme != "" && ref.Scheme != "http" && ref.Scheme != "https" {
		return ""
	}
	return base.ResolveReference(ref).String()
}

// manifestHref returns the first <link rel="manifest"> target.
func manifestHref(doc []byte, base *url.URL) string {
	z := html.NewTokenizer(bytes.NewReader(doc))
	for {
		switch z.Next() {
		case html.ErrorToken:
			return ""
		case html.StartTagToken, html.SelfClosingTagToken:
			name, hasAttr := z.TagName()
			if string(name) != "link" || !hasAttr {
				continue
			}
			rel, href := "", ""
			for {
				key, value, more := z.TagAttr()
				switch string(key) {
				case "rel":
					rel = strings.ToLower(strings.TrimSpace(string(value)))
				case "href":
					href = strings.TrimSpace(string(value))
				}
				if !more {
					break
				}
			}
			if rel == "manifest" && href != "" {
				return resolveURL(base, href)
			}
		}
	}
}

type manifestIcon struct {
	Src     string `json:"src"`
	Sizes   string `json:"sizes"`
	Type    string `json:"type"`
	Purpose string `json:"purpose"`
}

type webManifest struct {
	Icons []manifestIcon `json:"icons"`
}

// manifestIcons fetches a web app manifest and ranks its icons. Scores sit
// below the page's own link tags and above the bare /favicon.ico fallback.
func manifestIcons(ctx context.Context, client *http.Client, target string) []iconRef {
	body, err := getLimited(ctx, client, target, faviconManifest)
	if err != nil {
		return nil
	}
	var manifest webManifest
	if err := json.Unmarshal(body.body, &manifest); err != nil {
		return nil
	}
	var refs []iconRef
	for _, icon := range manifest.Icons {
		resolved := resolveURL(body.url, strings.TrimSpace(icon.Src))
		if resolved == "" {
			continue
		}
		score := 100
		if purpose := strings.ToLower(icon.Purpose); purpose == "" || strings.Contains(purpose, "any") {
			score += 30
		} else {
			score -= 20
		}
		score += manifestSizeBonus(largestSize(icon.Sizes))
		if strings.Contains(strings.ToLower(icon.Type), "png") {
			score += 5
		}
		refs = append(refs, iconRef{url: resolved, kind: "manifest", score: score})
	}
	return refs
}

// manifestSizeBonus prefers the sizes phones and desktops actually ask for.
func manifestSizeBonus(size int) int {
	switch size {
	case 512:
		return 40
	case 256:
		return 38
	case 192:
		return 36
	case 180:
		return 34
	case 144:
		return 30
	case 128:
		return 28
	case 96:
		return 26
	case 64:
		return 22
	}
	if size > 0 {
		return 10
	}
	return 0
}

// fetchIcon downloads one candidate and normalises it: ICO becomes PNG, and an
// HTML page or an oversized body is rejected so the next candidate can run.
func fetchIcon(ctx context.Context, client *http.Client, ref iconRef) (FaviconResult, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, ref.url, nil)
	if err != nil {
		return FaviconResult{}, err
	}
	req.Header.Set("User-Agent", faviconAgent)
	req.Header.Set("Accept", "image/png,image/svg+xml,image/webp,image/x-icon,image/*;q=0.8,*/*;q=0.5")
	resp, err := client.Do(req)
	if err != nil {
		return FaviconResult{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return FaviconResult{}, fmt.Errorf("GET %s: %s", ref.url, resp.Status)
	}
	body, err := readLimited(resp.Body, faviconIconMax)
	if err != nil {
		return FaviconResult{}, fmt.Errorf("%s: %w", ref.url, err)
	}
	kind, data, err := normalizeIcon(resp.Header.Get("Content-Type"), body)
	if err != nil {
		return FaviconResult{}, fmt.Errorf("%s: %w", ref.url, err)
	}
	return FaviconResult{
		URL:          ref.url,
		ContentType:  kind,
		Bytes:        base64.StdEncoding.EncodeToString(data),
		ETag:         resp.Header.Get("ETag"),
		LastModified: resp.Header.Get("Last-Modified"),
		Source:       ref.kind,
	}, nil
}

// normalizeIcon sniffs the real format instead of trusting the header: servers
// label ico as text/plain and HTML error pages as image/png.
func normalizeIcon(contentType string, body []byte) (string, []byte, error) {
	switch {
	case bytes.HasPrefix(body, []byte("\x89PNG\r\n\x1a\n")):
		return "image/png", body, nil
	case bytes.HasPrefix(body, []byte("\xff\xd8\xff")):
		return "image/jpeg", body, nil
	case bytes.HasPrefix(body, []byte("GIF8")):
		return "image/gif", body, nil
	case len(body) > 12 && string(body[:4]) == "RIFF" && string(body[8:12]) == "WEBP":
		return "image/webp", body, nil
	case bytes.HasPrefix(body, []byte{0, 0, 1, 0}), bytes.HasPrefix(body, []byte{0, 0, 2, 0}):
		converted, err := icoToPNG(body)
		if err != nil {
			return "", nil, err
		}
		return "image/png", converted, nil
	case looksLikeSVG(body):
		return "image/svg+xml", body, nil
	}
	if strings.HasPrefix(strings.ToLower(strings.TrimSpace(contentType)), "text/html") {
		return "", nil, errors.New("server returned an HTML page, not an icon")
	}
	return "", nil, errors.New("response is not a supported image")
}

func looksLikeSVG(body []byte) bool {
	head := body
	if len(head) > 4096 {
		head = head[:4096]
	}
	text := strings.ToLower(strings.TrimSpace(string(head)))
	return strings.HasPrefix(text, "<svg") ||
		(strings.HasPrefix(text, "<?xml") && strings.Contains(text, "<svg"))
}

// icoToPNG expands the largest entry of an ICO into a PNG, so the app has one
// raster path. Entries that are already PNG are passed through.
func icoToPNG(data []byte) ([]byte, error) {
	if len(data) < 6 {
		return nil, errors.New("ico: bad header")
	}
	count := int(binary.LittleEndian.Uint16(data[4:6]))
	if count == 0 || len(data) < 6+16*count {
		return nil, errors.New("ico: no entries")
	}
	bestOff, bestSize, bestArea, bestBits := 0, 0, 0, 0
	for i := 0; i < count; i++ {
		entry := data[6+16*i : 6+16*(i+1)]
		w, h := int(entry[0]), int(entry[1])
		if w == 0 {
			w = 256
		}
		if h == 0 {
			h = 256
		}
		size := int(binary.LittleEndian.Uint32(entry[8:12]))
		off := int(binary.LittleEndian.Uint32(entry[12:16]))
		bits := int(binary.LittleEndian.Uint16(entry[6:8]))
		if size <= 0 || off < 0 || off+size > len(data) {
			continue
		}
		if area := w * h; area > bestArea || (area == bestArea && bits > bestBits) {
			bestOff, bestSize, bestArea, bestBits = off, size, area, bits
		}
	}
	if bestSize == 0 {
		return nil, errors.New("ico: no usable entry")
	}
	entry := data[bestOff : bestOff+bestSize]
	if len(entry) >= 8 && bytes.HasPrefix(entry, []byte("\x89PNG\r\n\x1a\n")) {
		return entry, nil
	}
	img, err := decodeDIB(entry)
	if err != nil {
		return nil, err
	}
	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

// decodeDIB turns a BITMAPINFOHEADER + pixels entry into an image. Height is
// doubled (colour rows plus the AND mask); alpha comes from 32 bpp pixels and
// the mask, so 24 bpp icons keep their cut-out edges.
func decodeDIB(entry []byte) (image.Image, error) {
	if len(entry) < 40 {
		return nil, errors.New("ico: short DIB header")
	}
	headerSize := int(binary.LittleEndian.Uint32(entry[0:4]))
	if headerSize < 40 || headerSize > len(entry) {
		return nil, fmt.Errorf("ico: bad DIB header %d", headerSize)
	}
	w := int(int32(binary.LittleEndian.Uint32(entry[4:8])))
	h := int(int32(binary.LittleEndian.Uint32(entry[8:12]))) / 2
	bpp := int(binary.LittleEndian.Uint16(entry[14:16]))
	compression := binary.LittleEndian.Uint32(entry[16:20])
	if w <= 0 || w > 1024 || h <= 0 || h > 1024 {
		return nil, fmt.Errorf("ico: unsupported size %dx%d", w, h*2)
	}
	if compression != 0 && compression != 3 {
		return nil, fmt.Errorf("ico: unsupported compression %d", compression)
	}
	if bpp != 1 && bpp != 4 && bpp != 8 && bpp != 24 && bpp != 32 {
		return nil, fmt.Errorf("ico: unsupported %d bpp", bpp)
	}

	cursor := headerSize
	var palette []color.RGBA
	if bpp <= 8 {
		used := int(binary.LittleEndian.Uint32(entry[32:36]))
		if used == 0 {
			used = 1 << bpp
		}
		if used > 256 || cursor+4*used > len(entry) {
			return nil, errors.New("ico: bad palette")
		}
		palette = make([]color.RGBA, used)
		for i := 0; i < used; i++ {
			b, g, r := entry[cursor+4*i], entry[cursor+4*i+1], entry[cursor+4*i+2]
			palette[i] = color.RGBA{R: r, G: g, B: b, A: 0xff}
		}
		cursor += 4 * used
	} else if compression == 3 {
		cursor += 12
	}

	stride := ((w*bpp + 31) / 32) * 4
	maskStride := ((w + 31) / 32) * 4
	if cursor+stride*h > len(entry) {
		return nil, errors.New("ico: pixel data is short")
	}
	maskAt := cursor + stride*h

	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		row := entry[cursor+(h-1-y)*stride : cursor+(h-1-y)*stride+stride]
		var maskRow []byte
		if maskAt+maskStride*h <= len(entry) {
			maskRow = entry[maskAt+(h-1-y)*maskStride : maskAt+(h-1-y)*maskStride+maskStride]
		}
		for x := 0; x < w; x++ {
			var c color.RGBA
			switch bpp {
			case 32:
				c = color.RGBA{R: row[4*x+2], G: row[4*x+1], B: row[4*x], A: row[4*x+3]}
			case 24:
				c = color.RGBA{R: row[3*x+2], G: row[3*x+1], B: row[3*x], A: 0xff}
			case 8:
				c = paletteColor(palette, int(row[x]))
			case 4:
				idx := int(row[x/2])
				if x%2 == 0 {
					idx >>= 4
				}
				c = paletteColor(palette, idx&0x0f)
			case 1:
				idx := (int(row[x/8]) >> (7 - uint(x%8))) & 1
				c = paletteColor(palette, idx)
			}
			if bpp < 32 && maskRow != nil && maskRow[x/8]&(1<<uint(7-x%8)) != 0 {
				c.A = 0
			}
			img.SetRGBA(x, y, c)
		}
	}
	return img, nil
}

func paletteColor(palette []color.RGBA, index int) color.RGBA {
	if index >= 0 && index < len(palette) {
		return palette[index]
	}
	return color.RGBA{A: 0xff}
}
