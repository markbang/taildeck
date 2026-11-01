// JNI bridge between the Kotlin TerminalView and libghostty-vt.
// Every entry point takes the per-terminal mutex: SSH output arrives on Go
// callback threads while rendering and input happen on the UI thread.

#include <android/keycodes.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <ghostty/vt.h>

typedef struct {
  GhosttyTerminal term;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  GhosttyKeyEncoder keys;
  GhosttyKeyEvent key_event;
  GhosttyMouseEncoder mouse;
  GhosttyMouseEvent mouse_event;
  pthread_mutex_t mu;
  // Responses the terminal wants sent back to the remote (DA, DSR, ...).
  uint8_t* out;
  size_t out_len;
  size_t out_cap;
  int title_changed;
  int bell;
  // Latest OSC 52 clipboard write, handed to Kotlin after vt_write.
  uint8_t* clip;
  size_t clip_len;
  int clip_pending;
} Term;

static void out_append(Term* t, const uint8_t* data, size_t len) {
  if (t->out_len + len > t->out_cap) {
    size_t cap = t->out_cap ? t->out_cap * 2 : 256;
    while (cap < t->out_len + len) cap *= 2;
    uint8_t* p = realloc(t->out, cap);
    if (!p) return;
    t->out = p;
    t->out_cap = cap;
  }
  memcpy(t->out + t->out_len, data, len);
  t->out_len += len;
}

static void on_write_pty(GhosttyTerminal term, void* ud, const uint8_t* data, size_t len) {
  (void)term;
  out_append((Term*)ud, data, len);
}

static void on_title(GhosttyTerminal term, void* ud) {
  (void)term;
  ((Term*)ud)->title_changed = 1;
}

static void on_bell(GhosttyTerminal term, void* ud) {
  (void)term;
  ((Term*)ud)->bell = 1;
}

static void on_clipboard_write(GhosttyTerminal term, void* ud, const GhosttyClipboardWrite* w) {
  (void)term;
  Term* t = (Term*)ud;
  GhosttyClipboardWriteReply reply = {.size = sizeof(reply), .result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS, .remember = false};
  const GhosttyClipboardContent* text = NULL;
  for (size_t i = 0; i < w->contents_len; i++) {
    const GhosttyClipboardContent* c = &w->contents[i];
    if (c->mime.len == 0 || (c->mime.len >= 4 && memcmp(c->mime.ptr, "text", 4) == 0)) {
      text = c;
      break;
    }
  }
  // Phones have one clipboard; selection/primary writes land there too.
  if (text) {
    uint8_t* p = realloc(t->clip, text->data.len + 1);
    if (p) {
      memcpy(p, text->data.ptr, text->data.len);
      t->clip = p;
      t->clip_len = text->data.len;
      t->clip_pending = 1;
    }
  } else {
    reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
  }
  w->reply(w, &reply);
}

static jbyteArray take_out(JNIEnv* env, Term* t) {
  if (t->out_len == 0) return NULL;
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)t->out_len);
  if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)t->out_len, (const jbyte*)t->out);
  t->out_len = 0;
  return arr;
}

static jbyteArray bytes(JNIEnv* env, const char* buf, size_t len) {
  if (len == 0) return NULL;
  jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
  if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte*)buf);
  return arr;
}

#define T ((Term*)(intptr_t)handle)
#define FN(name) Java_top_bangwu_taildeck_term_GhosttyNative_##name

static GhosttyColorRgb to_rgb(jint c) {
  return (GhosttyColorRgb){.r = (uint8_t)(c >> 16), .g = (uint8_t)(c >> 8), .b = (uint8_t)c};
}

JNIEXPORT jlong JNICALL FN(create)(JNIEnv* env, jclass cls, jint cols, jint rows, jint scrollback, jint fg, jint bg, jint cur) {
  (void)env;
  (void)cls;
  Term* t = calloc(1, sizeof(Term));
  if (!t) return 0;
  if (ghostty_terminal_new(NULL, &t->term, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS) {
    free(t);
    return 0;
  }
  ghostty_render_state_new(NULL, &t->rs);
  ghostty_render_state_row_iterator_new(NULL, &t->rows);
  ghostty_render_state_row_cells_new(NULL, &t->cells);
  ghostty_key_encoder_new(NULL, &t->keys);
  ghostty_key_event_new(NULL, &t->key_event);
  ghostty_mouse_encoder_new(NULL, &t->mouse);
  ghostty_mouse_event_new(NULL, &t->mouse_event);
  pthread_mutex_init(&t->mu, NULL);

  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void*)on_write_pty);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, (const void*)on_title);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_BELL, (const void*)on_bell);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, (const void*)on_clipboard_write);
  size_t lines = (size_t)scrollback;
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
  GhosttyColorRgb cfg = to_rgb(fg), cbg = to_rgb(bg), ccur = to_rgb(cur);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &cfg);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &cbg);
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &ccur);
  return (jlong)(intptr_t)t;
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv* env, jclass cls, jlong handle) {
  (void)env;
  (void)cls;
  Term* t = T;
  if (!t) return;
  pthread_mutex_lock(&t->mu);
  ghostty_mouse_event_free(t->mouse_event);
  ghostty_mouse_encoder_free(t->mouse);
  ghostty_key_event_free(t->key_event);
  ghostty_key_encoder_free(t->keys);
  ghostty_render_state_row_cells_free(t->cells);
  ghostty_render_state_row_iterator_free(t->rows);
  ghostty_render_state_free(t->rs);
  ghostty_terminal_free(t->term);
  free(t->out);
  free(t->clip);
  pthread_mutex_unlock(&t->mu);
  pthread_mutex_destroy(&t->mu);
  free(t);
}

// Feeds remote output and returns any response bytes to send back.
JNIEXPORT jbyteArray JNICALL FN(write)(JNIEnv* env, jclass cls, jlong handle, jbyteArray data, jint len) {
  (void)cls;
  Term* t = T;
  jbyte* p = (*env)->GetByteArrayElements(env, data, NULL);
  if (!p) return NULL;
  pthread_mutex_lock(&t->mu);
  ghostty_terminal_vt_write(t->term, (const uint8_t*)p, (size_t)len);
  jbyteArray out = take_out(env, t);
  pthread_mutex_unlock(&t->mu);
  (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
  return out;
}

JNIEXPORT jbyteArray JNICALL FN(resize)(JNIEnv* env, jclass cls, jlong handle, jint cols, jint rows, jint cw, jint ch) {
  (void)cls;
  Term* t = T;
  pthread_mutex_lock(&t->mu);
  ghostty_terminal_resize(t->term, (uint16_t)cols, (uint16_t)rows, (uint32_t)cw, (uint32_t)ch);
  jbyteArray out = take_out(env, t);
  pthread_mutex_unlock(&t->mu);
  return out;
}

JNIEXPORT void JNICALL FN(scroll)(JNIEnv* env, jclass cls, jlong handle, jint delta) {
  (void)env;
  (void)cls;
  Term* t = T;
  GhosttyTerminalScrollViewport s = {0};
  if (delta == 0) {
    s.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
  } else {
    s.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
    s.value.delta = delta;
  }
  pthread_mutex_lock(&t->mu);
  ghostty_terminal_scroll_viewport(t->term, s);
  pthread_mutex_unlock(&t->mu);
}

// Bits for the per-cell flags word in snapshot().
enum {
  F_BOLD = 1 << 0,
  F_ITALIC = 1 << 1,
  F_FAINT = 1 << 2,
  F_UNDERLINE = 1 << 3,
  F_STRIKE = 1 << 4,
  F_INVERSE = 1 << 5,
  F_INVISIBLE = 1 << 6,
  F_WIDE = 1 << 7,
  F_SPACER = 1 << 8,
  F_SELECTED = 1 << 9,
  F_HAS_FG = 1 << 10,
  F_HAS_BG = 1 << 11,
  F_MULTI = 1 << 12,
};

static jint rgb(GhosttyColorRgb c) { return (jint)(0xFF000000u | ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) | c.b); }

// Fills cells (4 ints per cell: codepoint, fg, bg, flags), meta, and extra
// (multi-codepoint graphemes as [cellIndex, len, cp...]). Returns the number
// of ints written to extra.
JNIEXPORT jint JNICALL FN(snapshot)(JNIEnv* env, jclass cls, jlong handle, jintArray cellsArr, jintArray metaArr, jintArray extraArr) {
  (void)cls;
  Term* t = T;
  jsize cells_cap = (*env)->GetArrayLength(env, cellsArr);
  jsize extra_cap = (*env)->GetArrayLength(env, extraArr);
  jint* cells = (*env)->GetIntArrayElements(env, cellsArr, NULL);
  jint* extra = (*env)->GetIntArrayElements(env, extraArr, NULL);
  jint meta[16] = {0};
  jint extra_len = 0;

  pthread_mutex_lock(&t->mu);
  ghostty_render_state_update(t->rs, t->term);

  uint16_t cols = 0, nrows = 0;
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &nrows);
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);

  meta[0] = cols;
  meta[1] = nrows;
  meta[2] = cursor.visible && cursor.viewport_has_value;
  meta[3] = cursor.viewport_x;
  meta[4] = cursor.viewport_y;
  meta[5] = (jint)cursor.visual_style;
  meta[6] = rgb(colors.foreground);
  meta[7] = rgb(colors.background);
  meta[8] = colors.cursor_has_value ? rgb(colors.cursor) : 0;
  GhosttyTerminalScrollbar sb = {0};
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) == GHOSTTY_SUCCESS) {
    meta[9] = (jint)sb.total;
    meta[10] = (jint)sb.offset;
    meta[11] = (jint)sb.len;
  }
  meta[12] = t->title_changed;
  t->title_changed = 0;
  bool tracking = false;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
  meta[13] = tracking;
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  meta[14] = screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE;
  meta[15] = (t->bell ? 1 : 0) | (t->clip_pending ? 2 : 0);
  t->bell = 0;

  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &t->rows);
  int y = 0;
  while (y < nrows && ghostty_render_state_row_iterator_next(t->rows)) {
    ghostty_render_state_row_get(t->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &t->cells);
    int x = 0;
    while (x < cols && ghostty_render_state_row_cells_next(t->cells)) {
      int idx = y * cols + x;
      if ((idx + 1) * 4 > cells_cap) break;
      jint* c = cells + idx * 4;
      uint32_t glen = 0;
      ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
      uint32_t cps[16] = {0};
      if (glen > 0) ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps);
      GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
      ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
      jint flags = 0;
      GhosttyColorRgb fg, bg;
      if (ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg) == GHOSTTY_SUCCESS) {
        flags |= F_HAS_FG;
        c[1] = rgb(fg);
      } else {
        c[1] = 0;
      }
      if (ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bg) == GHOSTTY_SUCCESS) {
        flags |= F_HAS_BG;
        c[2] = rgb(bg);
      } else {
        c[2] = 0;
      }
      if (style.bold) flags |= F_BOLD;
      if (style.italic) flags |= F_ITALIC;
      if (style.faint) flags |= F_FAINT;
      if (style.underline != 0) flags |= F_UNDERLINE;
      if (style.strikethrough) flags |= F_STRIKE;
      if (style.inverse) flags |= F_INVERSE;
      if (style.invisible) flags |= F_INVISIBLE;
      bool selected = false;
      ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected);
      if (selected) flags |= F_SELECTED;
      GhosttyCell raw = 0;
      if (ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) == GHOSTTY_SUCCESS) {
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
        if (wide == GHOSTTY_CELL_WIDE_WIDE) flags |= F_WIDE;
        if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) flags |= F_SPACER;
      }
      c[0] = glen > 0 ? (jint)cps[0] : 0;
      if (glen > 1) {
        uint32_t n = glen < 16 ? glen : 16;
        if (extra_len + 2 + (jint)n <= extra_cap) {
          flags |= F_MULTI;
          extra[extra_len++] = idx;
          extra[extra_len++] = (jint)n;
          for (uint32_t i = 0; i < n; i++) extra[extra_len++] = (jint)cps[i];
        }
      }
      c[3] = flags;
      x++;
    }
    for (; x < cols; x++) {
      int idx = y * cols + x;
      if ((idx + 1) * 4 > cells_cap) break;
      memset(cells + idx * 4, 0, 4 * sizeof(jint));
    }
    y++;
  }
  ghostty_render_state_clean(t->rs);
  pthread_mutex_unlock(&t->mu);

  (*env)->ReleaseIntArrayElements(env, cellsArr, cells, 0);
  (*env)->ReleaseIntArrayElements(env, extraArr, extra, 0);
  (*env)->SetIntArrayRegion(env, metaArr, 0, 16, meta);
  return extra_len;
}

static GhosttyKey map_key(int code) {
  if (code >= AKEYCODE_A && code <= AKEYCODE_Z) return (GhosttyKey)(GHOSTTY_KEY_A + (code - AKEYCODE_A));
  if (code >= AKEYCODE_0 && code <= AKEYCODE_9) return (GhosttyKey)(GHOSTTY_KEY_DIGIT_0 + (code - AKEYCODE_0));
  if (code >= AKEYCODE_F1 && code <= AKEYCODE_F12) return (GhosttyKey)(GHOSTTY_KEY_F1 + (code - AKEYCODE_F1));
  switch (code) {
    case AKEYCODE_ENTER: return GHOSTTY_KEY_ENTER;
    case AKEYCODE_NUMPAD_ENTER: return GHOSTTY_KEY_NUMPAD_ENTER;
    case AKEYCODE_TAB: return GHOSTTY_KEY_TAB;
    case AKEYCODE_SPACE: return GHOSTTY_KEY_SPACE;
    case AKEYCODE_DEL: return GHOSTTY_KEY_BACKSPACE;
    case AKEYCODE_FORWARD_DEL: return GHOSTTY_KEY_DELETE;
    case AKEYCODE_ESCAPE: return GHOSTTY_KEY_ESCAPE;
    case AKEYCODE_DPAD_UP: return GHOSTTY_KEY_ARROW_UP;
    case AKEYCODE_DPAD_DOWN: return GHOSTTY_KEY_ARROW_DOWN;
    case AKEYCODE_DPAD_LEFT: return GHOSTTY_KEY_ARROW_LEFT;
    case AKEYCODE_DPAD_RIGHT: return GHOSTTY_KEY_ARROW_RIGHT;
    case AKEYCODE_MOVE_HOME: return GHOSTTY_KEY_HOME;
    case AKEYCODE_MOVE_END: return GHOSTTY_KEY_END;
    case AKEYCODE_PAGE_UP: return GHOSTTY_KEY_PAGE_UP;
    case AKEYCODE_PAGE_DOWN: return GHOSTTY_KEY_PAGE_DOWN;
    case AKEYCODE_INSERT: return GHOSTTY_KEY_INSERT;
    case AKEYCODE_GRAVE: return GHOSTTY_KEY_BACKQUOTE;
    case AKEYCODE_BACKSLASH: return GHOSTTY_KEY_BACKSLASH;
    case AKEYCODE_LEFT_BRACKET: return GHOSTTY_KEY_BRACKET_LEFT;
    case AKEYCODE_RIGHT_BRACKET: return GHOSTTY_KEY_BRACKET_RIGHT;
    case AKEYCODE_COMMA: return GHOSTTY_KEY_COMMA;
    case AKEYCODE_EQUALS: return GHOSTTY_KEY_EQUAL;
    case AKEYCODE_MINUS: return GHOSTTY_KEY_MINUS;
    case AKEYCODE_PERIOD: return GHOSTTY_KEY_PERIOD;
    case AKEYCODE_APOSTROPHE: return GHOSTTY_KEY_QUOTE;
    case AKEYCODE_SEMICOLON: return GHOSTTY_KEY_SEMICOLON;
    case AKEYCODE_SLASH: return GHOSTTY_KEY_SLASH;
    default: return GHOSTTY_KEY_UNIDENTIFIED;
  }
}

// Encodes a key press honoring terminal modes (cursor-key mode, kitty
// keyboard protocol, ...). Returns null if the key has no encoding.
JNIEXPORT jbyteArray JNICALL FN(encodeKey)(JNIEnv* env, jclass cls, jlong handle, jint androidKey, jint mods, jstring text, jint unshifted) {
  (void)cls;
  Term* t = T;
  GhosttyKey key = map_key(androidKey);
  if (key == GHOSTTY_KEY_UNIDENTIFIED && text == NULL) return NULL;
  const char* utf8 = text ? (*env)->GetStringUTFChars(env, text, NULL) : NULL;
  char buf[128];
  size_t n = 0;
  pthread_mutex_lock(&t->mu);
  ghostty_key_encoder_setopt_from_terminal(t->keys, t->term);
  ghostty_key_event_set_action(t->key_event, GHOSTTY_KEY_ACTION_PRESS);
  ghostty_key_event_set_key(t->key_event, key);
  ghostty_key_event_set_mods(t->key_event, (GhosttyMods)mods);
  ghostty_key_event_set_consumed_mods(t->key_event, 0);
  ghostty_key_event_set_composing(t->key_event, false);
  ghostty_key_event_set_utf8(t->key_event, utf8, utf8 ? strlen(utf8) : 0);
  ghostty_key_event_set_unshifted_codepoint(t->key_event, (uint32_t)unshifted);
  GhosttyResult r = ghostty_key_encoder_encode(t->keys, t->key_event, buf, sizeof(buf), &n);
  // The event borrows utf8; clear it before releasing the Java string.
  ghostty_key_event_set_utf8(t->key_event, NULL, 0);
  ghostty_terminal_scroll_viewport(t->term, (GhosttyTerminalScrollViewport){.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM});
  pthread_mutex_unlock(&t->mu);
  if (utf8) (*env)->ReleaseStringUTFChars(env, text, utf8);
  return r == GHOSTTY_SUCCESS ? bytes(env, buf, n) : NULL;
}

// Encodes a mouse event at pixel (x, y) per the remote's tracking mode and
// format. action: 0 press, 1 release, 2 motion; button: 0 none, 1 left,
// 4/5 wheel up/down. Returns null when the remote doesn't want this event.
JNIEXPORT jbyteArray JNICALL FN(encodeMouse)(JNIEnv* env, jclass cls, jlong handle, jint action, jint button, jboolean pressed,
                                              jfloat x, jfloat y, jint w, jint h, jint cw, jint ch) {
  (void)cls;
  Term* t = T;
  char buf[64];
  size_t n = 0;
  pthread_mutex_lock(&t->mu);
  ghostty_mouse_encoder_setopt_from_terminal(t->mouse, t->term);
  GhosttyMouseEncoderSize size = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
  size.screen_width = (uint32_t)w;
  size.screen_height = (uint32_t)h;
  size.cell_width = (uint32_t)cw;
  size.cell_height = (uint32_t)ch;
  ghostty_mouse_encoder_setopt(t->mouse, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
  bool any = pressed;
  ghostty_mouse_encoder_setopt(t->mouse, GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED, &any);
  ghostty_mouse_event_set_action(t->mouse_event, (GhosttyMouseAction)action);
  if (button > 0) {
    ghostty_mouse_event_set_button(t->mouse_event, (GhosttyMouseButton)button);
  } else {
    ghostty_mouse_event_clear_button(t->mouse_event);
  }
  ghostty_mouse_event_set_mods(t->mouse_event, 0);
  ghostty_mouse_event_set_position(t->mouse_event, (GhosttyMousePosition){.x = x, .y = y});
  GhosttyResult r = ghostty_mouse_encoder_encode(t->mouse, t->mouse_event, buf, sizeof(buf), &n);
  pthread_mutex_unlock(&t->mu);
  return r == GHOSTTY_SUCCESS ? bytes(env, buf, n) : NULL;
}

// Focus in/out report, only when the remote enabled mode 1004.
JNIEXPORT jbyteArray JNICALL FN(encodeFocus)(JNIEnv* env, jclass cls, jlong handle, jboolean gained) {
  (void)cls;
  Term* t = T;
  bool enabled = false;
  GhosttyTerminalModeConfig mode = {.mode = GHOSTTY_MODE_FOCUS_EVENT};
  pthread_mutex_lock(&t->mu);
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_MODE, &mode) == GHOSTTY_SUCCESS) enabled = mode.value;
  pthread_mutex_unlock(&t->mu);
  if (!enabled) return NULL;
  char buf[8];
  size_t n = 0;
  if (ghostty_focus_encode(gained ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST, buf, sizeof(buf), &n) != GHOSTTY_SUCCESS) return NULL;
  return bytes(env, buf, n);
}

JNIEXPORT jbyteArray JNICALL FN(takeClipboard)(JNIEnv* env, jclass cls, jlong handle) {
  (void)cls;
  Term* t = T;
  jbyteArray s = NULL;
  pthread_mutex_lock(&t->mu);
  if (t->clip_pending) {
    s = (*env)->NewByteArray(env, (jsize)t->clip_len);
    if (s) (*env)->SetByteArrayRegion(env, s, 0, (jsize)t->clip_len, (const jbyte*)t->clip);
    t->clip_pending = 0;
  }
  pthread_mutex_unlock(&t->mu);
  return s;
}

// Wraps pasted text in bracketed-paste markers when the remote asked for it.
JNIEXPORT jbyteArray JNICALL FN(encodePaste)(JNIEnv* env, jclass cls, jlong handle, jbyteArray data) {
  (void)cls;
  Term* t = T;
  jsize len = (*env)->GetArrayLength(env, data);
  jbyte* p = (*env)->GetByteArrayElements(env, data, NULL);
  bool bracketed = false;
  GhosttyTerminalModeConfig mode = {.mode = GHOSTTY_MODE_BRACKETED_PASTE};
  pthread_mutex_lock(&t->mu);
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_MODE, &mode) == GHOSTTY_SUCCESS) bracketed = mode.value;
  pthread_mutex_unlock(&t->mu);
  size_t cap = (size_t)len + 16;
  char* out = malloc(cap);
  size_t n = 0;
  jbyteArray arr = NULL;
  if (out && ghostty_paste_encode((char*)p, (size_t)len, bracketed, out, cap, &n) == GHOSTTY_SUCCESS) arr = bytes(env, out, n);
  free(out);
  (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
  return arr;
}

static GhosttyGridRef viewport_ref(Term* t, int x, int y) {
  GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_VIEWPORT, .value = {.coordinate = {.x = (uint16_t)x, .y = (uint32_t)y}}};
  ghostty_terminal_grid_ref(t->term, pt, &ref);
  return ref;
}

// Selects cells between two viewport positions (inclusive); x0 < 0 clears.
JNIEXPORT void JNICALL FN(select)(JNIEnv* env, jclass cls, jlong handle, jint x0, jint y0, jint x1, jint y1) {
  (void)env;
  (void)cls;
  Term* t = T;
  pthread_mutex_lock(&t->mu);
  if (x0 < 0) {
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
  } else {
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    sel.start = viewport_ref(t, x0, y0);
    sel.end = viewport_ref(t, x1, y1);
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
  }
  pthread_mutex_unlock(&t->mu);
}

JNIEXPORT jbyteArray JNICALL FN(selectionText)(JNIEnv* env, jclass cls, jlong handle) {
  (void)cls;
  Term* t = T;
  jbyteArray s = NULL;
  pthread_mutex_lock(&t->mu);
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) == GHOSTTY_SUCCESS) {
    GhosttyTerminalSelectionFormatOptions opts = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
    opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    opts.trim = true;
    opts.selection = &sel;
    uint8_t* buf = NULL;
    size_t len = 0;
    if (ghostty_terminal_selection_format_alloc(t->term, NULL, opts, &buf, &len) == GHOSTTY_SUCCESS) {
      s = bytes(env, (const char*)buf, len);
      ghostty_free(NULL, buf, len);
    }
  }
  pthread_mutex_unlock(&t->mu);
  return s;
}

JNIEXPORT jbyteArray JNICALL FN(title)(JNIEnv* env, jclass cls, jlong handle) {
  (void)cls;
  Term* t = T;
  jbyteArray s = NULL;
  pthread_mutex_lock(&t->mu);
  GhosttyString title = {0};
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_TITLE, &title) == GHOSTTY_SUCCESS) {
    s = bytes(env, (const char*)title.ptr, title.len);
  }
  pthread_mutex_unlock(&t->mu);
  return s;
}

// Display width (0, 1 or 2 cells) per Ghostty's Unicode tables, so the IME
// preedit lines up with how the terminal itself lays out text.
JNIEXPORT jint JNICALL FN(codepointWidth)(JNIEnv* env, jclass cls, jint cp) {
  (void)env;
  (void)cls;
  return (jint)ghostty_unicode_codepoint_width((uint32_t)cp);
}
