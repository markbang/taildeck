package tsbridge

import (
	"bufio"
	"bytes"
	"os/exec"
	"testing"
)

// fakeConn stands in for the herdr socket: what the test queues in in is
// what the server sends, and out records what the client wrote.
type fakeConn struct {
	in  bytes.Buffer
	out bytes.Buffer
}

func (c *fakeConn) Read(p []byte) (int, error)  { return c.in.Read(p) }
func (c *fakeConn) Write(p []byte) (int, error) { return c.out.Write(p) }
func (c *fakeConn) Close() error                { return nil }

func newHerdrOn(c *fakeConn) *Herdr {
	return &Herdr{conn: c, reader: bufio.NewReader(c)}
}

func TestCallFramesRequestAndReturnsResult(t *testing.T) {
	c := &fakeConn{}
	c.in.WriteString(`{"id":"td1","result":{"panes":[]}}` + "\n")
	got, err := newHerdrOn(c).Call("pane.list", `{"workspace":"w"}`)
	if err != nil {
		t.Fatal(err)
	}
	if got != `{"panes":[]}` {
		t.Fatalf("result = %s", got)
	}
	want := `{"id":"td1","method":"pane.list","params":{"workspace":"w"}}` + "\n"
	if c.out.String() != want {
		t.Fatalf("request = %q, want %q", c.out.String(), want)
	}
}

func TestCallDefaultsParamsToEmptyObject(t *testing.T) {
	c := &fakeConn{}
	c.in.WriteString(`{"id":"td1","result":{}}` + "\n")
	if _, err := newHerdrOn(c).Call("server.info", ""); err != nil {
		t.Fatal(err)
	}
	if want := `"params":{}`; !bytes.Contains(c.out.Bytes(), []byte(want)) {
		t.Fatalf("request %q has no %s", c.out.String(), want)
	}
}

func TestCallIDsIncreasePerRequest(t *testing.T) {
	c := &fakeConn{}
	c.in.WriteString(`{"id":"td1","result":1}` + "\n" + `{"id":"td2","result":2}` + "\n")
	h := newHerdrOn(c)
	a, err := h.Call("a", "")
	if err != nil {
		t.Fatal(err)
	}
	b, err := h.Call("b", "")
	if err != nil {
		t.Fatal(err)
	}
	if a != "1" || b != "2" {
		t.Fatalf("results = %s, %s", a, b)
	}
	if !bytes.Contains(c.out.Bytes(), []byte(`"id":"td2"`)) {
		t.Fatalf("second request not td2: %q", c.out.String())
	}
}

func TestCallSkipsResponsesForOtherRequests(t *testing.T) {
	c := &fakeConn{}
	c.in.WriteString(`{"id":"td0","result":{"stale":true}}` + "\n" + `{"id":"td1","result":{"ok":1}}` + "\n")
	got, err := newHerdrOn(c).Call("pane.get", "")
	if err != nil {
		t.Fatal(err)
	}
	if got != `{"ok":1}` {
		t.Fatalf("result = %s", got)
	}
}

func TestCallReportsServerErrorWithCode(t *testing.T) {
	c := &fakeConn{}
	c.in.WriteString(`{"id":"td1","error":{"code":"pane_not_found","message":"no such pane"}}` + "\n")
	_, err := newHerdrOn(c).Call("pane.get", `{"pane_id":"p9"}`)
	if err == nil || err.Error() != "pane_not_found: no such pane" {
		t.Fatalf("err = %v", err)
	}
}

func TestShellQuoteSurvivesTheShell(t *testing.T) {
	for _, s := range []string{"plain", "it's", "$HOME `x` \"y\"", "a\\b", ""} {
		out, err := exec.Command("sh", "-c", "printf %s "+shellQuote(s)).Output()
		if err != nil {
			t.Fatalf("sh rejected %q: %v", s, err)
		}
		if string(out) != s {
			t.Fatalf("shellQuote(%q) came back as %q", s, out)
		}
	}
}
