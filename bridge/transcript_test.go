package tsbridge

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

func newTranscript(kind string) *transcript {
	return &transcript{
		file: "session", kind: kind,
		tools: map[string]int{}, piByID: map[string]int{}, images: newImageStore(),
	}
}

type view struct {
	Mode      string     `json:"mode"`
	Truncated bool       `json:"truncated"`
	Messages  []chatMsg  `json:"messages"`
	Todos     []todoItem `json:"todos"`
	Model     string     `json:"model"`
	Context   struct {
		Used   int64 `json:"used"`
		Window int64 `json:"window"`
	} `json:"context"`
	Queued   []queuedPrompt    `json:"queued"`
	Settings map[string]string `json:"settings"`
}

func renderView(t *testing.T, tr *transcript) view {
	t.Helper()
	raw, err := tr.render("session")
	if err != nil {
		t.Fatal(err)
	}
	var v view
	if err := json.Unmarshal([]byte(raw), &v); err != nil {
		t.Fatalf("render output is not JSON: %v\n%s", err, raw)
	}
	return v
}

func jsonl(lines ...string) []byte {
	return []byte(strings.Join(lines, "\n") + "\n")
}

func unixMilli(s string) int64 {
	ts, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return ts.UnixMilli()
}

const claudeTurn = `{"type":"user","uuid":"u1","timestamp":"2026-11-26T01:00:00Z","cwd":"/work/app","gitBranch":"main","permissionMode":"acceptEdits","message":{"role":"user","content":"fix the login bug"}}
{"type":"assistant","uuid":"a1","timestamp":"2026-11-26T01:00:05Z","message":{"role":"assistant","model":"claude-opus-5-5","usage":{"input_tokens":10,"cache_creation_input_tokens":20,"cache_read_input_tokens":30,"output_tokens":5},"content":[{"type":"thinking","thinking":" check the session "},{"type":"text","text":"Looking now."},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"go test ./...","description":"Run tests"}}]}}
{"type":"user","uuid":"u2","timestamp":"2026-11-26T01:00:09Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"ok"}]}}`

func TestClaudeTurnRendersMessagesToolsAndSettings(t *testing.T) {
	tr := newTranscript("claude")
	tr.feed(jsonl(claudeTurn))
	v := renderView(t, tr)

	if got := len(v.Messages); got != 4 {
		t.Fatalf("got %d messages, want 4: %+v", got, v.Messages)
	}
	user, thinking, reply, tool := v.Messages[0], v.Messages[1], v.Messages[2], v.Messages[3]
	if user.Role != "user" || user.Text != "fix the login bug" {
		t.Fatalf("user = %+v", user)
	}
	if user.TS != unixMilli("2026-11-26T01:00:00Z") {
		t.Fatalf("user ts = %d", user.TS)
	}
	if thinking.Role != "thinking" || thinking.Text != "check the session" {
		t.Fatalf("thinking = %+v", thinking)
	}
	if reply.Role != "assistant" || reply.Text != "Looking now." {
		t.Fatalf("reply = %+v", reply)
	}
	if tool.Role != "tool" || tool.Kind != "shell" || tool.Name != "Bash" || tool.Call != "toolu_1" {
		t.Fatalf("tool = %+v", tool)
	}
	if tool.Summary != "go test ./..." || tool.Text != "Run tests" {
		t.Fatalf("tool summary/text = %q / %q", tool.Summary, tool.Text)
	}
	if tool.Output != "ok" || tool.Pending || tool.Error {
		t.Fatalf("tool result not attached: %+v", tool)
	}
	if v.Context.Used != 65 {
		t.Fatalf("context used = %d, want 10+20+30+5", v.Context.Used)
	}
	if v.Model != "claude-opus-5-5" {
		t.Fatalf("model = %q", v.Model)
	}
	want := map[string]string{"permission_mode": "acceptEdits", "cwd": "/work/app", "git_branch": "main"}
	for k, w := range want {
		if got := v.Settings[k]; got != w {
			t.Fatalf("settings[%s] = %q, want %q", k, got, w)
		}
	}
}

func TestClaudeToolWithoutResultIsPending(t *testing.T) {
	tr := newTranscript("claude")
	tr.feed(jsonl(`{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/w/a.go"}}]}}`))
	m := renderView(t, tr).Messages
	if len(m) != 1 || !m[0].Pending || m[0].Kind != "read" || m[0].Summary != "/w/a.go" {
		t.Fatalf("messages = %+v", m)
	}
}

func TestClaudeHidesInternalLines(t *testing.T) {
	tr := newTranscript("claude")
	tr.feed(jsonl(
		`{"type":"user","uuid":"m1","isMeta":true,"message":{"role":"user","content":"hidden caveat"}}`,
		`{"type":"user","uuid":"v1","isVisibleInTranscriptOnly":true,"message":{"role":"user","content":"not for you"}}`,
		`{"type":"assistant","uuid":"s1","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"subagent chatter"}]}}`,
		`{"type":"assistant","uuid":"b1","message":{"role":"assistant","content":[{"type":"text","text":"   "}]}}`,
		`{"type":"user","uuid":"r1","message":{"role":"user","content":"<system-reminder>context</system-reminder>"}}`,
		`{"type":"user","uuid":"ok1","message":{"role":"user","content":"visible prompt"}}`,
	))
	m := renderView(t, tr).Messages
	if len(m) != 1 || m[0].Text != "visible prompt" {
		t.Fatalf("messages = %+v", m)
	}
}

func TestClaudeSubagentTranscriptKeepsSidechain(t *testing.T) {
	tr := newTranscript("claude")
	tr.sidechain = true
	tr.feed(jsonl(`{"type":"assistant","uuid":"s1","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"searching"}]}}`))
	if m := renderView(t, tr).Messages; len(m) != 1 || m[0].Text != "searching" {
		t.Fatalf("messages = %+v", m)
	}
}

func TestClaudeQueueHoldsOnlyHumanPrompts(t *testing.T) {
	tr := newTranscript("claude")
	tr.feed(jsonl(
		`{"type":"queue-operation","operation":"enqueue","content":"also run lint"}`,
		`{"type":"queue-operation","operation":"enqueue","content":"<task-reminder>internal</task-reminder>"}`,
	))
	q := renderView(t, tr).Queued
	if len(q) != 1 || q[0].Text != "also run lint" {
		t.Fatalf("queued = %+v", q)
	}
	tr.feed(jsonl(`{"type":"queue-operation","operation":"dequeue"}`))
	if q := renderView(t, tr).Queued; q == nil || len(q) != 0 {
		t.Fatalf("after dequeue queued = %#v", q)
	}
}

func TestClaudeQueuedCommandBecomesQueuedUserMessage(t *testing.T) {
	tr := newTranscript("claude")
	tr.feed(jsonl(`{"type":"attachment","uuid":"q1","attachment":{"type":"queued_command","prompt":"stop after this"}}`))
	m := renderView(t, tr).Messages
	if len(m) != 1 || m[0].Role != "user" || !m[0].Queued || m[0].Text != "stop after this" {
		t.Fatalf("messages = %+v", m)
	}
}

func TestClaudeAsyncTaskFinishesWithNotification(t *testing.T) {
	tr := newTranscript("claude")
	tr.feed(jsonl(
		`{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"tool_use","id":"task1","name":"Task","input":{"description":"Scan repo","subagent_type":"Explore"}}]}}`,
		`{"type":"user","uuid":"u1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"task1","content":"started"}]},"toolUseResult":{"isAsync":true}}`,
	))
	m := renderView(t, tr).Messages
	if len(m) != 1 || m[0].Kind != "task" || m[0].Text != "Explore" || !m[0].Background || !m[0].Pending {
		t.Fatalf("while running: %+v", m)
	}

	tr.feed(jsonl(`{"type":"user","uuid":"n1","message":{"role":"user","content":"<task-notification><tool-use-id>task1</tool-use-id><summary>Scan finished</summary><result>3 files</result></task-notification>"}}`))
	m = renderView(t, tr).Messages
	if len(m) != 2 {
		t.Fatalf("after notification: %+v", m)
	}
	tool, done := m[0], m[1]
	if tool.Background || tool.Pending || tool.Output != "3 files" {
		t.Fatalf("tool after notification = %+v", tool)
	}
	if done.Role != "task" || done.Text != "Scan finished" {
		t.Fatalf("notification message = %+v", done)
	}
}

func TestClaudeUserTextClassification(t *testing.T) {
	cases := []struct {
		name, text, role, want string
	}{
		{"plain prompt", "plain prompt", "user", "plain prompt"},
		{"interrupt", "[Request interrupted by user]", "system", ""},
		{"ide context is hidden", "<ide_selection>file.go</ide_selection>", "", ""},
		{"pasted content stays", "<pasted_content>log</pasted_content> read this", "user", "<pasted_content>log</pasted_content> read this"},
		{"slash command", "<command-name>/clear</command-name><command-message>clear</command-message><command-args></command-args>", "command", "/clear"},
		{"command without slash", "<command-name>model</command-name><command-args>opus</command-args>", "command", "/model opus"},
		{"command output loses ANSI", "<local-command-stdout>\x1b[32mdone\x1b[0m</local-command-stdout>", "output", "done"},
		{"task notification", "<task-notification><summary>Explore done</summary><result>found 3</result></task-notification>", "task", "Explore done"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			tr := newTranscript("claude")
			tr.userText("id", c.text, nil, false)
			if c.role == "" {
				if len(tr.msgs) != 0 {
					t.Fatalf("want nothing, got %+v", tr.msgs)
				}
				return
			}
			if len(tr.msgs) != 1 || tr.msgs[0].Role != c.role || tr.msgs[0].Text != c.want {
				t.Fatalf("got %+v, want role %q text %q", tr.msgs, c.role, c.want)
			}
		})
	}
}

func TestClaudeTeammateMessageKeepsSender(t *testing.T) {
	tr := newTranscript("claude")
	tr.userText("id", `<teammate-message teammate_id="w1" summary="tests pass">All green</teammate-message>`, nil, false)
	m := tr.msgs
	if len(m) != 1 || m[0].Role != "teammate" || m[0].From != "w1" || m[0].Summary != "tests pass" || m[0].Text != "All green" {
		t.Fatalf("messages = %+v", m)
	}
}

func TestClaudeModelSwitchFromCommandOutput(t *testing.T) {
	tr := newTranscript("claude")
	tr.feed(jsonl(`{"type":"system","subtype":"local_command","uuid":"o1","content":"<local-command-stdout>Set model to ` + "`opus`" + `</local-command-stdout>"}`))
	if v := renderView(t, tr); v.Model != "opus" {
		t.Fatalf("model = %q", v.Model)
	}
}

func TestFeedWaitsForCompleteLinesAndSkipsGarbage(t *testing.T) {
	tr := newTranscript("claude")
	line := `{"type":"user","uuid":"u1","message":{"role":"user","content":"split"}}`
	tr.feed([]byte(line[:20]))
	if len(tr.msgs) != 0 {
		t.Fatal("partial line was parsed")
	}
	tr.feed([]byte(line[20:] + "\n{not json\n"))
	if len(tr.msgs) != 1 || tr.msgs[0].Text != "split" {
		t.Fatalf("messages = %+v", tr.msgs)
	}
}

func TestRenderKeepsNewestMessages(t *testing.T) {
	tr := newTranscript("claude")
	tr.limit = 2
	tr.feed(jsonl(
		`{"type":"user","uuid":"1","message":{"role":"user","content":"one"}}`,
		`{"type":"user","uuid":"2","message":{"role":"user","content":"two"}}`,
		`{"type":"user","uuid":"3","message":{"role":"user","content":"three"}}`,
	))
	v := renderView(t, tr)
	if !v.Truncated || len(v.Messages) != 2 || v.Messages[0].Text != "two" || v.Messages[1].Text != "three" {
		t.Fatalf("view = %+v", v)
	}
}

func TestEmptyTranscriptRendersEmptyLists(t *testing.T) {
	raw, err := newTranscript("claude").render("session")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"messages":[]`, `"queued":[]`} {
		if !strings.Contains(raw, want) {
			t.Fatalf("%s missing from %s", want, raw)
		}
	}
}

const piTree = `{"type":"model_change","id":"m1","provider":"anthropic","modelId":"claude-x"}
{"type":"thinking_level_change","id":"t1","parentId":"m1","thinkingLevel":"high"}
{"type":"message","id":"u1","parentId":"t1","timestamp":"2026-11-26T02:00:00Z","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}
{"type":"message","id":"a1","parentId":"u1","message":{"role":"assistant","model":"gpt-x","usage":{"totalTokens":42},"content":[{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"ls"}}]}}
{"type":"message","id":"r1","parentId":"a1","message":{"role":"toolResult","toolCallId":"c1","content":[{"type":"text","text":"file.txt"}]}}
{"type":"message","id":"a2","parentId":"r1","message":{"role":"assistant","model":"gpt-y","usage":{"input":5,"output":6},"content":[{"type":"text","text":"done"}]}}`

func TestPiPathFollowsNewestEntry(t *testing.T) {
	tr := newTranscript("pi")
	tr.feed(jsonl(strings.Split(piTree, "\n")...))
	v := renderView(t, tr)

	if len(v.Messages) != 3 {
		t.Fatalf("messages = %+v", v.Messages)
	}
	if v.Messages[0].Role != "user" || v.Messages[0].Text != "hi" {
		t.Fatalf("user = %+v", v.Messages[0])
	}
	tool := v.Messages[1]
	if tool.Kind != "shell" || tool.Summary != "ls" || tool.Output != "file.txt" || tool.Pending {
		t.Fatalf("tool = %+v", tool)
	}
	if v.Messages[2].Role != "assistant" || v.Messages[2].Text != "done" {
		t.Fatalf("reply = %+v", v.Messages[2])
	}
	if v.Model != "anthropic/claude-x" {
		t.Fatalf("model = %q, want the model_change choice", v.Model)
	}
	if v.Context.Used != 11 {
		t.Fatalf("context used = %d, want the newest entry's 5+6", v.Context.Used)
	}
	if v.Settings["effort"] != "high" {
		t.Fatalf("effort = %q", v.Settings["effort"])
	}
}

func TestPiBranchReplacesPathToNewestEntry(t *testing.T) {
	tr := newTranscript("pi")
	tr.feed(jsonl(strings.Split(piTree, "\n")...))
	tr.feed(jsonl(`{"type":"message","id":"u2","parentId":"u1","message":{"role":"user","content":"other branch"}}`))
	var texts []string
	for _, m := range renderView(t, tr).Messages {
		texts = append(texts, m.Text)
	}
	if strings.Join(texts, "|") != "hi|other branch" {
		t.Fatalf("texts = %q", texts)
	}
}

func TestCodexTurnRendersShellCallsAndUsage(t *testing.T) {
	tr := newTranscript("codex")
	tr.feed(jsonl(
		`{"type":"turn_context","payload":{"model":"gpt-5-codex","effort":"medium"}}`,
		`{"type":"event_msg","timestamp":"2026-11-26T03:00:00Z","payload":{"type":"user_message","message":"list files"}}`,
		`{"type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{\"command\":[\"bash\",\"-lc\",\"ls -la\"]}","call_id":"call_1"}}`,
		`{"type":"response_item","payload":{"type":"function_call_output","call_id":"call_1","output":"total 0"}}`,
		`{"type":"response_item","payload":{"type":"local_shell_call","call_id":"call_2","action":{"command":["false"]}}}`,
		`{"type":"response_item","payload":{"type":"local_shell_call_output","call_id":"call_2","output":"Exit code: 1\nboom"}}`,
		`{"type":"event_msg","payload":{"type":"agent_message","message":"Listed."}}`,
		`{"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"total_tokens":1234},"model_context_window":200000}}}`,
	))
	v := renderView(t, tr)
	if len(v.Messages) != 4 {
		t.Fatalf("messages = %+v", v.Messages)
	}
	ls, fail := v.Messages[1], v.Messages[2]
	if ls.Summary != "ls -la" || ls.Output != "total 0" || ls.Error || ls.Pending {
		t.Fatalf("ls = %+v", ls)
	}
	if fail.Summary != "false" || !fail.Error || !strings.Contains(fail.Output, "boom") {
		t.Fatalf("failed call = %+v", fail)
	}
	if v.Messages[3].Role != "assistant" || v.Messages[3].Text != "Listed." {
		t.Fatalf("reply = %+v", v.Messages[3])
	}
	if v.Model != "gpt-5-codex" || v.Settings["effort"] != "medium" {
		t.Fatalf("model %q effort %q", v.Model, v.Settings["effort"])
	}
	if v.Context.Used != 1234 || v.Context.Window != 200000 {
		t.Fatalf("context = %+v", v.Context)
	}
}

func TestCodexOutputFormats(t *testing.T) {
	cases := []struct {
		raw        string
		wantOut    string
		wantFailed bool
	}{
		{`"ok"`, "ok", false},
		{`"Exit code: 2\nboom"`, "Exit code: 2\nboom", true},
		{`"Exit code: 0\nfine"`, "Exit code: 0\nfine", false},
		{`"{\"output\":\"hi\",\"metadata\":{\"exit_code\":3}}"`, "hi", true},
		{`{"output":"x"}`, "x", false},
		{`{"content":[{"type":"text","text":"a"},{"type":"image"}]}`, "a\n[image]", false},
	}
	for _, c := range cases {
		out, failed := codexOutput(json.RawMessage(c.raw))
		if out != c.wantOut || failed != c.wantFailed {
			t.Fatalf("codexOutput(%s) = %q, %v; want %q, %v", c.raw, out, failed, c.wantOut, c.wantFailed)
		}
	}
}

func TestDescribeToolKinds(t *testing.T) {
	cases := []struct {
		name, args, kind, summary, diff string
	}{
		{"Bash", `{"command":"go test ./..."}`, "shell", "go test ./...", ""},
		{"bash", `{"command":["bash","-lc","make build"]}`, "shell", "make build", ""},
		{"Read", `{"file_path":"/w/a.go"}`, "read", "/w/a.go", ""},
		{"Edit", `{"file_path":"a.go","old_string":"x\ny","new_string":"x\nz"}`, "edit", "a.go", " x\n-y\n+z"},
		{"MultiEdit", `{"file_path":"a.go","edits":[{"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"d"}]}`, "edit", "a.go", "-a\n+b@@\n-c\n+d"},
		{"Write", `{"file_path":"n.txt","content":"hi"}`, "write", "n.txt", "+hi"},
		{"Grep", `{"pattern":"TODO"}`, "search", "TODO", ""},
		{"WebFetch", `{"url":"https://example.com"}`, "fetch", "https://example.com", ""},
		{"Task", `{"description":"Scan repo","subagent_type":"Explore"}`, "task", "Scan repo", ""},
		{"TodoWrite", `{"todos":[]}`, "todo", "", ""},
		{"ExitPlanMode", `{"plan":"1. read"}`, "plan", "1. read", ""},
		{"AskUserQuestion", `{"questions":[{"question":"Which db?"}]}`, "question", "Which db?", ""},
		{"Frobnicate", `{"path":"p"}`, "other", "p", ""},
	}
	for _, c := range cases {
		kind, summary, diff := describeTool(c.name, []byte(c.args))
		if kind != c.kind || summary != c.summary || diff != c.diff {
			t.Fatalf("describeTool(%s) = %q, %q, %q; want %q, %q, %q",
				c.name, kind, summary, diff, c.kind, c.summary, c.diff)
		}
	}
}

func TestApplyPatchListsFiles(t *testing.T) {
	patch := "*** Begin Patch\n*** Update File: a.go\n*** Add File: b.go\n*** End Patch"
	kind, summary, diff := describeTool("apply_patch", []byte(patch))
	if kind != "edit" || summary != "a.go, b.go" || diff != patch {
		t.Fatalf("got %q, %q, %q", kind, summary, diff)
	}
}

func TestLineDiff(t *testing.T) {
	cases := []struct{ old, new, want string }{
		{"", "", ""},
		{"a\nb", "a\nc", " a\n-b\n+c"},
		{"", "new", "+new"},
		{"old", "", "-old"},
		{"same", "same", " same"},
	}
	for _, c := range cases {
		if got := lineDiff(c.old, c.new); got != c.want {
			t.Fatalf("lineDiff(%q, %q) = %q, want %q", c.old, c.new, got, c.want)
		}
	}
}

func TestParseTodos(t *testing.T) {
	todos, ok := parseTodos("TodoWrite", []byte(`{"todos":[{"content":"a","status":"completed"},{"content":"b","status":"in_progress"}]}`))
	if !ok || len(todos) != 2 || todos[0] != (todoItem{"a", "completed"}) || todos[1] != (todoItem{"b", "in_progress"}) {
		t.Fatalf("TodoWrite = %+v, %v", todos, ok)
	}
	plan, ok := parseTodos("update_plan", []byte(`{"plan":[{"step":"read","status":"pending"}]}`))
	if !ok || len(plan) != 1 || plan[0] != (todoItem{"read", "pending"}) {
		t.Fatalf("update_plan = %+v, %v", plan, ok)
	}
	if _, ok := parseTodos("Bash", []byte(`{"todos":[]}`)); ok {
		t.Fatal("non-todo tool parsed as todos")
	}
	if _, ok := parseTodos("TodoWrite", []byte(`{not json`)); ok {
		t.Fatal("invalid JSON parsed as todos")
	}
}

func TestQuestionTextRendersOptions(t *testing.T) {
	got := questionText([]byte(`{"questions":[{"question":"Which db?","options":[{"label":"pg","description":"hosted"},{"label":"sqlite"}]}]}`))
	want := "**Which db?**\n- pg — hosted\n- sqlite"
	if got != want {
		t.Fatalf("questionText = %q, want %q", got, want)
	}
}

func TestParseTSAcceptsRFC3339AndUnixTimes(t *testing.T) {
	cases := map[string]int64{
		`"2026-11-26T01:00:00Z"`: unixMilli("2026-11-26T01:00:00Z"),
		`1700000000`:             1700000000000,
		`1700000000123`:          1700000000123,
		`"yesterday"`:            0,
		`0`:                      0,
	}
	for raw, want := range cases {
		if got := parseTS(json.RawMessage(raw)); got != want {
			t.Fatalf("parseTS(%s) = %d, want %d", raw, got, want)
		}
	}
}

func TestClipNeverSplitsRunes(t *testing.T) {
	if got := clip("abc", 5); got != "abc" {
		t.Fatalf("clip(abc, 5) = %q", got)
	}
	if got := clip("abcdef", 3); got != "abc\n…" {
		t.Fatalf("clip(abcdef, 3) = %q", got)
	}
	got := clip("héllo", 2)
	if got != "h\n…" || !utf8.ValidString(got) {
		t.Fatalf("clip(héllo, 2) = %q", got)
	}
}

func TestFirstLine(t *testing.T) {
	if got := firstLine("  x  "); got != "x" {
		t.Fatalf("firstLine = %q", got)
	}
	if got := firstLine("a\nb"); got != "a …" {
		t.Fatalf("firstLine(multi) = %q", got)
	}
}

func TestToolInputShowsCommandFirst(t *testing.T) {
	if got := toolInput(json.RawMessage(`{"command":"ls"}`)); got != "ls" {
		t.Fatalf("toolInput = %q", got)
	}
	if got := toolInput(json.RawMessage(`{"command":"ls","timeout":5}`)); !strings.HasPrefix(got, "ls\n") {
		t.Fatalf("toolInput with extra args = %q", got)
	}
}

func TestXMLFieldUnescapes(t *testing.T) {
	if got := xmlField("<a>x &amp; y</a>", "a"); got != "x & y" {
		t.Fatalf("xmlField = %q", got)
	}
	if got := xmlField("<a>x</a>", "b"); got != "" {
		t.Fatalf("missing field = %q", got)
	}
}
