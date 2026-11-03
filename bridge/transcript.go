package tsbridge

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// chatMsg is the agent-neutral message shape the app renders.
type chatMsg struct {
	ID      string `json:"id"`
	Role    string `json:"role"` // user, assistant, thinking, tool, system, command, output, recap, teammate, task
	Text    string `json:"text,omitempty"`
	Name    string `json:"name,omitempty"`
	Input   string `json:"input,omitempty"`
	Output  string `json:"output,omitempty"`
	Error   bool   `json:"error,omitempty"`
	Pending bool   `json:"pending,omitempty"`
	TS      int64  `json:"ts,omitempty"`      // unix ms
	Kind    string `json:"kind,omitempty"`    // tools: shell, read, edit, write, search, fetch, task, todo, other
	Summary string `json:"summary,omitempty"` // tools: the one argument that says what the call does
	Diff    string `json:"diff,omitempty"`    // edits and writes, as +/- lines

	Call       string   `json:"call,omitempty"`       // tools: the agent's own call id
	Images     []string `json:"images,omitempty"`     // "img:<id>" (see Herdr.Image) or file paths on the node
	From       string   `json:"from,omitempty"`       // teammate messages: the sender
	Queued     bool     `json:"queued,omitempty"`     // sent while the agent was working
	Background bool     `json:"background,omitempty"` // async subagent or background job still running
}

type queuedPrompt struct {
	ID    string `json:"id"`
	Text  string `json:"text"`
	human bool
}

type todoItem struct {
	Text   string `json:"text"`
	Status string `json:"status"` // pending, in_progress, completed
}

type piEntry struct {
	id, parent string
	msgs       []chatMsg
	results    []chatMsg // tool results keyed by Name=toolCallId
	used       int64
	model      string
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
	ts    int64          // timestamp of the line being parsed

	todos  []todoItem
	used   int64 // tokens in context after the latest model call
	window int64 // context window, when the agent records it
	model  string

	effort, permissionMode, gitBranch, cwd string
	piModel                                string         // provider/model from pi's model_change entries
	queue                                  []queuedPrompt // Claude's own queue of prompts typed while it works
	images                                 *imageStore
	sidechain                              bool // subagent transcripts are all sidechain
	limit                                  int  // most messages rendered

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
	file, matched := "", ""
	if a.AgentSession == nil || a.AgentSession.Value == "" {
		cwd := a.ForegroundCwd
		if cwd == "" {
			cwd = a.Cwd
		}
		if file = h.identify(target, kind, cwd); file != "" {
			matched = "session"
		}
	}
	if file == "" {
		file, matched = h.resolveSessionFile(kind, a)
	}
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
		window := h.windows[target]
		if window == 0 {
			window = initialWindow
		}
		if t == nil || t.file != file || t.kind != kind {
			t = &transcript{
				file: file, kind: kind, tools: map[string]int{}, piByID: map[string]int{},
				images: h.images, limit: maxMessages * (window / initialWindow),
			}
			h.transcripts[target] = t
		}
		chunk := maxChunk
		if !t.started && window > chunk {
			chunk = window
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
			shellQuote(file), t.offset, first, window, window, chunk), 60*time.Second)
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
	if m.Text == "" && m.Role != "tool" && m.Kind == "" && len(m.Images) == 0 {
		return
	}
	m.Text = clip(m.Text, maxText)
	if m.TS == 0 {
		m.TS = t.ts
	}
	if m.ID == "" {
		m.ID = fmt.Sprint(len(t.msgs))
	}
	t.msgs = append(t.msgs, m)
}

func (t *transcript) addTool(id, name string, args []byte) {
	m := toolMsg(name, args)
	m.ID = "tool:" + id + fmt.Sprint(len(t.msgs))
	m.Call = id
	if todos, ok := parseTodos(name, args); ok {
		t.todos = todos
	}
	t.add(m)
	if id != "" {
		t.tools[id] = len(t.msgs) - 1
	}
}

func (t *transcript) toolResult(id, output string, isErr bool, images ...string) {
	if i, ok := t.tools[id]; ok {
		t.msgs[i].Output = clip(output, maxToolText)
		t.msgs[i].Error = isErr
		t.msgs[i].Pending = false
		t.msgs[i].Images = images
	}
}

func (t *transcript) render(matched string) (string, error) {
	msgs := t.msgs
	used, model := t.used, t.model
	if t.kind == "pi" {
		msgs, used, model = t.piPath()
		if t.piModel != "" {
			model = t.piModel
		}
	}
	truncated := t.truncated
	limit := t.limit
	if limit == 0 {
		limit = maxMessages
	}
	if len(msgs) > limit {
		msgs = msgs[len(msgs)-limit:]
		truncated = true
	}
	queued := []queuedPrompt{}
	for _, q := range t.queue {
		if q.human {
			queued = append(queued, q)
		}
	}
	if msgs == nil {
		msgs = []chatMsg{}
	}
	b, err := json.Marshal(map[string]any{
		"mode": "transcript", "source": t.kind, "file": t.file, "matched": matched,
		"truncated": truncated, "messages": msgs, "todos": t.todos, "model": model,
		"context": map[string]int64{"used": used, "window": t.window},
		"queued":  queued,
		"settings": map[string]string{
			"model": model, "effort": t.effort, "permission_mode": t.permissionMode,
			"git_branch": t.gitBranch, "cwd": t.cwd,
		},
	})
	return string(b), err
}

// ---- pi: tree of entries; the conversation is the path to the newest one ----

func (t *transcript) piLine(line []byte) {
	var e struct {
		Type          string          `json:"type"`
		ID            string          `json:"id"`
		ParentID      *string         `json:"parentId"`
		Message       json.RawMessage `json:"message"`
		Summary       string          `json:"summary"`
		Timestamp     json.RawMessage `json:"timestamp"`
		ThinkingLevel string          `json:"thinkingLevel"`
		Provider      string          `json:"provider"`
		ModelID       string          `json:"modelId"`
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
		pe.msgs, pe.results = t.piMessage(e.ID, e.Message)
		var u struct {
			Model string `json:"model"`
			Usage struct {
				Input, Output, CacheRead, CacheWrite, TotalTokens int64
			} `json:"usage"`
		}
		if json.Unmarshal(e.Message, &u) == nil {
			pe.model = u.Model
			pe.used = u.Usage.TotalTokens
			if pe.used == 0 {
				pe.used = u.Usage.Input + u.Usage.Output + u.Usage.CacheRead + u.Usage.CacheWrite
			}
		}
	case "thinking_level_change":
		t.effort = e.ThinkingLevel
	case "model_change":
		if e.ModelID != "" {
			t.piModel = e.Provider + "/" + e.ModelID
		}
	case "compaction", "branch_summary":
		if e.Summary != "" {
			pe.msgs = []chatMsg{{ID: e.ID, Role: "system", Text: e.Summary}}
		}
	}
	ts := parseTS(e.Timestamp)
	for i := range pe.msgs {
		if pe.msgs[i].TS == 0 {
			pe.msgs[i].TS = ts
		}
	}
	t.piByID[e.ID] = len(t.pi)
	t.pi = append(t.pi, pe)
}

func (t *transcript) piMessage(id string, raw json.RawMessage) (msgs, results []chatMsg) {
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
		Timestamp  json.RawMessage `json:"timestamp"`
		StopReason string          `json:"stopReason"`
		ErrorMsg   string          `json:"errorMessage"`
	}
	if json.Unmarshal(raw, &m) != nil {
		return nil, nil
	}
	defer func() {
		ts := parseTS(m.Timestamp)
		for i := range msgs {
			msgs[i].TS = ts
		}
	}()
	switch m.Role {
	case "user":
		text, images := t.parts(m.Content)
		return []chatMsg{{ID: id, Role: "user", Text: text, Images: images}}, nil
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
				tm := toolMsg(b.Name, b.Arguments)
				tm.ID = "call:" + b.ID
				tm.Call = b.ID
				msgs = append(msgs, tm)
			}
		}
		switch m.StopReason {
		case "error":
			if m.ErrorMsg != "" {
				msgs = append(msgs, chatMsg{ID: id + ".error", Role: "system", Kind: "error", Text: clip(m.ErrorMsg, 2000)})
			}
		case "aborted":
			msgs = append(msgs, chatMsg{ID: id + ".aborted", Role: "system", Kind: "interrupted"})
		}
		return msgs, nil
	case "toolResult":
		text, images := t.parts(m.Content)
		return nil, []chatMsg{{Name: m.ToolCallID, Output: text, Error: m.IsError, Images: images}}
	case "bashExecution":
		out := m.Output
		return []chatMsg{{ID: id, Role: "tool", Name: "bash", Kind: "shell", Summary: firstLine(m.Command), Input: m.Command, Output: out, Error: m.ExitCode != nil && *m.ExitCode != 0}}, nil
	case "custom":
		if m.Display != nil && *m.Display {
			return []chatMsg{{ID: id, Role: "system", Text: contentText(m.Content)}}, nil
		}
	case "branchSummary", "compactionSummary":
		return []chatMsg{{ID: id, Role: "system", Text: m.Summary}}, nil
	}
	return nil, nil
}

func (t *transcript) piPath() (msgs []chatMsg, used int64, model string) {
	if len(t.pi) == 0 {
		return nil, 0, ""
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
		if e.used > 0 {
			used = e.used
		}
		if e.model != "" {
			model = e.model
		}
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
				out[i].Images = r.Images
			}
		}
	}
	filtered := out[:0]
	for _, m := range out {
		if m.Text != "" || m.Role == "tool" || m.Kind != "" || len(m.Images) > 0 {
			filtered = append(filtered, m)
		}
	}
	return filtered, used, model
}

// ---- Claude Code: one JSON object per content block, tool results in user turns ----

func (t *transcript) claudeLine(line []byte) {
	var e struct {
		Type                      string          `json:"type"`
		UUID                      string          `json:"uuid"`
		Subtype                   string          `json:"subtype"`
		Operation                 string          `json:"operation"`
		IsSidechain               bool            `json:"isSidechain"`
		IsMeta                    bool            `json:"isMeta"`
		IsCompactSummary          bool            `json:"isCompactSummary"`
		IsVisibleInTranscriptOnly bool            `json:"isVisibleInTranscriptOnly"`
		Content                   json.RawMessage `json:"content"`
		Timestamp                 json.RawMessage `json:"timestamp"`
		PermissionMode            string          `json:"permissionMode"`
		Cwd                       string          `json:"cwd"`
		GitBranch                 string          `json:"gitBranch"`
		Effort                    string          `json:"effort"`
		ToolUseResult             json.RawMessage `json:"toolUseResult"`
		Attachment                *struct {
			Type        string          `json:"type"`
			Prompt      json.RawMessage `json:"prompt"`
			CommandMode string          `json:"commandMode"`
			Origin      *struct {
				Kind string `json:"kind"`
			} `json:"origin"`
		} `json:"attachment"`
		Message struct {
			Role    string          `json:"role"`
			Content json.RawMessage `json:"content"`
			Model   string          `json:"model"`
			Usage   *struct {
				Input       int64 `json:"input_tokens"`
				CacheCreate int64 `json:"cache_creation_input_tokens"`
				CacheRead   int64 `json:"cache_read_input_tokens"`
				Output      int64 `json:"output_tokens"`
			} `json:"usage"`
		} `json:"message"`
	}
	if json.Unmarshal(line, &e) != nil || e.IsSidechain && !t.sidechain {
		return
	}
	t.ts = parseTS(e.Timestamp)
	if e.Cwd != "" {
		t.cwd, t.gitBranch = e.Cwd, e.GitBranch
	}
	if e.PermissionMode != "" && (e.Type == "user" || e.Type == "permission-mode") {
		t.permissionMode = e.PermissionMode
	}
	switch e.Type {
	case "queue-operation":
		t.queueOp(e.Operation, rawString(e.Content))
	case "attachment":
		a := e.Attachment
		if a != nil && a.Type == "queued_command" && (a.Origin == nil || a.Origin.Kind == "human") && (a.CommandMode == "" || a.CommandMode == "prompt") {
			text, images := t.parts(a.Prompt)
			t.userText(e.UUID, text, images, true)
		}
	case "system":
		switch e.Subtype {
		case "compact_boundary":
			t.add(chatMsg{ID: e.UUID, Role: "system", Kind: "compacted"})
		case "away_summary":
			text := strings.TrimSpace(recapHint.ReplaceAllString(rawString(e.Content), ""))
			t.add(chatMsg{ID: e.UUID, Role: "recap", Text: text})
		case "local_command":
			if !e.IsMeta {
				t.userText(e.UUID, rawString(e.Content), nil, false)
			}
		}
	case "user":
		if e.IsMeta || e.IsVisibleInTranscriptOnly {
			return
		}
		if e.IsCompactSummary {
			t.add(chatMsg{ID: e.UUID, Role: "system", Kind: "compacted"})
			return
		}
		var s string
		if json.Unmarshal(e.Message.Content, &s) == nil {
			t.userText(e.UUID, s, nil, false)
			return
		}
		var blocks []struct {
			Type      string          `json:"type"`
			Text      string          `json:"text"`
			ToolUseID string          `json:"tool_use_id"`
			Content   json.RawMessage `json:"content"`
			IsError   bool            `json:"is_error"`
			Source    *imageSource    `json:"source"`
		}
		json.Unmarshal(e.Message.Content, &blocks)
		var texts, images []string
		for _, b := range blocks {
			switch b.Type {
			case "text":
				texts = append(texts, b.Text)
			case "image":
				if ref := t.storeImage(b.Source); ref != "" {
					images = append(images, ref)
				}
			case "tool_result":
				var r struct {
					IsAsync bool `json:"isAsync"`
				}
				json.Unmarshal(e.ToolUseResult, &r)
				if i, ok := t.tools[b.ToolUseID]; ok && r.IsAsync && t.msgs[i].Kind == "task" {
					t.msgs[i].Background = true
					continue
				}
				text, imgs := t.parts(b.Content)
				t.toolResult(b.ToolUseID, text, b.IsError, imgs...)
			}
		}
		if len(texts) > 0 || len(images) > 0 {
			t.userText(e.UUID, strings.Join(texts, "\n"), images, false)
		}
	case "assistant":
		if u := e.Message.Usage; u != nil {
			t.used = u.Input + u.CacheCreate + u.CacheRead + u.Output
		}
		if e.Message.Model != "" && !strings.HasPrefix(e.Message.Model, "<") {
			t.model = e.Message.Model
		}
		if e.Effort != "" {
			t.effort = strings.ToLower(e.Effort)
		}
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
				t.add(chatMsg{ID: id, Role: "assistant", Text: strings.TrimSpace(b.Text)})
			case "thinking":
				t.add(chatMsg{ID: id, Role: "thinking", Text: strings.TrimSpace(b.Thinking)})
			case "tool_use":
				t.addTool(b.ID, b.Name, b.Input)
			}
		}
	}
}

var (
	claudeTag       = regexp.MustCompile(`(?s)<(command-name|command-message|command-args|local-command-stdout|local-command-stderr)>(.*?)</[a-z-]+>`)
	systemReminder  = regexp.MustCompile(`(?s)<system-reminder>.*?</system-reminder>`)
	recapHint       = regexp.MustCompile(`\s*\(disable recaps in /config\)\s*$`)
	ansiEscape      = regexp.MustCompile("\x1b\\[[0-?]*[ -/]*[@-~]|\x1b\\][^\x07\x1b]*(?:\x07|\x1b\\\\)|\x1b[()][0-9A-Za-z]|\x1b[=>78]")
	teammateMessage = regexp.MustCompile(`(?s)<teammate-message\b([^>]*)>(.*?)</teammate-message>`)
	xmlAttr         = regexp.MustCompile(`([a-zA-Z_][\w-]*)="([^"]*)"`)
	leadingTag      = regexp.MustCompile(`^\s*<([a-z][a-z0-9_-]*)[\s>]`)
	modelSet        = regexp.MustCompile("(?:Set model to|Kept model as) (?:`([^`]+)`|([^\\s<]+))")
	effortSet       = regexp.MustCompile(`Set effort level to ([A-Za-z]+)`)
)

func xmlField(s, tag string) string {
	m := regexp.MustCompile(`(?s)<` + tag + `>(.*?)</` + tag + `>`).FindStringSubmatch(s)
	if m == nil {
		return ""
	}
	return strings.TrimSpace(html.UnescapeString(m[1]))
}

// userText classifies what Claude records as a user turn: real prompts,
// slash commands and their output, teammate messages, background-job
// notifications, interrupts, or injected context that isn't shown.
func (t *transcript) userText(id, text string, images []string, queued bool) {
	text = strings.TrimSpace(systemReminder.ReplaceAllString(text, ""))
	if strings.Contains(text, "<teammate-message") {
		for i, m := range teammateMessage.FindAllStringSubmatch(text, -1) {
			attrs := map[string]string{}
			for _, a := range xmlAttr.FindAllStringSubmatch(m[1], -1) {
				attrs[a[1]] = a[2]
			}
			t.add(chatMsg{ID: fmt.Sprintf("%s.t%d", id, i), Role: "teammate", From: attrs["teammate_id"], Summary: attrs["summary"], Text: strings.TrimSpace(m[2])})
		}
		return
	}
	if claudeTag.MatchString(text) {
		parts := map[string]string{}
		for _, m := range claudeTag.FindAllStringSubmatch(text, -1) {
			parts[m[1]] = strings.TrimSpace(m[2])
		}
		if name := parts["command-name"]; name != "" {
			if !strings.HasPrefix(name, "/") {
				name = "/" + name
			}
			t.add(chatMsg{ID: id, Role: "command", Text: strings.TrimSpace(name + " " + parts["command-args"])})
		}
		out := strings.TrimSpace(ansiEscape.ReplaceAllString(parts["local-command-stdout"]+parts["local-command-stderr"], ""))
		if out != "" {
			if m := modelSet.FindStringSubmatch(out); m != nil {
				t.model = m[1] + m[2]
			}
			if m := effortSet.FindStringSubmatch(out); m != nil {
				t.effort = strings.ToLower(m[1])
			}
			t.add(chatMsg{ID: id + ".out", Role: "output", Text: out})
		}
		return
	}
	if strings.Contains(text, "<task-notification>") {
		m := chatMsg{ID: id, Role: "task", Text: xmlField(text, "summary"), Output: clip(xmlField(text, "result"), maxToolText)}
		if call := xmlField(text, "tool-use-id"); call != "" {
			if i, ok := t.tools[call]; ok {
				t.msgs[i].Background = false
				t.msgs[i].Pending = false
				if t.msgs[i].Output == "" {
					t.msgs[i].Output = m.Output
				}
			}
		}
		t.add(m)
		return
	}
	if strings.HasPrefix(text, "[Request interrupted") {
		t.add(chatMsg{ID: id, Role: "system", Kind: "interrupted"})
		return
	}
	if m := leadingTag.FindStringSubmatch(text); m != nil && m[1] != "pasted_content" && strings.Contains(text, "</"+m[1]+">") {
		return
	}
	t.add(chatMsg{ID: id, Role: "user", Text: text, Images: images, Queued: queued})
}

// queueOp mirrors Claude's queue of prompts typed while it works.
func (t *transcript) queueOp(op, content string) {
	switch op {
	case "enqueue":
		m := leadingTag.FindStringSubmatch(content)
		injected := m != nil && strings.Contains(content, "</"+m[1]+">")
		sum := sha1.Sum([]byte(fmt.Sprint(t.ts) + "\x00" + content))
		text := strings.TrimSpace(systemReminder.ReplaceAllString(content, ""))
		t.queue = append(t.queue, queuedPrompt{ID: hex.EncodeToString(sum[:8]), Text: text, human: !injected})
	case "dequeue":
		for i, q := range t.queue {
			if q.human {
				t.queue = append(t.queue[:i], t.queue[i+1:]...)
				return
			}
		}
		if len(t.queue) > 0 {
			t.queue = t.queue[1:]
		}
	case "remove":
		for i, q := range t.queue {
			if q.Text == strings.TrimSpace(systemReminder.ReplaceAllString(content, "")) {
				t.queue = append(t.queue[:i], t.queue[i+1:]...)
				return
			}
		}
	}
}

type imageSource struct {
	Type      string `json:"type"`
	MediaType string `json:"media_type"`
	Data      string `json:"data"`
}

func (t *transcript) storeImage(src *imageSource) string {
	if src == nil || src.Type != "base64" || t.images == nil {
		return ""
	}
	return t.images.put(src.Data)
}

// parts flattens a string or a list of text and image blocks.
func (t *transcript) parts(raw json.RawMessage) (string, []string) {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s, nil
	}
	var blocks []struct {
		Type     string       `json:"type"`
		Text     string       `json:"text"`
		Source   *imageSource `json:"source"`
		Data     string       `json:"data"`
		ImageURL string       `json:"image_url"`
	}
	json.Unmarshal(raw, &blocks)
	var texts, images []string
	for _, b := range blocks {
		switch b.Type {
		case "text", "input_text", "output_text":
			texts = append(texts, b.Text)
		case "image":
			ref := t.storeImage(b.Source)
			if ref == "" && b.Data != "" && t.images != nil {
				ref = t.images.put(b.Data)
			}
			if ref != "" {
				images = append(images, ref)
			}
		case "input_image":
			if ref := t.dataURL(b.ImageURL); ref != "" {
				images = append(images, ref)
			}
		}
	}
	return strings.Join(texts, "\n"), images
}

func (t *transcript) dataURL(u string) string {
	_, data, ok := strings.Cut(u, ";base64,")
	if !ok || !strings.HasPrefix(u, "data:image/") || t.images == nil {
		return ""
	}
	return t.images.put(data)
}

func rawString(raw json.RawMessage) string {
	var s string
	json.Unmarshal(raw, &s)
	return s
}

// ---- Codex: event_msg carries the chat, response_item carries tool calls ----

func (t *transcript) codexLine(line []byte) {
	var e struct {
		Type      string          `json:"type"`
		Timestamp json.RawMessage `json:"timestamp"`
		Payload   struct {
			Type      string          `json:"type"`
			Message   string          `json:"message"`
			Images    []string        `json:"images"`
			Text      string          `json:"text"`
			Name      string          `json:"name"`
			Arguments string          `json:"arguments"`
			Input     string          `json:"input"`
			CallID    string          `json:"call_id"`
			Output    json.RawMessage `json:"output"`
			Action    struct {
				Command []string `json:"command"`
			} `json:"action"`
			Model  string `json:"model"`
			Effort string `json:"effort"`
			Info   *struct {
				Last struct {
					Total int64 `json:"total_tokens"`
				} `json:"last_token_usage"`
				Window int64 `json:"model_context_window"`
			} `json:"info"`
		} `json:"payload"`
	}
	if json.Unmarshal(line, &e) != nil {
		return
	}
	p := e.Payload
	t.ts = parseTS(e.Timestamp)
	switch e.Type + "/" + p.Type {
	case "turn_context/":
		if p.Model != "" {
			t.model = p.Model
		}
		if p.Effort != "" {
			t.effort = p.Effort
		}
	case "event_msg/token_count":
		if p.Info != nil {
			t.used, t.window = p.Info.Last.Total, p.Info.Window
		}
	case "event_msg/user_message":
		var images []string
		for _, u := range p.Images {
			if ref := t.dataURL(u); ref != "" {
				images = append(images, ref)
			}
		}
		t.add(chatMsg{Role: "user", Text: p.Message, Images: images})
	case "event_msg/turn_aborted":
		t.add(chatMsg{Role: "system", Kind: "interrupted"})
	case "compacted/":
		t.add(chatMsg{Role: "system", Kind: "compacted"})
	case "event_msg/agent_message":
		t.add(chatMsg{Role: "assistant", Text: p.Message})
	case "event_msg/agent_reasoning":
		t.add(chatMsg{Role: "thinking", Text: p.Text})
	case "response_item/function_call":
		t.addTool(p.CallID, p.Name, []byte(p.Arguments))
	case "response_item/custom_tool_call":
		t.addTool(p.CallID, p.Name, []byte(p.Input))
	case "response_item/local_shell_call":
		args, _ := json.Marshal(map[string]any{"command": p.Action.Command})
		t.addTool(p.CallID, "shell", args)
	case "response_item/function_call_output", "response_item/custom_tool_call_output", "response_item/local_shell_call_output":
		out, failed := codexOutput(p.Output)
		t.toolResult(p.CallID, out, failed)
	}
}

var codexExit = regexp.MustCompile(`(?m)^Exit code: (\d+)`)

// codexOutput returns a call's output and whether the command failed.
func codexOutput(raw json.RawMessage) (string, bool) {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		// Older Codex wraps output as {"output": "...", "metadata": {"exit_code": n}} inside a string.
		var inner struct {
			Output   string `json:"output"`
			Metadata struct {
				ExitCode int `json:"exit_code"`
			} `json:"metadata"`
		}
		if strings.HasPrefix(s, "{") && json.Unmarshal([]byte(s), &inner) == nil && inner.Output != "" {
			return inner.Output, inner.Metadata.ExitCode != 0
		}
		m := codexExit.FindStringSubmatch(s)
		return s, m != nil && m[1] != "0"
	}
	var obj struct {
		Content json.RawMessage `json:"content"`
		Output  string          `json:"output"`
	}
	if json.Unmarshal(raw, &obj) == nil {
		if obj.Output != "" {
			return obj.Output, false
		}
		return contentText(obj.Content), false
	}
	return string(raw), false
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

func toolMsg(name string, args []byte) chatMsg {
	kind, summary, diff := describeTool(name, args)
	m := chatMsg{
		Role: "tool", Name: name, Input: clip(toolInput(args), maxToolText), Pending: true,
		Kind: kind, Summary: clip(firstLine(summary), 240), Diff: clip(diff, maxToolText),
	}
	switch kind {
	case "plan":
		m.Text = summary
		m.Summary = ""
	case "question":
		m.Text = questionText(args)
	case "shell":
		// Claude explains each command in a few words; that reads better than the command.
		var a struct {
			Description string `json:"description"`
		}
		json.Unmarshal(args, &a)
		m.Text = clip(strings.TrimSpace(a.Description), 240)
	case "task":
		var a struct {
			Type string `json:"subagent_type"`
		}
		json.Unmarshal(args, &a)
		m.Text = a.Type
	}
	return m
}

// questionText renders AskUserQuestion's questions and options as Markdown.
func questionText(args []byte) string {
	var a struct {
		Questions []struct {
			Question string `json:"question"`
			Header   string `json:"header"`
			Options  []struct {
				Label       string `json:"label"`
				Description string `json:"description"`
			} `json:"options"`
		} `json:"questions"`
	}
	if json.Unmarshal(args, &a) != nil {
		return ""
	}
	var b strings.Builder
	for i, q := range a.Questions {
		if i > 0 {
			b.WriteString("\n\n")
		}
		b.WriteString("**" + q.Question + "**")
		for _, o := range q.Options {
			b.WriteString("\n- " + o.Label)
			if o.Description != "" {
				b.WriteString(" — " + o.Description)
			}
		}
	}
	return b.String()
}

// parseTodos reads the task list Claude Code (TodoWrite) and Codex
// (update_plan) rewrite in full on every call.
func parseTodos(name string, args []byte) ([]todoItem, bool) {
	var a struct {
		Todos []struct {
			Content, Status string
		} `json:"todos"`
		Plan []struct {
			Step, Status string
		} `json:"plan"`
	}
	switch strings.ToLower(name) {
	case "todowrite":
		if json.Unmarshal(args, &a) != nil {
			return nil, false
		}
		out := []todoItem{}
		for _, x := range a.Todos {
			out = append(out, todoItem{x.Content, x.Status})
		}
		return out, true
	case "update_plan":
		if json.Unmarshal(args, &a) != nil {
			return nil, false
		}
		out := []todoItem{}
		for _, x := range a.Plan {
			out = append(out, todoItem{x.Step, x.Status})
		}
		return out, true
	}
	return nil, false
}

// describeTool maps the tool names of Claude Code, Codex and pi onto a few
// kinds the app can label, picking the argument that identifies the call.
func describeTool(name string, raw []byte) (kind, summary, diff string) {
	var a map[string]any
	if json.Unmarshal(raw, &a) != nil {
		a = nil
	}
	str := func(keys ...string) string {
		for _, k := range keys {
			switch v := a[k].(type) {
			case string:
				if v != "" {
					return v
				}
			case []any:
				return shellArgs(v)
			}
		}
		return ""
	}
	switch strings.ToLower(name) {
	case "bash", "shell", "local_shell", "exec_command", "shell_command", "bashoutput", "killshell", "write_stdin":
		return "shell", str("command", "cmd", "bash_id", "shell_id"), ""
	case "read", "ls", "view_image", "notebookread":
		return "read", str("file_path", "path", "notebook_path"), ""
	case "edit", "multiedit", "notebookedit":
		path := str("file_path", "path", "notebook_path")
		if edits, ok := a["edits"].([]any); ok {
			var parts []string
			for _, e := range edits {
				if m, ok := e.(map[string]any); ok {
					parts = append(parts, lineDiff(strAny(m, "old_string", "oldText"), strAny(m, "new_string", "newText")))
				}
			}
			return "edit", path, strings.Join(parts, "@@\n")
		}
		return "edit", path, lineDiff(str("old_string", "oldText"), str("new_string", "newText", "new_source"))
	case "write":
		return "write", str("file_path", "path"), lineDiff("", str("content"))
	case "apply_patch":
		patch := str("input", "patch")
		if a == nil {
			patch = string(raw)
		}
		return "edit", patchFiles(patch), patch
	case "grep", "glob", "find", "search", "websearch", "web_search":
		return "search", str("pattern", "query"), ""
	case "webfetch", "fetch":
		return "fetch", str("url"), ""
	case "task", "agent":
		return "task", str("description", "prompt"), ""
	case "todowrite", "update_plan":
		return "todo", "", ""
	case "exitplanmode":
		return "plan", str("plan"), ""
	case "askuserquestion":
		if qs, ok := a["questions"].([]any); ok && len(qs) > 0 {
			if q, ok := qs[0].(map[string]any); ok {
				return "question", strAny(q, "question"), ""
			}
		}
		return "question", "", ""
	}
	return "other", str("command", "file_path", "path", "pattern", "url", "query", "description"), ""
}

func strAny(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if s, ok := m[k].(string); ok && s != "" {
			return s
		}
	}
	return ""
}

// shellArgs shows ["bash","-lc","cmd"] as just cmd.
func shellArgs(v []any) string {
	var p []string
	for _, x := range v {
		p = append(p, fmt.Sprint(x))
	}
	if len(p) == 3 && (p[1] == "-lc" || p[1] == "-c") {
		return p[2]
	}
	return strings.Join(p, " ")
}

// lineDiff renders a replacement as +/- lines, keeping shared leading and
// trailing lines as context.
func lineDiff(old, new string) string {
	if old == "" && new == "" {
		return ""
	}
	var a, b []string
	if old != "" {
		a = strings.Split(old, "\n")
	}
	if new != "" {
		b = strings.Split(new, "\n")
	}
	pre := 0
	for pre < len(a) && pre < len(b) && a[pre] == b[pre] {
		pre++
	}
	suf := 0
	for suf < len(a)-pre && suf < len(b)-pre && a[len(a)-1-suf] == b[len(b)-1-suf] {
		suf++
	}
	var out strings.Builder
	for _, l := range a[:pre] {
		out.WriteString(" " + l + "\n")
	}
	for _, l := range a[pre : len(a)-suf] {
		out.WriteString("-" + l + "\n")
	}
	for _, l := range b[pre : len(b)-suf] {
		out.WriteString("+" + l + "\n")
	}
	for _, l := range a[len(a)-suf:] {
		out.WriteString(" " + l + "\n")
	}
	return strings.TrimSuffix(out.String(), "\n")
}

var patchFile = regexp.MustCompile(`(?m)^\*\*\* (?:Update|Add|Delete) File: (.+)$`)

func patchFiles(patch string) string {
	var files []string
	for _, m := range patchFile.FindAllStringSubmatch(patch, -1) {
		files = append(files, strings.TrimSpace(m[1]))
	}
	return strings.Join(files, ", ")
}

func firstLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i] + " …"
	}
	return s
}

// parseTS accepts RFC 3339 strings and unix seconds or milliseconds.
func parseTS(raw json.RawMessage) int64 {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
			return t.UnixMilli()
		}
		return 0
	}
	var n float64
	if json.Unmarshal(raw, &n) == nil && n > 0 {
		if n < 1e12 {
			n *= 1000
		}
		return int64(n)
	}
	return 0
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
