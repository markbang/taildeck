package top.bangwu.taildeck.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Draws a libghostty-vt terminal and turns touch, IME and hardware keys into
 * bytes for the remote. All terminal state lives in native code; this view
 * only renders snapshots of it.
 */
class TerminalView(context: Context) : View(context) {
    /** Bytes the remote should receive: keystrokes and terminal replies. */
    var onSend: (ByteArray) -> Unit = {}
    var onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    var onTitle: (String) -> Unit = {}
    /** Called when a one-shot modifier from the extra-keys row was used up. */
    var onModifiersConsumed: () -> Unit = {}

    var ctrlArmed = false
    var altArmed = false

    private val handle = GhosttyNative.create(80, 24, 10_000, FG, BG, CURSOR)
    private var closed = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fill = Paint()
    private var fontSp = 13f
    private var cellW = 1f
    private var cellH = 1
    private var baseline = 0f
    var cols = 80
        private set
    var rows = 24
        private set

    private var cells = IntArray(cols * rows * 4)
    private val meta = IntArray(16)
    private var extra = IntArray(4096)
    private val chars = CharArray(4096)

    private var composing = ""
    private var scrollAccum = 0f
    private var selecting = false
    private var selStart = 0 to 0
    private var actionMode: ActionMode? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BG)
        applyFont()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun applyFont() {
        paint.textSize = sp(fontSp)
        cellW = paint.measureText("M")
        val fm = paint.fontMetrics
        cellH = ceil(fm.descent - fm.ascent + fm.leading).toInt()
        baseline = -fm.ascent
        if (width > 0) layoutGrid(width, height)
    }

    private fun layoutGrid(w: Int, h: Int) {
        val c = max(1, floor(w / cellW).toInt())
        val r = max(1, h / cellH)
        if (c == cols && r == rows && cells.size >= c * r * 4) return
        cols = c
        rows = r
        cells = IntArray(c * r * 4)
        if (closed) return
        GhosttyNative.resize(handle, c, r, ceil(cellW).toInt(), cellH)?.let(onSend)
        onResize(c, r)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutGrid(w, h)

    /** Feeds remote output; safe to call from any thread. */
    fun feed(data: ByteArray) {
        if (closed || data.isEmpty()) return
        GhosttyNative.write(handle, data, data.size)?.let(onSend)
        postInvalidateOnAnimation()
    }

    fun close() {
        if (closed) return
        closed = true
        GhosttyNative.destroy(handle)
    }

    // ---- input ----

    private fun send(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        actionMode?.finish()
        onSend(bytes)
    }

    private fun consumeModifiers(): Int {
        var mods = 0
        if (ctrlArmed) mods = mods or GhosttyNative.MOD_CTRL
        if (altArmed) mods = mods or GhosttyNative.MOD_ALT
        if (mods != 0) {
            ctrlArmed = false
            altArmed = false
            onModifiersConsumed()
        }
        return mods
    }

    /** Sends a key such as KeyEvent.KEYCODE_DPAD_UP through the Ghostty key encoder. */
    fun sendKey(keyCode: Int, extraMods: Int = 0) {
        if (closed) return
        val mods = extraMods or consumeModifiers()
        send(GhosttyNative.encodeKey(handle, keyCode, mods, null, 0))
    }

    /** Sends typed text, applying one-shot Ctrl/Alt from the extra-keys row. */
    fun sendText(text: String) {
        if (closed || text.isEmpty()) return
        val mods = consumeModifiers()
        if (mods != 0 && text.length == 1) {
            val c = text[0]
            val lower = c.lowercaseChar()
            val keyCode = when (lower) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (lower - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (lower - '0')
                ' ' -> KeyEvent.KEYCODE_SPACE
                else -> 0
            }
            if (keyCode != 0) {
                send(GhosttyNative.encodeKey(handle, keyCode, mods, c.toString(), lower.code))
                return
            }
            if (mods and GhosttyNative.MOD_CTRL != 0 && c in "@[\\]^_") {
                send(byteArrayOf((c.code and 0x1f).toByte()))
                return
            }
            if (mods and GhosttyNative.MOD_ALT != 0) {
                send(("\u001b" + c).toByteArray())
                return
            }
        }
        send(text.replace("\n", "\r").toByteArray(Charsets.UTF_8))
        GhosttyNative.scroll(handle, 0)
    }

    fun paste(text: String) {
        if (closed || text.isEmpty()) return
        send(GhosttyNative.encodePaste(handle, text.toByteArray(Charsets.UTF_8)))
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions/autocorrect, but still a text field so CJK IMEs work.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return TermInputConnection()
    }

    private inner class TermInputConnection : BaseInputConnection(this, true) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            editable?.clear()
            sendText(text.toString())
            invalidate()
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            super.setComposingText(text, newCursorPosition)
            composing = text.toString()
            invalidate()
            return true
        }

        override fun finishComposingText(): Boolean {
            val text = composing
            composing = ""
            editable?.clear()
            super.finishComposingText()
            if (text.isNotEmpty()) sendText(text)
            invalidate()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isEmpty() && editable.isNullOrEmpty()) {
                repeat(max(1, beforeLength)) { sendKey(KeyEvent.KEYCODE_DEL) }
                return true
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
            return true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (closed) return false
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        var mods = consumeModifiers()
        if (event.isShiftPressed) mods = mods or GhosttyNative.MOD_SHIFT
        if (event.isCtrlPressed) mods = mods or GhosttyNative.MOD_CTRL
        if (event.isAltPressed) mods = mods or GhosttyNative.MOD_ALT
        if (event.isMetaPressed) mods = mods or GhosttyNative.MOD_SUPER
        val printable = event.getUnicodeChar(event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        val unshifted = event.getUnicodeChar(0)
        val text = if (printable > 0 && mods and (GhosttyNative.MOD_CTRL or GhosttyNative.MOD_ALT) == 0) {
            String(Character.toChars(printable))
        } else {
            null
        }
        val bytes = GhosttyNative.encodeKey(handle, keyCode, mods, text, max(0, unshifted))
        if (bytes != null) {
            send(bytes)
            return true
        }
        if (text != null) {
            send(text.toByteArray(Charsets.UTF_8))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- touch ----

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (actionMode != null) clearSelection() else showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (selecting) return true
            scrollAccum += dy
            val steps = (scrollAccum / cellH).toInt()
            if (steps == 0) return true
            scrollAccum -= steps * cellH
            scrollLines(steps, e2.x, e2.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            selecting = true
            val (x, y) = cellAt(e.x, e.y)
            val (from, to) = wordAt(x, y)
            selStart = from to y
            GhosttyNative.select(handle, from, y, to, y)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (fontSp * d.scaleFactor).coerceIn(7f, 30f)
            if (abs(next - fontSp) > 0.25f) {
                fontSp = next
                applyFont()
            }
            return true
        }
    })

    /** Positive = towards newer output (finger moving up). */
    private fun scrollLines(steps: Int, x: Float, y: Float) {
        if (meta[13] != 0) {
            repeat(abs(steps)) {
                send(GhosttyNative.encodeWheel(handle, steps < 0, x, y, width, height, ceil(cellW).toInt(), cellH))
            }
        } else if (meta[14] != 0) {
            // Alternate screen without mouse support (less, man): page with arrows.
            repeat(abs(steps)) { send(GhosttyNative.encodeKey(handle, if (steps < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0, null, 0)) }
        } else {
            GhosttyNative.scroll(handle, steps)
            invalidate()
        }
    }

    /** Column range of the non-blank run under (x, y), from the last drawn frame. */
    private fun wordAt(x: Int, y: Int): Pair<Int, Int> {
        fun blank(col: Int): Boolean {
            val i = (y * cols + col) * 4
            return i + 3 >= cells.size || cells[i] <= 32 && cells[i + 3] and GhosttyNative.F_SPACER == 0
        }
        if (blank(x)) return x to x
        var from = x
        while (from > 0 && !blank(from - 1)) from--
        var to = x
        while (to < cols - 1 && !blank(to + 1)) to++
        return from to to
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> =
        (x / cellW).toInt().coerceIn(0, cols - 1) to (y / cellH).toInt().coerceIn(0, rows - 1)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closed) return false
        scaler.onTouchEvent(event)
        if (scaler.isInProgress) return true
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val (x, y) = cellAt(event.x, event.y)
                    GhosttyNative.select(handle, selStart.first, selStart.second, x, y)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    startSelectionMenu()
                }
            }
        }
        gestures.onTouchEvent(event)
        return true
    }

    private fun clearSelection() {
        GhosttyNative.select(handle, -1, 0, 0, 0)
        actionMode?.finish()
        invalidate()
    }

    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)

    private fun startSelectionMenu() {
        actionMode?.finish()
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_COPY, 0, android.R.string.copy)
                menu.add(0, MENU_PASTE, 1, android.R.string.paste)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY -> GhosttyNative.selectionText(handle)?.let {
                        clipboard().setPrimaryClip(ClipData.newPlainText("terminal", String(it, Charsets.UTF_8)))
                    }
                    MENU_PASTE -> clipboard().primaryClip?.getItemAt(0)?.coerceToText(context)?.let { paste(it.toString()) }
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                GhosttyNative.select(handle, -1, 0, 0, 0)
                invalidate()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val y = selStart.second * cellH
                outRect.set(0, y, width, y + cellH)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    // ---- rendering ----

    override fun onDraw(canvas: Canvas) {
        if (closed) return
        if (cells.size < cols * rows * 4) cells = IntArray(cols * rows * 4)
        val extraLen = GhosttyNative.snapshot(handle, cells, meta, extra)
        val c = meta[0]
        val r = meta[1]
        if (c == 0 || r == 0) return
        val defFg = meta[6]
        val defBg = meta[7]
        canvas.drawColor(defBg)
        if (meta[12] != 0) GhosttyNative.title(handle)?.let { onTitle(String(it, Charsets.UTF_8)) }

        val multi = if (extraLen > 0) parseExtra(extraLen) else null

        for (y in 0 until r) {
            val top = (y * cellH).toFloat()
            // Backgrounds first, so wide glyphs aren't clipped by the next cell.
            for (x in 0 until c) {
                val i = (y * c + x) * 4
                val flags = cells[i + 3]
                val bg = cellBg(flags, cells[i + 1], cells[i + 2], defFg, defBg)
                if (bg != defBg) {
                    fill.color = bg
                    val span = if (flags and GhosttyNative.F_WIDE != 0) 2 else 1
                    canvas.drawRect(x * cellW, top, (x + span) * cellW + 0.5f, top + cellH, fill)
                }
            }
            var x = 0
            while (x < c) {
                val i = (y * c + x) * 4
                val cp = cells[i]
                val flags = cells[i + 3]
                if (cp <= 32 || flags and (GhosttyNative.F_SPACER or GhosttyNative.F_INVISIBLE) != 0) {
                    if (flags and (GhosttyNative.F_UNDERLINE or GhosttyNative.F_STRIKE) != 0) decorate(canvas, x, 1, top, flags, cellFg(flags, cells[i + 1], cells[i + 2], defFg, defBg))
                    x++
                    continue
                }
                val fg = cellFg(flags, cells[i + 1], cells[i + 2], defFg, defBg)
                val styleKey = flags and STYLE_MASK
                if (cp < 0x7f && flags and GhosttyNative.F_MULTI == 0) {
                    // Batch an ASCII run with identical style into one drawText.
                    var n = 0
                    var j = x
                    while (j < c && n < chars.size) {
                        val k = (y * c + j) * 4
                        val cpj = cells[k]
                        val fj = cells[k + 3]
                        if (cpj < 32 || cpj >= 0x7f || fj and STYLE_MASK != styleKey || fj and GhosttyNative.F_MULTI != 0 ||
                            cellFg(fj, cells[k + 1], cells[k + 2], defFg, defBg) != fg
                        ) break
                        chars[n++] = cpj.toChar()
                        j++
                    }
                    if (n > 0) {
                        stylePaint(flags, fg)
                        canvas.drawText(chars, 0, n, x * cellW, top + baseline, paint)
                        decorate(canvas, x, n, top, flags, fg)
                        x += n
                        continue
                    }
                }
                val span = if (flags and GhosttyNative.F_WIDE != 0) 2 else 1
                val text = multi?.get(y * c + x) ?: String(Character.toChars(cp))
                stylePaint(flags, fg)
                val w = paint.measureText(text)
                val left = x * cellW + max(0f, (span * cellW - w) / 2)
                canvas.drawText(text, left, top + baseline, paint)
                decorate(canvas, x, span, top, flags, fg)
                x += span
            }
        }
        drawCursor(canvas, c, defFg, defBg)
        drawScrollbar(canvas)
    }

    private fun parseExtra(len: Int): Map<Int, String> {
        val out = HashMap<Int, String>()
        var i = 0
        while (i + 1 < len) {
            val idx = extra[i]
            val n = extra[i + 1]
            val sb = StringBuilder()
            for (k in 0 until n) sb.appendCodePoint(extra[i + 2 + k])
            out[idx] = sb.toString()
            i += 2 + n
        }
        return out
    }

    private fun cellFg(flags: Int, fg: Int, bg: Int, defFg: Int, defBg: Int): Int {
        var color = if (flags and GhosttyNative.F_HAS_FG != 0) fg else defFg
        if (flags and GhosttyNative.F_INVERSE != 0) color = if (flags and GhosttyNative.F_HAS_BG != 0) bg else defBg
        if (flags and GhosttyNative.F_SELECTED != 0) color = SELECTION_FG
        if (flags and GhosttyNative.F_FAINT != 0) color = (color and 0x00FFFFFF) or (0x99 shl 24)
        return color
    }

    private fun cellBg(flags: Int, fg: Int, bg: Int, defFg: Int, defBg: Int): Int {
        if (flags and GhosttyNative.F_SELECTED != 0) return SELECTION_BG
        if (flags and GhosttyNative.F_INVERSE != 0) return if (flags and GhosttyNative.F_HAS_FG != 0) fg else defFg
        return if (flags and GhosttyNative.F_HAS_BG != 0) bg else defBg
    }

    private fun stylePaint(flags: Int, color: Int) {
        paint.color = color
        paint.isFakeBoldText = flags and GhosttyNative.F_BOLD != 0
        paint.textSkewX = if (flags and GhosttyNative.F_ITALIC != 0) -0.2f else 0f
    }

    private fun decorate(canvas: Canvas, x: Int, n: Int, top: Float, flags: Int, color: Int) {
        if (flags and (GhosttyNative.F_UNDERLINE or GhosttyNative.F_STRIKE) == 0) return
        fill.color = color
        val thick = max(1f, cellH / 14f)
        if (flags and GhosttyNative.F_UNDERLINE != 0) {
            canvas.drawRect(x * cellW, top + cellH - thick * 1.5f, (x + n) * cellW, top + cellH - thick * 0.5f, fill)
        }
        if (flags and GhosttyNative.F_STRIKE != 0) {
            val mid = top + cellH / 2f
            canvas.drawRect(x * cellW, mid - thick / 2, (x + n) * cellW, mid + thick / 2, fill)
        }
    }

    private fun drawCursor(canvas: Canvas, c: Int, defFg: Int, defBg: Int) {
        val cx = meta[3]
        val cy = meta[4]
        val left = cx * cellW
        val top = (cy * cellH).toFloat()
        if (composing.isNotEmpty()) {
            paint.isFakeBoldText = false
            paint.textSkewX = 0f
            val w = paint.measureText(composing)
            fill.color = defBg
            canvas.drawRect(left, top, left + w, top + cellH, fill)
            paint.color = defFg
            canvas.drawText(composing, left, top + baseline, paint)
            fill.color = CURSOR
            canvas.drawRect(left, top + cellH - 2, left + w, top + cellH, fill)
            return
        }
        if (meta[2] == 0) return
        val i = (cy * c + cx) * 4
        val wide = i + 3 < cells.size && cells[i + 3] and GhosttyNative.F_WIDE != 0
        val right = left + cellW * (if (wide) 2 else 1)
        fill.color = if (meta[8] != 0) meta[8] else CURSOR
        val bar = max(2f, cellW / 6)
        when {
            !hasFocus() -> {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 2f
                canvas.drawRect(left + 1, top + 1, right - 1, top + cellH - 1, fill)
                fill.style = Paint.Style.FILL
            }
            meta[5] == CURSOR_BAR -> canvas.drawRect(left, top, left + bar, top + cellH, fill)
            meta[5] == CURSOR_UNDERLINE -> canvas.drawRect(left, top + cellH - bar, right, top + cellH, fill)
            else -> {
                canvas.drawRect(left, top, right, top + cellH, fill)
                if (i + 3 < cells.size && cells[i] > 32) {
                    stylePaint(cells[i + 3], defBg)
                    canvas.drawText(String(Character.toChars(cells[i])), left, top + baseline, paint)
                }
            }
        }
    }

    private fun drawScrollbar(canvas: Canvas) {
        val total = meta[9]
        val offset = meta[10]
        val len = meta[11]
        if (total <= len || offset + len >= total) return
        val h = height.toFloat()
        val top = h * offset / total
        val bottom = max(top + 24f, h * (offset + len) / total)
        fill.color = 0x66FFFFFF
        canvas.drawRect(width - 6f, top, width - 2f, bottom, fill)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    companion object {
        const val FG = 0xFFE6E6E6.toInt()
        const val BG = 0xFF0F1113.toInt()
        const val CURSOR = 0xFF8FD3A8.toInt()
        private const val SELECTION_BG = 0xFF2C4A38.toInt()
        private const val SELECTION_FG = 0xFFFFFFFF.toInt()
        private const val STYLE_MASK = GhosttyNative.F_BOLD or GhosttyNative.F_ITALIC or GhosttyNative.F_FAINT or
            GhosttyNative.F_UNDERLINE or GhosttyNative.F_STRIKE or GhosttyNative.F_INVERSE or GhosttyNative.F_SELECTED
        // GhosttyRenderStateCursorVisualStyle
        private const val CURSOR_BAR = 0
        private const val CURSOR_UNDERLINE = 2
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
    }
}
