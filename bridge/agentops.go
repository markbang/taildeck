package tsbridge

import (
	"bytes"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path"
	"regexp"
	"strings"
	"sync"
	"time"
)

// ---- images from transcripts, held in memory until the app asks for them ----

const imageStoreCap = 48 << 20

type imageStore struct {
	mu    sync.Mutex
	data  map[string][]byte
	order []string
	size  int
}

func newImageStore() *imageStore { return &imageStore{data: map[string][]byte{}} }

// put stores base64 image data and returns its "img:<id>" reference.
func (s *imageStore) put(b64 string) string {
	sum := sha1.Sum([]byte(b64))
	id := hex.EncodeToString(sum[:10])
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.data[id]; ok {
		return "img:" + id
	}
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(b) == 0 || len(b) > imageStoreCap/4 {
		return ""
	}
	s.data[id] = b
	s.order = append(s.order, id)
	s.size += len(b)
	for s.size > imageStoreCap && len(s.order) > 1 {
		old := s.order[0]
		s.order = s.order[1:]
		s.size -= len(s.data[old])
		delete(s.data, old)
	}
	return "img:" + id
}

// Image returns an image a transcript referenced as "img:<id>".
func (h *Herdr) Image(ref string) ([]byte, error) {
	id := strings.TrimPrefix(ref, "img:")
	h.images.mu.Lock()
	defer h.images.mu.Unlock()
	if b, ok := h.images.data[id]; ok {
		return b, nil
	}
	return nil, errors.New("image is no longer cached; reopen the chat")
}

// ---- which session a pane's agent is in ----

type paneSession struct {
	file    string
	checked time.Time
}

var sessionIDPattern = regexp.MustCompile(`^[A-Za-z0-9-]{8,64}$`)

// identify finds the transcript of the agent in a pane from its processes:
// Claude Code writes sessions/<pid>.json, Codex keeps its rollout file open.
// Exact finds are reported back to herdr so every client sees them.
func (h *Herdr) identify(pane, kind, cwd string) string {
	if kind != "claude" && kind != "codex" {
		return ""
	}
	h.smu.Lock()
	if s := h.sessions[pane]; s != nil && (s.file != "" || time.Since(s.checked) < 15*time.Second) {
		h.smu.Unlock()
		return s.file
	}
	h.sessions[pane] = &paneSession{checked: time.Now()}
	h.smu.Unlock()

	raw, err := h.Call("pane.process_info", fmt.Sprintf(`{"pane_id":%q}`, pane))
	if err != nil {
		return ""
	}
	var info struct {
		ProcessInfo struct {
			Foreground []struct {
				PID  int      `json:"pid"`
				Argv []string `json:"argv"`
			} `json:"foreground_processes"`
		} `json:"process_info"`
	}
	json.Unmarshal([]byte(raw), &info)
	var pids []string
	resume := ""
	for _, p := range info.ProcessInfo.Foreground {
		pids = append(pids, fmt.Sprint(p.PID))
		for i, a := range p.Argv {
			if (a == "resume" || a == "--resume" || a == "-r") && i+1 < len(p.Argv) && sessionIDPattern.MatchString(p.Argv[i+1]) {
				resume = p.Argv[i+1]
			}
		}
	}
	if len(pids) == 0 {
		return ""
	}
	file, id := "", ""
	switch kind {
	case "claude":
		file, id = h.claudeSession(pids, cwd)
	case "codex":
		file = h.codexSession(pids, resume)
	}
	if file == "" {
		return ""
	}
	h.smu.Lock()
	h.sessions[pane] = &paneSession{file: file, checked: time.Now()}
	h.smu.Unlock()
	report := map[string]any{"pane_id": pane, "source": "herdr:" + kind, "agent": kind, "seq": time.Now().UnixMicro()}
	if id != "" {
		report["agent_session_id"] = id
	} else {
		report["agent_session_path"] = file
	}
	if b, err := json.Marshal(report); err == nil {
		h.Call("pane.report_agent_session", string(b))
	}
	return file
}

// claudeSession reads sessions/<pid>.json for the pane's processes; failing
// that, it takes the only live session whose directory is the pane's.
func (h *Herdr) claudeSession(pids []string, cwd string) (file, id string) {
	out, err := h.exec(fmt.Sprintf(`pids=%s
dirs="${CLAUDE_CONFIG_DIR:-$HOME/.claude}
$HOME/.claude"
for p in $pids; do
  e=$(tr '\0' '\n' < /proc/$p/environ 2>/dev/null | sed -n 's/^CLAUDE_CONFIG_DIR=//p')
  [ -n "$e" ] && dirs="$e
$dirs"
done
echo "$dirs" | while IFS= read -r d; do
  [ -d "$d/sessions" ] || continue
  for p in $pids; do [ -f "$d/sessions/$p.json" ] && printf 'EXACT\t%%s\t%%s\n' "$d" "$(tr -d '\n' < "$d/sessions/$p.json")"; done
  for f in "$d"/sessions/*.json; do
    [ -f "$f" ] || continue
    p=$(basename "$f" .json); kill -0 "$p" 2>/dev/null || continue
    printf 'LIVE\t%%s\t%%s\n' "$d" "$(tr -d '\n' < "$f")"
  done
done`, shellQuote(strings.Join(pids, " "))), 10*time.Second)
	if err != nil {
		return "", ""
	}
	type entry struct{ dir, id, cwd string }
	var exact, live []entry
	for _, line := range strings.Split(string(out), "\n") {
		parts := strings.SplitN(line, "\t", 3)
		if len(parts) != 3 {
			continue
		}
		var s struct {
			SessionID string `json:"sessionId"`
			Cwd       string `json:"cwd"`
		}
		if json.Unmarshal([]byte(parts[2]), &s) != nil || !sessionIDPattern.MatchString(s.SessionID) {
			continue
		}
		e := entry{parts[1], s.SessionID, strings.TrimRight(s.Cwd, "/")}
		if parts[0] == "EXACT" {
			exact = append(exact, e)
		} else {
			live = append(live, e)
		}
	}
	pick := entry{}
	if len(exact) > 0 {
		pick = exact[0]
	} else {
		var same []entry
		seen := map[string]bool{}
		for _, e := range live {
			if e.cwd == strings.TrimRight(cwd, "/") && !seen[e.id] {
				seen[e.id] = true
				same = append(same, e)
			}
		}
		if len(same) != 1 {
			return "", ""
		}
		pick = same[0]
	}
	out, err = h.exec(fmt.Sprintf(`for f in %s/projects/*/%s.jsonl; do [ -f "$f" ] && echo "$f" && break; done`, shellQuote(pick.dir), pick.id), 10*time.Second)
	if err != nil {
		return "", ""
	}
	if f := strings.TrimSpace(string(out)); f != "" {
		return strings.Split(f, "\n")[0], pick.id
	}
	return "", ""
}

// codexSession finds the rollout file a Codex process has open, or the one
// it was resumed from.
func (h *Herdr) codexSession(pids []string, resume string) string {
	byID := ""
	if resume != "" {
		byID = fmt.Sprintf(`find "${CODEX_HOME:-$HOME/.codex}/sessions" -name '*%s.jsonl' 2>/dev/null | head -1`, resume)
	}
	out, err := h.exec(fmt.Sprintf(`for p in %s; do
  f=$(ls -l /proc/$p/fd 2>/dev/null | grep -o '/[^ ]*/rollout-[^ ]*\.jsonl' | head -1)
  [ -z "$f" ] && f=$(lsof -p $p -Fn 2>/dev/null | sed -n 's/^n//p' | grep 'rollout-.*\.jsonl$' | head -1)
  [ -n "$f" ] && { echo "$f"; exit 0; }
done
%s`, strings.Join(pids, " "), byID), 10*time.Second)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(strings.Split(string(out), "\n")[0])
}

// ---- history ----

// TranscriptMore widens how far back a pane's transcript is read; the next
// Transcript call re-reads it.
func (h *Herdr) TranscriptMore(target string) bool {
	h.tmu.Lock()
	defer h.tmu.Unlock()
	w := h.windows[target]
	if w == 0 {
		w = initialWindow
	}
	if t := h.transcripts[target]; t != nil && !t.truncated {
		return false
	}
	if w >= 64<<20 {
		return false
	}
	h.windows[target] = w * 4
	delete(h.transcripts, target)
	return true
}

// Subagent returns the transcript of a Claude subagent started by tool call
// callID in the pane's session, with its type, description and whether it finished.
func (h *Herdr) Subagent(target, callID string) (string, error) {
	h.tmu.Lock()
	t := h.transcripts[target]
	h.tmu.Unlock()
	if t == nil || t.kind != "claude" {
		return "", errors.New("subagent_not_found: this agent keeps no subagent transcripts")
	}
	if !safeID.MatchString(callID) {
		return "", errors.New("bad tool call id")
	}
	dir := strings.TrimSuffix(t.file, ".jsonl") + "/subagents"
	out, err := h.exec(fmt.Sprintf(`d=%s; id=%s
for m in "$d"/*.meta.json; do
  [ -f "$m" ] || continue
  grep -q "\"toolUseId\" *: *\"$id\"" "$m" || continue
  f="${m%%.meta.json}.jsonl"; [ -f "$f" ] || exit 4
  tr -d '\n' < "$m"; echo
  tail -c %d "$f"
  exit 0
done
exit 4`, shellQuote(dir), shellQuote(callID), 4<<20), 30*time.Second)
	if err != nil {
		return "", err
	}
	meta, data, _ := bytes.Cut(out, []byte("\n"))
	if len(meta) == 0 {
		return "", errors.New("subagent_not_found: this Claude Code version keeps no transcript for the subagent")
	}
	var m struct {
		AgentType   string `json:"agentType"`
		Description string `json:"description"`
		Finished    bool   `json:"finished"`
	}
	json.Unmarshal(meta, &m)
	sub := &transcript{kind: "claude", tools: map[string]int{}, piByID: map[string]int{}, images: h.images, sidechain: true}
	sub.feed(append(data, '\n'))
	rendered, err := sub.render("session")
	if err != nil {
		return "", err
	}
	var obj map[string]any
	json.Unmarshal([]byte(rendered), &obj)
	obj["agent"] = map[string]any{"type": m.AgentType, "description": m.Description, "finished": m.Finished}
	b, err := json.Marshal(obj)
	return string(b), err
}

// ---- files on the node ----

// execIn runs cmd under sh with data on stdin.
func (h *Herdr) execIn(cmd string, data io.Reader, timeout time.Duration) ([]byte, error) {
	s, err := h.client.NewSession()
	if err != nil {
		return nil, err
	}
	defer s.Close()
	s.Stdin = data
	var out, stderr bytes.Buffer
	s.Stdout, s.Stderr = &out, &stderr
	if err := s.Start("sh -c " + shellQuote(cmd)); err != nil {
		return nil, err
	}
	done := make(chan error, 1)
	go func() { done <- s.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			return nil, fmt.Errorf("%v: %s", err, strings.TrimSpace(stderr.String()))
		}
		return out.Bytes(), nil
	case <-time.After(timeout):
		return nil, errors.New("upload timed out")
	}
}

var unsafeName = regexp.MustCompile(`[\x00-\x1f<>:"|?*'\\/$` + "`" + `]`)

// Upload saves data under ~/.cache/taildeck/uploads on the node and returns
// its path, for prompts that reference the file.
func (h *Herdr) Upload(name string, data []byte) (string, error) {
	name = strings.TrimLeft(unsafeName.ReplaceAllString(path.Base(name), "_"), ".")
	if name == "" {
		name = "file"
	}
	if len(name) > 120 {
		name = name[len(name)-120:]
	}
	r := make([]byte, 4)
	rand.Read(r)
	dir := time.Now().Format("20060102-150405") + "-" + hex.EncodeToString(r)
	out, err := h.execIn(`d="${XDG_CACHE_HOME:-$HOME/.cache}/taildeck/uploads/`+dir+`" && mkdir -p "$d" && cat > "$d/`+name+`" && printf %s "$d/`+name+`"`,
		bytes.NewReader(data), 5*time.Minute)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

// ReadFile returns a file of at most maxBytes from the node; "~/" means home.
func (h *Herdr) ReadFile(file string, maxBytes int) ([]byte, error) {
	out, err := h.exec(fmt.Sprintf(`f=%s
case "$f" in "~/"*) f="$HOME/${f#\~/}";; esac
[ -f "$f" ] || { echo MISSING; exit 0; }
s=$(wc -c < "$f"); s=$((s+0))
[ "$s" -le %d ] || { echo TOOBIG; exit 0; }
echo OK; base64 < "$f"`, shellQuote(file), maxBytes), 60*time.Second)
	if err != nil {
		return nil, err
	}
	status, body, _ := bytes.Cut(out, []byte("\n"))
	switch string(status) {
	case "OK":
		return base64.StdEncoding.DecodeString(strings.Join(strings.Fields(string(body)), ""))
	case "TOOBIG":
		return nil, errors.New("file is too large")
	}
	return nil, errors.New("file not found")
}

// ---- slash commands and skills ----

// Commands lists the prompt commands and skills an agent of kind can use in
// cwd, as JSON [{name, description, source}].
func (h *Herdr) Commands(kind, cwd string) (string, error) {
	var script string
	q := shellQuote(cwd)
	switch kind {
	case "claude":
		script = `cwd=` + q + `
for d in "$cwd/.claude" "${CLAUDE_CONFIG_DIR:-$HOME/.claude}" "$HOME/.claude"; do
  [ -d "$d/commands" ] && find "$d/commands" -name '*.md' 2>/dev/null | while IFS= read -r f; do
    n=${f#"$d/commands/"}; n=${n%.md}; printf 'command\t%s\t%s\n' "$(echo "$n" | tr / :)" "$(desc "$f")"; done
  for f in "$d"/skills/*/SKILL.md; do [ -f "$f" ] && printf 'skill\t%s\t%s\n' "$(skill "$f")" "$(desc "$f")"; done
done`
	case "codex":
		script = `for f in "${CODEX_HOME:-$HOME/.codex}"/prompts/*.md; do [ -f "$f" ] && printf 'prompt\tprompts:%s\t%s\n' "$(basename "$f" .md)" "$(desc "$f")"; done`
	case "pi":
		script = `cwd=` + q + `
for d in "$cwd/.pi" "$HOME/.pi/agent"; do
  for f in "$d"/prompts/*.md; do [ -f "$f" ] && printf 'prompt\t%s\t%s\n' "$(basename "$f" .md)" "$(desc "$f")"; done
  for f in "$d"/skills/*/SKILL.md; do [ -f "$f" ] && printf 'skill\tskill:%s\t%s\n' "$(skill "$f")" "$(desc "$f")"; done
done`
	default:
		return "[]", nil
	}
	prelude := `front() { awk 'NR==1&&$0!="---"{exit} NR>1&&$0=="---"{exit} NR>1' "$1" 2>/dev/null; }
desc() { front "$1" | sed -n 's/^description:[[:space:]]*//p' | head -1 | tr -d '"' | cut -c1-160; }
skill() { n=$(front "$1" | sed -n 's/^name:[[:space:]]*//p' | head -1 | tr -d '"'); [ -n "$n" ] || n=$(basename "$(dirname "$1")"); echo "$n"; }
`
	out, err := h.exec(prelude+script, 15*time.Second)
	if err != nil {
		return "", err
	}
	type command struct {
		Name        string `json:"name"`
		Description string `json:"description"`
		Source      string `json:"source"`
	}
	list := []command{}
	seen := map[string]bool{}
	for _, line := range strings.Split(string(out), "\n") {
		p := strings.SplitN(line, "\t", 3)
		if len(p) < 2 || p[1] == "" || seen[p[1]] || len(list) >= 400 {
			continue
		}
		seen[p[1]] = true
		c := command{Name: p[1], Source: p[0]}
		if len(p) == 3 {
			c.Description = p[2]
		}
		list = append(list, c)
	}
	b, err := json.Marshal(list)
	return string(b), err
}

// ---- restarting an agent into the same session ----

type flagSpec struct {
	value, variadic, boolean, dropBool, dropValue, dropSub []string
}

// Flags each agent keeps across a restart; session selection is dropped and
// replaced by an explicit resume.
var restartFlags = map[string]flagSpec{
	"claude": {
		value:     []string{"--model", "--permission-mode", "--settings", "--agent", "--agents", "--append-system-prompt", "--system-prompt", "--fallback-model", "--setting-sources", "--plugin-dir", "--max-turns", "--name"},
		variadic:  []string{"--add-dir", "--mcp-config", "--allowedTools", "--allowed-tools", "--disallowedTools", "--disallowed-tools", "--betas", "--tools"},
		boolean:   []string{"--dangerously-skip-permissions", "--allow-dangerously-skip-permissions", "--verbose", "--ide", "--chrome", "--no-chrome", "--strict-mcp-config", "--debug"},
		dropBool:  []string{"-c", "--continue", "--fork-session", "-p", "--print"},
		dropValue: []string{"--session-id", "-r", "--resume"},
	},
	"codex": {
		value:    []string{"-m", "--model", "-c", "--config", "-p", "--profile", "-s", "--sandbox", "-a", "--ask-for-approval", "-C", "--cd", "--local-provider"},
		variadic: []string{"--add-dir", "-i", "--image", "--enable", "--disable"},
		boolean:  []string{"--yolo", "--dangerously-bypass-approvals-and-sandbox", "--full-auto", "--search", "--oss", "--no-alt-screen"},
		dropBool: []string{"--last", "--all"},
		dropSub:  []string{"resume", "fork"},
	},
	"pi": {
		value:     []string{"--provider", "--model", "--api-key", "--system-prompt", "--append-system-prompt", "--thinking", "--session-dir", "--mode", "-n", "--name", "--tools", "--extension", "-e"},
		variadic:  []string{"--models"},
		boolean:   []string{"--no-tools", "--no-extensions", "--offline", "--verbose"},
		dropBool:  []string{"-c", "--continue", "--no-session"},
		dropValue: []string{"--session", "--session-id", "--fork", "-r", "--resume"},
	},
}

func has(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// keptFlags returns the flags of argv (after the agent's executable) that a
// restart should pass again.
func keptFlags(kind string, argv []string) []string {
	spec := restartFlags[kind]
	start := -1
	for i, a := range argv {
		if path.Base(a) == kind {
			start = i
			break
		}
	}
	if start < 0 {
		return nil
	}
	args := argv[start+1:]
	isFlag := func(s string) bool { return strings.HasPrefix(s, "-") && s != "-" }
	var out []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		if a == "--" {
			break
		}
		if !isFlag(a) {
			if has(spec.dropSub, a) {
				for i+1 < len(args) && !isFlag(args[i+1]) {
					i++
				}
			}
			continue
		}
		name, inline := a, false
		if j := strings.IndexByte(a, '='); j > 0 {
			name, inline = a[:j], true
		}
		switch {
		case has(spec.dropBool, name):
		case has(spec.dropValue, name):
			if !inline && i+1 < len(args) && !isFlag(args[i+1]) {
				i++
			}
		case has(spec.boolean, name) || inline:
			out = append(out, a)
		case has(spec.variadic, name):
			out = append(out, a)
			for i+1 < len(args) && !isFlag(args[i+1]) {
				i++
				out = append(out, args[i])
			}
		default:
			out = append(out, a)
			if (has(spec.value, name) || i+1 < len(args) && !isFlag(args[i+1])) && i+1 < len(args) {
				i++
				out = append(out, args[i])
			}
		}
	}
	return out
}

var rolloutID = regexp.MustCompile(`([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jsonl$`)

// RestartAgent quits the agent in pane and starts it again in the same
// session with the same flags, then optionally sends thenPrompt. Returns
// the new agent name.
func (h *Herdr) RestartAgent(pane, thenPrompt string) (string, error) {
	raw, err := h.Call("agent.get", fmt.Sprintf(`{"target":%q}`, pane))
	if err != nil {
		return "", err
	}
	var pg struct {
		Pane struct {
			Agent        string `json:"agent"`
			AgentSession *struct {
				Agent, Kind, Value string
			} `json:"agent_session"`
		} `json:"agent"`
	}
	json.Unmarshal([]byte(raw), &pg)
	kind := pg.Pane.Agent
	if s := pg.Pane.AgentSession; s != nil && s.Agent != "" {
		kind = s.Agent
	}
	if _, ok := restartFlags[kind]; !ok {
		return "", fmt.Errorf("unsupported_agent: %s can't be restarted from here", kind)
	}
	session := ""
	if s := pg.Pane.AgentSession; s != nil {
		session = s.Value
	}
	h.smu.Lock()
	if s := h.sessions[pane]; session == "" && s != nil {
		session = s.file
	}
	h.smu.Unlock()
	switch {
	case kind == "claude" && strings.HasSuffix(session, ".jsonl"):
		session = strings.TrimSuffix(path.Base(session), ".jsonl")
	case kind == "codex" && strings.HasSuffix(session, ".jsonl"):
		if m := rolloutID.FindStringSubmatch(session); m != nil {
			session = m[1]
		}
	}
	if session == "" {
		return "", errors.New("no_agent_session: the agent has no session to open again yet")
	}

	proc, err := h.processInfo(pane)
	if err != nil {
		return "", err
	}
	var flags []string
	cwd := ""
	for _, p := range proc.Foreground {
		if p.PID == proc.Group {
			flags, cwd = keptFlags(kind, p.Argv), p.Cwd
		}
	}
	if proc.Group != 0 && proc.Group != proc.Shell {
		if err := h.quit(pane, proc.Group, proc.Shell); err != nil {
			return "", err
		}
	}
	for i := 0; i < 20; i++ {
		raw, _ := h.Call("pane.get", fmt.Sprintf(`{"pane_id":%q}`, pane))
		if !strings.Contains(raw, `"agent":"`+kind+`"`) {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if cwd != "" {
		h.Call("pane.send_text", fmt.Sprintf(`{"pane_id":%q,"text":%q}`, pane, "cd -- "+shellQuote(cwd)))
		h.Call("pane.send_keys", fmt.Sprintf(`{"pane_id":%q,"keys":["enter"]}`, pane))
	}
	args := append([]string{}, flags...)
	switch kind {
	case "codex":
		args = append(args, "resume", session)
	case "pi":
		args = append(args, "--session", session)
	default:
		args = append(args, "--resume", session)
	}
	h.smu.Lock()
	delete(h.sessions, pane)
	h.smu.Unlock()
	// A fresh name: herdr doesn't activate an agent started under the name of
	// the one that just quit.
	r := make([]byte, 4)
	rand.Read(r)
	n := kind + "-" + hex.EncodeToString(r)
	params, _ := json.Marshal(map[string]any{"name": n, "kind": kind, "pane_id": pane, "args": args, "timeout_ms": 60000})
	name := ""
	for attempt := 0; attempt < 6 && name == ""; attempt++ {
		_, err := h.Call("agent.start", string(params))
		switch {
		case err == nil:
			name = n
		case strings.Contains(err.Error(), "not an available shell"):
			time.Sleep(time.Second)
		default:
			return "", fmt.Errorf("start_failed: %v", err)
		}
	}
	if name == "" {
		return "", errors.New("start_failed: the shell never came back")
	}
	if thenPrompt != "" {
		var err error
		// A restarted agent takes a while to become interactive; poll for up to
		// what agent.start allows.
		for i := 0; i < 30; i++ {
			time.Sleep(2 * time.Second)
			// herdr settles launch readiness when the agent is read.
			h.Call("agent.get", fmt.Sprintf(`{"target":%q}`, pane))
			_, err = h.Call("agent.prompt", fmt.Sprintf(`{"target":%q,"text":%q}`, pane, thenPrompt))
			if err == nil || !strings.Contains(err.Error(), "not_ready") && !strings.Contains(err.Error(), "pending") && !strings.Contains(err.Error(), "not an active") {
				break
			}
		}
		if err != nil {
			return name, fmt.Errorf("prompt_failed: %v", err)
		}
	}
	return name, nil
}

type processInfo struct {
	Shell      int `json:"shell_pid"`
	Group      int `json:"foreground_process_group_id"`
	Foreground []struct {
		PID  int      `json:"pid"`
		Argv []string `json:"argv"`
		Cwd  string   `json:"cwd"`
	} `json:"foreground_processes"`
}

func (h *Herdr) processInfo(pane string) (processInfo, error) {
	raw, err := h.Call("pane.process_info", fmt.Sprintf(`{"pane_id":%q}`, pane))
	var r struct {
		Info processInfo `json:"process_info"`
	}
	if err == nil {
		err = json.Unmarshal([]byte(raw), &r)
	}
	return r.Info, err
}

// quit asks the agent to exit the way a person would, then signals its
// process group if it doesn't.
func (h *Herdr) quit(pane string, group, shell int) error {
	keys := func(k string) { h.Call("pane.send_keys", fmt.Sprintf(`{"pane_id":%q,"keys":[%q]}`, pane, k)) }
	gone := func(wait time.Duration) bool {
		for end := time.Now().Add(wait); ; {
			p, err := h.processInfo(pane)
			if err == nil && (p.Group == shell || p.Group != group) {
				return true
			}
			if time.Now().After(end) {
				return false
			}
			time.Sleep(250 * time.Millisecond)
		}
	}
	keys("esc")
	time.Sleep(300 * time.Millisecond)
	for round := 0; round < 2; round++ {
		keys("ctrl+c")
		time.Sleep(200 * time.Millisecond)
		keys("ctrl+c")
		if gone(2500 * time.Millisecond) {
			return nil
		}
	}
	for _, sig := range []string{"TERM", "KILL"} {
		h.exec(fmt.Sprintf("kill -%s -%d 2>/dev/null || kill -%s %d", sig, group, sig, group), 5*time.Second)
		if gone(3 * time.Second) {
			return nil
		}
	}
	return errors.New("quit_timeout: the agent didn't quit")
}

// ---- models an agent can switch to ----

type modelChoice struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Efforts []string `json:"efforts,omitempty"`
	Default string   `json:"default,omitempty"`
}

// ModelOptions lists the models and reasoning levels an agent of kind can be
// switched to, from the agent itself: Codex's model catalog cache and the
// models pi reports as usable with the credentials it has. "custom" means
// other model ids are accepted too.
func (h *Herdr) ModelOptions(kind, pane string) (string, error) {
	out := map[string]any{"models": []modelChoice{}, "efforts": []string{}, "custom": false}
	switch kind {
	case "claude":
		out["models"] = []modelChoice{{ID: "fable", Name: "Fable"}, {ID: "opus", Name: "Opus"}, {ID: "sonnet", Name: "Sonnet"}, {ID: "haiku", Name: "Haiku"}}
		out["efforts"] = []string{"low", "medium", "high", "xhigh", "max", "auto"}
	case "codex":
		out["efforts"] = []string{"low", "medium", "high", "xhigh"}
		out["custom"] = true
		// Models named in config.toml (top level and profiles) come first: with
		// another provider configured they are the ones that actually work.
		models := []modelChoice{}
		seen := map[string]bool{}
		if conf, err := h.exec(`cat "${CODEX_HOME:-$HOME/.codex}/config.toml" 2>/dev/null`, 10*time.Second); err == nil {
			for _, m := range codexConfigModel.FindAllStringSubmatch(string(conf), -1) {
				if !seen[m[1]] {
					seen[m[1]] = true
					models = append(models, modelChoice{ID: m[1], Name: m[1]})
				}
			}
		}
		out["models"] = models
		raw, err := h.exec(`cat "${CODEX_HOME:-$HOME/.codex}/models_cache.json" 2>/dev/null`, 10*time.Second)
		if err != nil {
			break
		}
		var cache struct {
			Models []struct {
				Slug        string `json:"slug"`
				DisplayName string `json:"display_name"`
				Visibility  string `json:"visibility"`
				Default     string `json:"default_reasoning_level"`
				Levels      []struct {
					Effort string `json:"effort"`
				} `json:"supported_reasoning_levels"`
			} `json:"models"`
		}
		if json.Unmarshal(raw, &cache) != nil {
			break
		}
		for _, m := range cache.Models {
			if m.Slug == "" || m.Visibility != "list" || seen[m.Slug] {
				continue
			}
			c := modelChoice{ID: m.Slug, Name: m.DisplayName, Default: m.Default}
			if c.Name == "" {
				c.Name = m.Slug
			}
			for _, l := range m.Levels {
				c.Efforts = append(c.Efforts, l.Effort)
			}
			models = append(models, c)
		}
		if len(models) > 0 {
			out["models"] = models
		}
	case "pi", "omp":
		out["efforts"] = []string{"off", "minimal", "low", "medium", "high", "xhigh"}
		out["custom"] = true
		models := h.piModels(kind, pane)
		if len(models) == 0 {
			models = h.piSettingsModels()
		}
		out["models"] = models
	}
	b, err := json.Marshal(out)
	return string(b), err
}

// piModels asks pi for every model it can use right now (its built-in
// catalog and models.json, limited to providers it has credentials for),
// running the same pi the pane runs when that can be told from its process.
func (h *Herdr) piModels(kind, pane string) []modelChoice {
	var cmds []string
	if p, err := h.processInfo(pane); err == nil {
		for _, fp := range p.Foreground {
			for i, a := range fp.Argv {
				if b := path.Base(a); b == kind || b == kind+".js" || strings.Contains(a, "coding-agent") && strings.HasSuffix(a, ".js") {
					var q []string
					for _, x := range fp.Argv[:i+1] {
						q = append(q, shellQuote(x))
					}
					cmds = append(cmds, strings.Join(q, " "))
					break
				}
			}
		}
	}
	// A login shell finds pi where the user's PATH (nvm, npm prefix, ...) puts it.
	cmds = append(cmds, `"${SHELL:-sh}" -lc 'exec `+kind+` "$@"' `+kind, `"${SHELL:-sh}" -ic 'exec `+kind+` "$@"' `+kind)
	for _, c := range cmds {
		out, err := h.exec(c+" --list-models </dev/null 2>/dev/null", 45*time.Second)
		if err != nil {
			continue
		}
		if models := parsePiModelTable(string(out)); len(models) > 0 {
			return models
		}
	}
	return nil
}

var codexConfigModel = regexp.MustCompile(`(?m)^\s*model\s*=\s*["']([^"']+)["']`)

// parsePiModelTable reads "provider model context max-out thinking images" rows.
func parsePiModelTable(out string) []modelChoice {
	var models []modelChoice
	header := false
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) >= 2 && f[0] == "provider" && f[1] == "model" {
			header = true
			continue
		}
		if !header || len(f) < 6 {
			continue
		}
		c := modelChoice{ID: f[0] + "/" + f[1], Name: f[1] + " · " + f[0]}
		if f[4] == "no" {
			c.Efforts = []string{"off"}
		}
		models = append(models, c)
	}
	return models
}

// piSettingsModels is the fallback when pi can't be run: its default and enabled models.
func (h *Herdr) piSettingsModels() []modelChoice {
	raw, err := h.exec(`cat "$HOME/.pi/agent/settings.json" 2>/dev/null`, 10*time.Second)
	if err != nil {
		return nil
	}
	var set struct {
		Provider string   `json:"defaultProvider"`
		Model    string   `json:"defaultModel"`
		Enabled  []string `json:"enabledModels"`
	}
	json.Unmarshal(raw, &set)
	seen := map[string]bool{}
	var models []modelChoice
	add := func(id string) {
		if id == "" || seen[id] || strings.ContainsAny(id, "*?[") {
			return
		}
		seen[id] = true
		models = append(models, modelChoice{ID: id, Name: id})
	}
	if set.Provider != "" && set.Model != "" {
		add(set.Provider + "/" + set.Model)
	}
	for _, m := range set.Enabled {
		add(m)
	}
	return models
}
