package tsbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeNode answers herdr calls from memory and runs node scripts with sh in
// a temporary home, so session lookups read real files and real processes.
type fakeNode struct {
	t       *testing.T
	home    string
	cwd     string
	agent   string
	session map[string]string // herdr's agent_session, when it has one
	pid     int
	reports []string
}

func newFakeNode(t *testing.T, agent string) *fakeNode {
	t.Helper()
	agentProc := exec.Command("sleep", "300")
	if err := agentProc.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { agentProc.Process.Kill(); agentProc.Wait() })
	return &fakeNode{t: t, home: t.TempDir(), cwd: "/srv/app", agent: agent, pid: agentProc.Process.Pid}
}

func (n *fakeNode) herdr() *Herdr {
	return &Herdr{
		transcripts: map[string]*transcript{}, windows: map[string]int{},
		images: newImageStore(), sessions: map[string]*paneSession{},
		callHook: n.call, execHook: n.exec,
	}
}

func (n *fakeNode) call(method, params string) (string, error) {
	switch method {
	case "agent.get":
		agent := map[string]any{"agent": n.agent, "cwd": n.cwd}
		if n.session != nil {
			agent["agent_session"] = n.session
		}
		b, _ := json.Marshal(map[string]any{"agent": agent})
		return string(b), nil
	case "pane.process_info":
		return fmt.Sprintf(`{"process_info":{"foreground_processes":[{"pid":%d,"argv":[%q]}]}}`, n.pid, n.agent), nil
	case "pane.report_agent_session":
		n.reports = append(n.reports, params)
		return "{}", nil
	case "agent.read":
		return `{"read":{"text":"welcome screen"}}`, nil
	}
	return "", fmt.Errorf("unexpected call %s", method)
}

func (n *fakeNode) exec(script string) ([]byte, error) {
	cmd := exec.Command("sh", "-s")
	cmd.Stdin = strings.NewReader(script)
	cmd.Env = []string{"HOME=" + n.home, "PATH=" + os.Getenv("PATH")}
	out, err := cmd.Output()
	var exit *exec.ExitError
	if errors.As(err, &exit) {
		// Like an SSH session, a failing script still returns its output.
		err = nil
	}
	return out, err
}

// write puts a session file under the temporary home, last written at.
func (n *fakeNode) write(rel string, at time.Time, lines ...string) {
	n.t.Helper()
	file := filepath.Join(n.home, rel)
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		n.t.Fatal(err)
	}
	if err := os.WriteFile(file, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		n.t.Fatal(err)
	}
	if err := os.Chtimes(file, at, at); err != nil {
		n.t.Fatal(err)
	}
}

type shownTranscript struct {
	Mode     string `json:"mode"`
	Matched  string `json:"matched"`
	Messages []struct {
		Role string `json:"role"`
		Text string `json:"text"`
	} `json:"messages"`
	Settings struct {
		Model  string `json:"model"`
		Effort string `json:"effort"`
	} `json:"settings"`
}

func readShown(t *testing.T, h *Herdr) shownTranscript {
	t.Helper()
	raw, err := h.Transcript("w1:p2")
	if err != nil {
		t.Fatal(err)
	}
	var shown shownTranscript
	if err := json.Unmarshal([]byte(raw), &shown); err != nil {
		t.Fatal(err)
	}
	return shown
}

func (s shownTranscript) texts() []string {
	var out []string
	for _, m := range s.Messages {
		out = append(out, m.Role+": "+m.Text)
	}
	return out
}

const piDir = ".pi/agent/sessions/--srv-app--/"

func piSession(question, answer, model, effort string) []string {
	return []string{
		`{"type":"session","id":"s0"}`,
		`{"type":"model_change","id":"m0","parentId":null,"provider":"anthropic","modelId":"` + model + `"}`,
		`{"type":"thinking_level_change","id":"e0","parentId":"m0","thinkingLevel":"` + effort + `"}`,
		`{"type":"message","id":"u1","parentId":"e0","message":{"role":"user","content":[{"type":"text","text":"` + question + `"}]}}`,
		`{"type":"message","id":"a1","parentId":"u1","message":{"role":"assistant","model":"` + model + `","content":[{"type":"text","text":"` + answer + `"}]}}`,
	}
}

func TestNewPiSessionStartsEmpty(t *testing.T) {
	node := newFakeNode(t, "pi")
	h := node.herdr()
	node.write(piDir+"2026-11-19T10-00-00-000Z_old.jsonl", time.Now().Add(-time.Hour),
		piSession("old question", "old answer", "claude-opus-4-1", "high")...)

	shown := readShown(t, h)
	if shown.Mode != "transcript" || len(shown.Messages) != 0 || shown.Settings.Model != "" {
		t.Fatalf("a new pi agent shows %+v, want an empty transcript", shown)
	}

	node.write(piDir+"2026-11-20T10-00-00-000Z_new.jsonl", time.Now(),
		piSession("new question", "new answer", "claude-sonnet-4-5", "low")...)
	shown = readShown(t, h)
	if got := shown.texts(); len(got) != 2 || got[0] != "user: new question" {
		t.Fatalf("after its first reply the agent shows %v", got)
	}
	if shown.Settings.Model != "anthropic/claude-sonnet-4-5" || shown.Settings.Effort != "low" {
		t.Fatalf("settings %+v are not the new session's", shown.Settings)
	}
}

const claudeDir = ".claude/projects/-srv-app/"

func claudeLines(question, answer, model string) []string {
	return []string{
		`{"type":"user","uuid":"u1","message":{"role":"user","content":"` + question + `"}}`,
		`{"type":"assistant","uuid":"a1","message":{"role":"assistant","model":"` + model + `","content":[{"type":"text","text":"` + answer + `"}]}}`,
	}
}

func TestNamedSessionWithoutFileStartsEmpty(t *testing.T) {
	node := newFakeNode(t, "claude")
	h := node.herdr()
	node.write(claudeDir+"11111111-old.jsonl", time.Now(), claudeLines("old question", "old answer", "claude-opus-4-1-20250805")...)
	node.session = map[string]string{"agent": "claude", "kind": "id", "value": "22222222-new"}

	shown := readShown(t, h)
	if len(shown.Messages) != 0 || shown.Matched != "new" {
		t.Fatalf("herdr's new session shows %v (%s), want empty", shown.texts(), shown.Matched)
	}
}

func TestClaudeSessionFollowsItsProcess(t *testing.T) {
	node := newFakeNode(t, "claude")
	h := node.herdr()
	node.write(claudeDir+"11111111-old.jsonl", time.Now(), claudeLines("old question", "old answer", "claude-opus-4-1-20250805")...)
	pidFile := fmt.Sprintf(".claude/sessions/%d.json", node.pid)
	node.write(pidFile, time.Now(), `{"sessionId":"22222222-new","cwd":"/srv/app"}`)

	shown := readShown(t, h)
	if len(shown.Messages) != 0 {
		t.Fatalf("a claude session with no file yet shows %v", shown.texts())
	}
	if len(node.reports) != 1 || !strings.Contains(node.reports[0], `"agent_session_id":"22222222-new"`) {
		t.Fatalf("reports %v, want the new session id", node.reports)
	}

	node.write(claudeDir+"22222222-new.jsonl", time.Now(), claudeLines("new question", "new answer", "claude-sonnet-4-5-20250929")...)
	h.sessions["w1:p2"].checked = time.Now().Add(-time.Minute)
	shown = readShown(t, h)
	if got := shown.texts(); len(got) != 2 || got[0] != "user: new question" || shown.Settings.Model != "claude-sonnet-4-5-20250929" {
		t.Fatalf("the session shows %v with %+v", got, shown.Settings)
	}

	// /clear starts another session in the same process.
	node.write(pidFile, time.Now(), `{"sessionId":"33333333-clear","cwd":"/srv/app"}`)
	h.sessions["w1:p2"].checked = time.Now().Add(-time.Minute)
	shown = readShown(t, h)
	if len(shown.Messages) != 0 {
		t.Fatalf("after /clear the pane still shows %v", shown.texts())
	}
	if len(node.reports) != 3 {
		t.Fatalf("reports %v, want one per session", node.reports)
	}
}

func TestNewestSince(t *testing.T) {
	out := "now 1000\netime 01:40\netime 00:05\nfile 990 /s/new.jsonl\nfile 899 /s/old.jsonl\n"
	if file, started := newestSince(out); file != "/s/new.jsonl" || !started {
		t.Fatalf("got %q %v", file, started)
	}
	out = "now 1000\netime 00:05\nfile 900 /s/old.jsonl\n"
	if file, started := newestSince(out); file != "" || !started {
		t.Fatalf("an older file matched: %q %v", file, started)
	}
	if file, started := newestSince("now 1000\nfile 999 /s/x.jsonl\n"); file != "" || started {
		t.Fatalf("matched without a start time: %q %v", file, started)
	}
	for in, want := range map[string]int64{"00:07": 7, "01:02:03": 3723, "2-00:00:01": 172801} {
		if got, ok := parseEtime(in); !ok || got != want {
			t.Fatalf("parseEtime(%q) = %d, want %d", in, got, want)
		}
	}
}
