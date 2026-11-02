package tsbridge

import (
	"bytes"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// chatMsg is the agent-neutral message shape the app renders.
type chatMsg struct {
	ID      string `json:"id"`
	Role    string `json:"role"` // user, assistant, thinking, tool, system
	Text    string `json:"text,omitempty"`
	Name    string `json:"name,omitempty"`
	Input   string `json:"input,omitempty"`
	Output  string `json:"output,omitempty"`
	Error   bool   `json:"error,omitempty"`
	Pending bool   `json:"pending,omitempty"`
}

type piEntry struct {
	id, parent string
	msgs       []chatMsg
	results    []chatMsg // tool results keyed by Name=toolCallId
}

// transcript incrementally parses one agent's native session file.
type transcript struct {
	file      string
	kind      string
	offset    int64
	started   bool
	partial   []byte
	truncated bool

	msgs  []chatMsg
	tools map[string]int // tool call id -> index in msgs

	pi     []piEntry
	piByID map[string]int
}

const (
	initialWindow = 2 << 20 // only parse the tail of huge sessions
	maxChunk      = 4 << 20
	maxMessages   = 400
	maxText       = 24 << 10
	maxToolText   = 6 << 10
)

var safeID = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

type agentRecord struct {
	Agent         string `json:"agent"`
	AgentStatus   string `json:"agent_status"`
	Cwd           string `json:"cwd"`
	ForegroundCwd string `json:"foreground_cwd"`
	AgentSession  *struct {
		Agent string `json:"agent"`
		Kind  string `json:"kind"`
		Value string `json:"value"`
	} `json:"agent_session"`
}

// Transcript returns the conversation of the agent in target (pane id or
// agent name) as JSON. Agents without a readable session file fall back to
// the pane's recent screen text ("mode":"screen").
func (h *Herdr) Transcript(target string) (string, error) {
	raw, err := h.Call("agent.get", fmt.Sprintf(`{"target":%q}`, target))
	if err != nil {
		return "", err
	}
	var info struct {
		Agent agentRecord `json:"agent"`
	}
	if err := json.Unmarshal([]byte(raw), &info); err != nil {
		return "", err
	}
	a := info.Agent
	kind := strings.ToLower(a.Agent)
	if a.AgentSession != nil && a.AgentSession.Agent != "" {
		kind = strings.ToLower(a.AgentSession.Agent)
	}
	if kind == "omp" {
		kind = "pi"
	}
	file, matched := h.resolveSessionFile(kind, a)
	if file != "" {
		if out, err := h.readTranscript(target, kind, file, matched); err == nil {
			return out, nil
		}
	}
	return h.screen(target)
}

func (h *Herdr) screen(target string) (string, error) {
	raw, err := h.Call("agent.read", fmt.Sprintf(`{"target":%q,"source":"recent_unwrapped","lines":400}`, target))
	if err != nil {
		return "", err
	}
	var r struct {
		Read struct {
			Text string `json:"text"`
		} `json:"read"`
	}
	json.Unmarshal([]byte(raw), &r)
	b, err := json.Marshal(map[string]any{"mode": "screen", "screen": strings.TrimRight(r.Read.Text, "\n ")})
	return string(b), err
}

func (h *Herdr) resolveSessionFile(kind string, a agentRecord) (path, matched string) {
	var script string
	if s := a.AgentSession; s != nil {
		switch {
		case s.Kind == "path" && strings.HasPrefix(s.Value, "/"):
			script = fmt.Sprintf(`[ -f %s ] && echo %s`, shellQuote(s.Value), shellQuote(s.Value))
		case !safeID.MatchString(s.Value):
		case kind == "claude":
			script = fmt.Sprintf(`for f in "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"/projects/*/%s.jsonl; do [ -f "$f" ] && echo "$f" && break; done`, s.Value)
		case kind == "codex":
			script = fmt.Sprintf(`find "${CODEX_HOME:-$HOME/.codex}/sessions" -name '*%s.jsonl' 2>/dev/null | head -1`, s.Value)
		case kind == "pi":
			script = fmt.Sprintf(`find "$HOME/.pi/agent/sessions" -name '*%s*.jsonl' 2>/dev/null | head -1`, s.Value)
		}
		if script != "" {
			if out, err := h.exec(script, 10*time.Second); err == nil {
				if p := strings.TrimSpace(string(out)); p != "" {
					return strings.Split(p, "\n")[0], "session"
				}
			}
		}
	}
	// Without a session reference, take the newest session for the agent's directory.
	cwd := a.ForegroundCwd
	if cwd == "" {
		cwd = a.Cwd
	}
	if cwd == "" || !strings.HasPrefix(cwd, "/") {
		return "", ""
	}
	switch kind {
	case "claude":
		dir := regexp.MustCompile(`[^A-Za-z0-9]`).ReplaceAllString(cwd, "-")
		script = fmt.Sprintf(`ls -t "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"/projects/%s/*.jsonl 2>/dev/null | head -1`, shellQuote(dir))
	case "pi":
		dir := "--" + strings.NewReplacer("/", "-", ":", "-").Replace(strings.TrimPrefix(cwd, "/")) + "--"
		script = fmt.Sprintf(`ls -t "$HOME/.pi/agent/sessions"/%s/*.jsonl 2>/dev/null | head -1`, shellQuote(dir))
	default:
		return "", ""
	}
	out, err := h.exec(script, 10*time.Second)
	if err != nil {
		return "", ""
	}
	if p := strings.TrimSpace(string(out)); p != "" {
		return strings.Split(p, "\n")[0], "cwd"
	}
	return "", ""
}

func (h *Herdr) readTranscript(target, kind, file, matched string) (string, error) {
	h.tmu.Lock()
	defer h.tmu.Unlock()
	for attempt := 0; attempt < 2; attempt++ {
		t := h.transcripts[target]
		if t == nil || t.file != file || t.kind != kind {
			t = &transcript{file: file, kind: kind, tools: map[string]int{}, piByID: map[string]int{}}
			h.transcripts[target] = t
		}
		first := 0
		if !t.started {
			first = 1
		}
		// Prints "<size> <start>" then the bytes from start; a first read of a
		// large file starts inside the tail window.
		out, err := h.exec(fmt.Sprintf(`f=%s; [ -f "$f" ] || exit 3
s=$(wc -c < "$f"); s=$((s+0)); o=%d
if [ %d = 1 ] && [ "$s" -gt %d ]; then o=$((s-%d)); fi
echo "$s $o"; [ "$o" -lt "$s" ] && tail -c +$((o+1)) "$f" | head -c %d; true`,
			shellQuote(file), t.offset, first, initialWindow, initialWindow, maxChunk), 30*time.Second)
		if err != nil {
			return "", err
		}
		head, data, _ := bytes.Cut(out, []byte("\n"))
		var size, start int64
		if _, err := fmt.Sscan(string(head), &size, &start); err != nil {
			return "", fmt.Errorf("cannot read %s", file)
		}
		if size < t.offset {
			delete(h.transcripts, target) // rewritten or replaced: start over
			continue
		}
		if !t.started {
			t.started = true
			if start > 0 {
				t.truncated = true
				if i := bytes.IndexByte(data, '\n'); i >= 0 {
					start += int64(i + 1)
					data = data[i+1:]
				}
			}
		}
		t.feed(data)
		t.offset = start + int64(len(data))
		return t.render(matched)
	}
	return "", fmt.Errorf("cannot read %s", file)
}

func (t *transcript) feed(data []byte) {
	buf := append(t.partial, data...)
	for {
		i := bytes.IndexByte(buf, '\n')
		if i < 0 {
			break
		}
		line := bytes.TrimSpace(buf[:i])
		buf = buf[i+1:]
		if len(line) == 0 {
			continue
		}
		switch t.kind {
		case "pi":
			t.piLine(line)
		case "claude":
			t.claudeLine(line)
		case "codex":
			t.codexLine(line)
		}
	}
	t.partial = append([]byte(nil), buf...)
}

func (t *transcript) add(m chatMsg) {
	if m.Text == "" && m.Role != "tool" {
		return
	}
	m.Text = clip(m.Text, maxText)
	if m.ID == "" {
		m.ID = fmt.Sprint(len(t.msgs))
	}
	t.msgs = append(t.msgs, m)
}

func (t *transcript) addTool(id, name, input string) {
	t.add(chatMsg{ID: "tool:" + id + fmt.Sprint(len(t.msgs)), Role: "tool", Name: name, Input: clip(input, maxToolText), Pending: true})
	if id != "" {
		t.tools[id] = len(t.msgs) - 1
	}
}

func (t *transcript) toolResult(id, output string, isErr bool) {
	if i, ok := t.tools[id]; ok {
		t.msgs[i].Output = clip(output, maxToolText)
		t.msgs[i].Error = isErr
		t.msgs[i].Pending = false
	}
}

func (t *transcript) render(matched string) (string, error) {
	msgs := t.msgs
	if t.kind == "pi" {
		msgs = t.piPath()
	}
	truncated := t.truncated
	if len(msgs) > maxMessages {
		msgs = msgs[len(msgs)-maxMessages:]
		truncated = true
	}
	if msgs == nil {
		msgs = []chatMsg{}
	}
	b, err := json.Marshal(map[string]any{
		"mode": "transcript", "source": t.kind, "file": t.file, "matched": matched,
		"truncated": truncated, "messages": msgs,
	})
	return string(b), err
}

// ---- pi: tree of entries; the conversation is the path to the newest one ----

func (t *transcript) piLine(line []byte) {
	var e struct {
		Type     string          `json:"type"`
		ID       string          `json:"id"`
		ParentID *string         `json:"parentId"`
		Message  json.RawMessage `json:"message"`
		Summary  string          `json:"summary"`
	}
	if json.Unmarshal(line, &e) != nil || e.ID == "" {
		return
	}
	pe := piEntry{id: e.ID}
	if e.ParentID != nil {
		pe.parent = *e.ParentID
	}
	switch e.Type {
	case "message":
		pe.msgs, pe.results = piMessage(e.ID, e.Message)
	case "compaction", "branch_summary":
		if e.Summary != "" {
			pe.msgs = []chatMsg{{ID: e.ID, Role: "system", Text: e.Summary}}
		}
	}
	t.piByID[e.ID] = len(t.pi)
	t.pi = append(t.pi, pe)
}

func piMessage(id string, raw json.RawMessage) (msgs, results []chatMsg) {
	var m struct {
		Role       string          `json:"role"`
		Content    json.RawMessage `json:"content"`
		ToolCallID string          `json:"toolCallId"`
		IsError    bool            `json:"isError"`
		Command    string          `json:"command"`
		Output     string          `json:"output"`
		ExitCode   *int            `json:"exitCode"`
		Summary    string          `json:"summary"`
		Display    *bool           `json:"display"`
	}
	if json.Unmarshal(raw, &m) != nil {
		return nil, nil
	}
	switch m.Role {
	case "user":
		return []chatMsg{{ID: id, Role: "user", Text: contentText(m.Content)}}, nil
	case "assistant":
		var blocks []struct {
			Type      string          `json:"type"`
			Text      string          `json:"text"`
			Thinking  string          `json:"thinking"`
			ID        string          `json:"id"`
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		json.Unmarshal(m.Content, &blocks)
		for i, b := range blocks {
			bid := fmt.Sprintf("%s.%d", id, i)
			switch b.Type {
			case "text":
				msgs = append(msgs, chatMsg{ID: bid, Role: "assistant", Text: b.Text})
			case "thinking":
				msgs = append(msgs, chatMsg{ID: bid, Role: "thinking", Text: b.Thinking})
			case "toolCall":
				msgs = append(msgs, chatMsg{ID: "call:" + b.ID, Role: "tool", Name: b.Name, Input: toolInput(b.Arguments), Pending: true})
			}
		}
		return msgs, nil
	case "toolResult":
		return nil, []chatMsg{{Name: m.ToolCallID, Output: contentText(m.Content), Error: m.IsError}}
	case "bashExecution":
		out := m.Output
		return []chatMsg{{ID: id, Role: "tool", Name: "bash", Input: m.Command, Output: out, Error: m.ExitCode != nil && *m.ExitCode != 0}}, nil
	case "custom":
		if m.Display != nil && *m.Display {
			return []chatMsg{{ID: id, Role: "system", Text: contentText(m.Content)}}, nil
		}
	case "branchSummary", "compactionSummary":
		return []chatMsg{{ID: id, Role: "system", Text: m.Summary}}, nil
	}
	return nil, nil
}

func (t *transcript) piPath() []chatMsg {
	if len(t.pi) == 0 {
		return nil
	}
	var chain []int
	seen := map[int]bool{}
	for i, ok := len(t.pi)-1, true; ok && !seen[i]; i, ok = t.piByID[t.pi[i].parent] {
		seen[i] = true
		chain = append(chain, i)
		if t.pi[i].parent == "" {
			break
		}
	}
	var out []chatMsg
	calls := map[string]int{}
	for k := len(chain) - 1; k >= 0; k-- {
		e := t.pi[chain[k]]
		for _, m := range e.msgs {
			m.Text = clip(m.Text, maxText)
			m.Input = clip(m.Input, maxToolText)
			out = append(out, m)
			if strings.HasPrefix(m.ID, "call:") {
				calls[strings.TrimPrefix(m.ID, "call:")] = len(out) - 1
			}
		}
		for _, r := range e.results {
			if i, ok := calls[r.Name]; ok {
				out[i].Output = clip(r.Output, maxToolText)
				out[i].Error = r.Error
				out[i].Pending = false
			}
		}
	}
	filtered := out[:0]
	for _, m := range out {
		if m.Text != "" || m.Role == "tool" {
			filtered = append(filtered, m)
		}
	}
	return filtered
}

// ---- Claude Code: one JSON object per content block, tool results in user turns ----

func (t *transcript) claudeLine(line []byte) {
	var e struct {
		Type        string `json:"type"`
		UUID        string `json:"uuid"`
		IsSidechain bool   `json:"isSidechain"`
		IsMeta      bool   `json:"isMeta"`
		Message     struct {
			Role    string          `json:"role"`
			Content json.RawMessage `json:"content"`
		} `json:"message"`
		Subtype string `json:"subtype"`
		Content string `json:"content"`
	}
	if json.Unmarshal(line, &e) != nil || e.IsSidechain || e.IsMeta {
		return
	}
	switch e.Type {
	case "user":
		var s string
		if json.Unmarshal(e.Message.Content, &s) == nil {
			t.add(chatMsg{ID: e.UUID, Role: claudeUserRole(s), Text: claudeCleanUser(s)})
			return
		}
		var blocks []struct {
			Type      string          `json:"type"`
			Text      string          `json:"text"`
			ToolUseID string          `json:"tool_use_id"`
			Content   json.RawMessage `json:"content"`
			IsError   bool            `json:"is_error"`
		}
		json.Unmarshal(e.Message.Content, &blocks)
		for i, b := range blocks {
			switch b.Type {
			case "text":
				t.add(chatMsg{ID: fmt.Sprintf("%s.%d", e.UUID, i), Role: claudeUserRole(b.Text), Text: claudeCleanUser(b.Text)})
			case "tool_result":
				t.toolResult(b.ToolUseID, contentText(b.Content), b.IsError)
			}
		}
	case "assistant":
		var blocks []struct {
			Type     string          `json:"type"`
			Text     string          `json:"text"`
			Thinking string          `json:"thinking"`
			ID       string          `json:"id"`
			Name     string          `json:"name"`
			Input    json.RawMessage `json:"input"`
		}
		json.Unmarshal(e.Message.Content, &blocks)
		for i, b := range blocks {
			id := fmt.Sprintf("%s.%d", e.UUID, i)
			switch b.Type {
			case "text":
				t.add(chatMsg{ID: id, Role: "assistant", Text: b.Text})
			case "thinking":
				t.add(chatMsg{ID: id, Role: "thinking", Text: b.Thinking})
			case "tool_use":
				t.addTool(b.ID, b.Name, toolInput(b.Input))
			}
		}
	case "system":
		if e.Subtype == "compact_boundary" {
			t.add(chatMsg{ID: e.UUID, Role: "system", Text: "Conversation compacted"})
		}
	}
}

var claudeTag = regexp.MustCompile(`(?s)<(command-name|command-message|command-args|local-command-stdout|local-command-stderr)>(.*?)</[a-z-]+>`)

func claudeUserRole(s string) string {
	if strings.HasPrefix(strings.TrimSpace(s), "<") && claudeTag.MatchString(s) || strings.HasPrefix(s, "[Request interrupted") {
		return "system"
	}
	return "user"
}

// claudeCleanUser turns slash-command envelopes into "/cmd args".
func claudeCleanUser(s string) string {
	if !claudeTag.MatchString(s) {
		return s
	}
	parts := map[string]string{}
	for _, m := range claudeTag.FindAllStringSubmatch(s, -1) {
		parts[m[1]] = strings.TrimSpace(m[2])
	}
	if name := parts["command-name"]; name != "" {
		return strings.TrimSpace(name + " " + parts["command-args"])
	}
	return strings.TrimSpace(parts["local-command-stdout"] + parts["local-command-stderr"])
}

// ---- Codex: event_msg carries the chat, response_item carries tool calls ----

func (t *transcript) codexLine(line []byte) {
	var e struct {
		Type    string `json:"type"`
		Payload struct {
			Type      string          `json:"type"`
			Message   string          `json:"message"`
			Text      string          `json:"text"`
			Name      string          `json:"name"`
			Arguments string          `json:"arguments"`
			Input     string          `json:"input"`
			CallID    string          `json:"call_id"`
			Output    json.RawMessage `json:"output"`
			Action    struct {
				Command []string `json:"command"`
			} `json:"action"`
		} `json:"payload"`
	}
	if json.Unmarshal(line, &e) != nil {
		return
	}
	p := e.Payload
	switch e.Type + "/" + p.Type {
	case "event_msg/user_message":
		t.add(chatMsg{Role: "user", Text: p.Message})
	case "event_msg/agent_message":
		t.add(chatMsg{Role: "assistant", Text: p.Message})
	case "event_msg/agent_reasoning":
		t.add(chatMsg{Role: "thinking", Text: p.Text})
	case "response_item/function_call":
		t.addTool(p.CallID, p.Name, toolInput(json.RawMessage(p.Arguments)))
	case "response_item/custom_tool_call":
		t.addTool(p.CallID, p.Name, p.Input)
	case "response_item/local_shell_call":
		t.addTool(p.CallID, "shell", strings.Join(p.Action.Command, " "))
	case "response_item/function_call_output", "response_item/custom_tool_call_output", "response_item/local_shell_call_output":
		out := codexOutput(p.Output)
		t.toolResult(p.CallID, out, false)
	}
}

func codexOutput(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		// Older Codex wraps output as {"output": "...", "metadata": {...}} inside a string.
		var inner struct {
			Output string `json:"output"`
		}
		if json.Unmarshal([]byte(s), &inner) == nil && inner.Output != "" {
			return inner.Output
		}
		return s
	}
	var obj struct {
		Content json.RawMessage `json:"content"`
		Output  string          `json:"output"`
	}
	if json.Unmarshal(raw, &obj) == nil {
		if obj.Output != "" {
			return obj.Output
		}
		return contentText(obj.Content)
	}
	return string(raw)
}

// ---- shared helpers ----

// contentText flattens a string or an array of {type:text,text} blocks.
func contentText(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(raw, &blocks) != nil {
		return ""
	}
	var parts []string
	for _, b := range blocks {
		switch b.Type {
		case "text", "input_text", "output_text":
			parts = append(parts, b.Text)
		case "image":
			parts = append(parts, "[image]")
		}
	}
	return strings.Join(parts, "\n")
}

// toolInput shows the most telling argument (command, path, ...) first.
func toolInput(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var args map[string]any
	if json.Unmarshal(raw, &args) != nil {
		return string(raw)
	}
	for _, k := range []string{"command", "cmd", "file_path", "path", "pattern", "url", "query", "description"} {
		if v, ok := args[k]; ok {
			s := fmt.Sprint(v)
			if arr, ok := v.([]any); ok {
				var p []string
				for _, x := range arr {
					p = append(p, fmt.Sprint(x))
				}
				s = strings.Join(p, " ")
			}
			if len(args) == 1 {
				return s
			}
			rest := map[string]any{}
			for kk, vv := range args {
				if kk != k {
					rest[kk] = vv
				}
			}
			b, _ := json.MarshalIndent(rest, "", "  ")
			return s + "\n" + string(b)
		}
	}
	b, _ := json.MarshalIndent(args, "", "  ")
	return string(b)
}

func clip(s string, n int) string {
	if len(s) <= n {
		return s
	}
	cut := n
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return s[:cut] + "\n…"
}
