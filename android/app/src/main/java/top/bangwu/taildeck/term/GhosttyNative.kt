package top.bangwu.taildeck.term

/** Thin JNI surface over libghostty-vt; see src/main/cpp/term_jni.c. */
object GhosttyNative {
    init {
        System.loadLibrary("taildeckterm")
    }

    const val F_BOLD = 1 shl 0
    const val F_ITALIC = 1 shl 1
    const val F_FAINT = 1 shl 2
    const val F_UNDERLINE = 1 shl 3
    const val F_STRIKE = 1 shl 4
    const val F_INVERSE = 1 shl 5
    const val F_INVISIBLE = 1 shl 6
    const val F_WIDE = 1 shl 7
    const val F_SPACER = 1 shl 8
    const val F_SELECTED = 1 shl 9
    const val F_HAS_FG = 1 shl 10
    const val F_HAS_BG = 1 shl 11
    const val F_MULTI = 1 shl 12

    const val MOD_SHIFT = 1
    const val MOD_CTRL = 2
    const val MOD_ALT = 4
    const val MOD_SUPER = 8

    @JvmStatic external fun create(cols: Int, rows: Int, scrollback: Int, fg: Int, bg: Int, cursor: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun write(handle: Long, data: ByteArray, len: Int): ByteArray?
    @JvmStatic external fun resize(handle: Long, cols: Int, rows: Int, cellW: Int, cellH: Int): ByteArray?
    @JvmStatic external fun scroll(handle: Long, delta: Int)
    @JvmStatic external fun snapshot(handle: Long, cells: IntArray, meta: IntArray, extra: IntArray): Int
    @JvmStatic external fun encodeKey(handle: Long, androidKey: Int, mods: Int, text: String?, unshifted: Int): ByteArray?
    @JvmStatic external fun encodeWheel(handle: Long, up: Boolean, x: Float, y: Float, w: Int, h: Int, cellW: Int, cellH: Int): ByteArray?
    @JvmStatic external fun encodePaste(handle: Long, data: ByteArray): ByteArray?
    @JvmStatic external fun select(handle: Long, x0: Int, y0: Int, x1: Int, y1: Int)
    @JvmStatic external fun selectionText(handle: Long): ByteArray?
    @JvmStatic external fun title(handle: Long): ByteArray?
}
