package tsbridge

import (
	"bytes"
	"encoding/base64"
	"reflect"
	"strings"
	"testing"
)

func TestImageStoreReturnsStoredBytes(t *testing.T) {
	h := &Herdr{images: newImageStore()}
	raw := []byte("png-bytes")
	ref := h.images.put(base64.StdEncoding.EncodeToString(raw))
	if !strings.HasPrefix(ref, "img:") {
		t.Fatalf("ref = %q", ref)
	}
	got, err := h.Image(ref)
	if err != nil || !bytes.Equal(got, raw) {
		t.Fatalf("Image = %q, %v", got, err)
	}
	if again := h.images.put(base64.StdEncoding.EncodeToString(raw)); again != ref {
		t.Fatalf("same image got ref %q then %q", ref, again)
	}
}

func TestImageStoreRejectsBadBase64(t *testing.T) {
	s := newImageStore()
	if ref := s.put("!!! not base64 !!!"); ref != "" {
		t.Fatalf("ref = %q, want empty", ref)
	}
}

func TestImageStoreEvictsOldestAboveCap(t *testing.T) {
	h := &Herdr{images: newImageStore()}
	var refs []string
	n := imageStoreCap/(4<<20) + 2
	for i := 0; i < n; i++ {
		data := bytes.Repeat([]byte{byte(i + 1)}, 4<<20)
		refs = append(refs, h.images.put(base64.StdEncoding.EncodeToString(data)))
	}
	if _, err := h.Image(refs[0]); err == nil {
		t.Fatal("oldest image is still cached above the cap")
	}
	if _, err := h.Image(refs[n-1]); err != nil {
		t.Fatalf("newest image was evicted: %v", err)
	}
}

func TestKeptFlagsRestartKeepsSettingsOnly(t *testing.T) {
	cases := []struct {
		kind string
		argv []string
		want []string
	}{
		{
			"claude",
			[]string{"/usr/local/bin/claude", "--model", "opus", "--permission-mode", "plan", "--resume", "1234abcd-0000-4000-8000-000000000001", "-c", "--verbose", "fix it"},
			[]string{"--model", "opus", "--permission-mode", "plan", "--verbose"},
		},
		{
			"claude",
			[]string{"claude", "--add-dir", "/a", "/b", "--model=sonnet", "--print", "hi"},
			[]string{"--add-dir", "/a", "/b", "--model=sonnet"},
		},
		{
			"claude",
			[]string{"claude", "--model", "opus", "--", "--verbose"},
			[]string{"--model", "opus"},
		},
		{
			"codex",
			[]string{"codex", "resume", "0f9c-id", "-m", "gpt-5", "--yolo", "--last", "hello"},
			[]string{"-m", "gpt-5", "--yolo"},
		},
		{
			"codex",
			[]string{"codex", "-C", "/proj", "fork", "abc"},
			[]string{"-C", "/proj"},
		},
		{
			"claude",
			[]string{"bash", "-c", "echo"},
			nil,
		},
	}
	for _, c := range cases {
		got := keptFlags(c.kind, c.argv)
		if !reflect.DeepEqual(got, c.want) {
			t.Fatalf("keptFlags(%s, %v) = %v, want %v", c.kind, c.argv, got, c.want)
		}
	}
}

func TestParsePiModelTable(t *testing.T) {
	out := `Models:
provider  model   context  max-out  thinking  images
anthropic claude-x 200K    64K      yes       yes
openai    gpt-y    128K    16K      no        no
too short row`
	got := parsePiModelTable(out)
	want := []modelChoice{
		{ID: "anthropic/claude-x", Name: "claude-x · anthropic"},
		{ID: "openai/gpt-y", Name: "gpt-y · openai", Efforts: []string{"off"}},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parsePiModelTable = %+v, want %+v", got, want)
	}
}

func TestParsePiModelTableWithoutHeaderIsEmpty(t *testing.T) {
	if got := parsePiModelTable("anthropic claude-x 200K 64K yes yes"); len(got) != 0 {
		t.Fatalf("rows before the header were parsed: %+v", got)
	}
}

func TestTranscriptMoreWidensUntilCap(t *testing.T) {
	h := &Herdr{transcripts: map[string]*transcript{}, windows: map[string]int{}}
	if !h.TranscriptMore("p1") {
		t.Fatal("unknown pane should be widened")
	}
	h.transcripts["p1"] = &transcript{}
	if h.TranscriptMore("p1") {
		t.Fatal("complete transcript should not be widened")
	}
	h.transcripts["p1"] = &transcript{truncated: true}
	if !h.TranscriptMore("p1") {
		t.Fatal("truncated transcript should be widened")
	}
	h.windows["p2"] = 64 << 20
	h.transcripts["p2"] = &transcript{truncated: true}
	if h.TranscriptMore("p2") {
		t.Fatal("widened past 64 MiB")
	}
}
