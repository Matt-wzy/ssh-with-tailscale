// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale.terminal

/**
 * A small, dependency-free VT100/ANSI screen buffer.
 *
 * It holds the characters plus their colour/attribute state and exposes a
 * scrollable list of lines for a View to render. It deliberately has no Android
 * imports so it can be unit-tested on a plain JVM.
 */
class TerminalBuffer(
    var cols: Int = 80,
    var rows: Int = 24,
    private val maxLines: Int = 2000
) {
    companion object {
        const val FLAG_BOLD = 1.toByte()
        const val FLAG_UNDERLINE = 2.toByte()

        /** Sentinel colour meaning "terminal default colour". */
        const val COLOR_DEFAULT = -1

        /**
         * Tag bit for extended colours (256-colour cube, 24-bit true colour)
         * packed into an fg/bg cell value as COLOR_RGB_TAG | 0x00RRGGBB. Plain
         * SGR codes (30-37, 90-97, 40-47, 100-107) stay untagged so the View
         * can map them onto its own palette.
         */
        const val COLOR_RGB_TAG = 0x01000000.toInt()

        /** The six intensity levels of the 256-colour cube. */
        private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)
    }

    /** One row of cells. */
    class Line(width: Int) {
        val chars = CharArray(width) { ' ' }
        val fg = IntArray(width) { COLOR_DEFAULT }
        val bg = IntArray(width) { COLOR_DEFAULT }
        val flags = ByteArray(width)

        /**
         * True for the trailing cell of a double-width character (CJK, emoji).
         * The glyph itself lives in the cell to the left and is drawn spanning
         * both columns, so this cell is skipped when the row is painted.
         */
        val cont = BooleanArray(width)

        fun clear(width: Int = chars.size) {
            for (i in 0 until width) {
                chars[i] = ' '
                fg[i] = COLOR_DEFAULT
                bg[i] = COLOR_DEFAULT
                flags[i] = 0
                cont[i] = false
            }
        }

    }

    /**
     * The active screen. [enterAlt]/[exitAlt] swap this between the normal
     * buffer and the alternate screen, so all the drawing helpers below can
     * stay oblivious to which one is showing.
     */
    private var lines = ArrayList<Line>()

    /** The normal buffer, saved while the alternate screen is active. */
    private var primary: ArrayList<Line>? = null
    private var altActive = false

    /** DECSC save/restore slot: x, y, fg, bg, flags. */
    private var savedCursor: IntArray? = null

    /** False while CSI ?25l hides the cursor (vim does this while redrawing). */
    var isCursorVisible: Boolean = true
        private set

    /** Cursor position within the visible window. */
    var cursorX = 0
        private set
    var cursorY = 0
        private set

    /** Current SGR state applied to newly written characters. */
    private var curFg = COLOR_DEFAULT
    private var curBg = COLOR_DEFAULT
    private var curFlags: Byte = 0

    /** Escape-sequence parser state. */
    private enum class State { NORMAL, ESC, CSI, STRING, STRING_ESC }

    /**
     * Optional diagnostics hook: called with a short description of every
     * control sequence applied. Unused (and free) unless someone sets it, so
     * the class stays Android-free and unit-testable.
     */
    var trace: ((String) -> Unit)? = null

    private var state = State.NORMAL
    private val params = StringBuilder()
    private var csiPrivate = false

    init {
        ensureScreen()
    }

    // ---------------------------------------------------------------- sizing

    private fun newLine() = Line(cols)

    private fun ensureScreen() {
        while (lines.size < rows) lines.add(newLine())
    }

    /** Resize the screen; existing content is preserved as far as possible. */
    fun setSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        // Remember which *logical* line the cursor sat on (its absolute index
        // into `lines`) before the row count changes. The soft keyboard showing
        // or hiding resizes this view, and the screen is simply the last `rows`
        // lines -- so without remapping, growing the view leaves the cursor
        // stranded rows above the prompt (its screen row is unchanged while the
        // screen top moved up). Re-deriving `cursorY` from the absolute line
        // keeps the cursor glued to the same line across a resize.
        val oldRows = rows
        val cursorAbsBefore = (lines.size - oldRows).coerceAtLeast(0) + cursorY

        lines = resizeList(lines, newCols)
        // The buffer that is currently swapped out (the normal screen while an
        // alt-screen app runs) must be rebuilt too, or restoring it later shows
        // lines of the old width.
        primary = primary?.let { resizeList(it, newCols) }
        cols = newCols
        rows = newRows
        ensureScreen()
        trim()
        cursorX = cursorX.coerceIn(0, cols - 1)
        val newScreenTop = (lines.size - rows).coerceAtLeast(0)
        cursorY = (cursorAbsBefore - newScreenTop).coerceIn(0, rows - 1)
    }

    /** Rebuild every line of [src] at [newCols] wide, copying content over. */
    private fun resizeList(src: List<Line>, newCols: Int): ArrayList<Line> {
        val out = ArrayList<Line>(src.size)
        for (line in src) {
            val l = Line(newCols)
            val n = minOf(line.chars.size, newCols)
            System.arraycopy(line.chars, 0, l.chars, 0, n)
            System.arraycopy(line.fg, 0, l.fg, 0, n)
            System.arraycopy(line.bg, 0, l.bg, 0, n)
            System.arraycopy(line.flags, 0, l.flags, 0, n)
            System.arraycopy(line.cont, 0, l.cont, 0, n)
            out.add(l)
        }
        return out
    }

    private fun trim() {
        val overflow = lines.size - maxLines
        if (overflow > 0) repeat(overflow) { lines.removeAt(0) }
    }

    // ----------------------------------------------------------- line access

    /** Total number of buffered lines (scrollback + screen). */
    val lineCount: Int get() = lines.size

    /** Index of the first line of the visible screen. */
    val screenTop: Int get() = (lines.size - rows).coerceAtLeast(0)

    fun lineAt(index: Int): Line = lines[index.coerceIn(0, lines.size - 1)]

    /** True when the screen cell [x], [screenY] is the trailing half of a wide char. */
    fun isContinuation(screenY: Int, x: Int): Boolean {
        if (x < 0 || x >= cols) return false
        return lineAt(screenTop + screenY.coerceIn(0, rows - 1)).cont[x]
    }

    /** True when the screen cell [x], [screenY] starts a two-column character. */
    fun isWideHead(screenY: Int, x: Int): Boolean {
        if (x < 0 || x >= cols - 1) return false
        val line = lineAt(screenTop + screenY.coerceIn(0, rows - 1))
        return !line.cont[x] && line.cont[x + 1]
    }

    /** Absolute index (into [lines]) of the line the cursor is on. */
    private fun cursorAbs(): Int = screenTop + cursorY.coerceIn(0, rows - 1)

    // --------------------------------------------------------------- feeding

    /** Feed raw text (already decoded) through the ANSI parser. */
    fun feed(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            // A surrogate pair is a single astral character (usually a
            // double-width emoji): keep both halves in adjacent cells so the row
            // renderer, which paints a whole run as one string, still forms the
            // right glyph, and advance by two columns as a terminal would.
            if (state == State.NORMAL && ch.isHighSurrogate() &&
                i + 1 < text.length && text[i + 1].isLowSurrogate()
            ) {
                putWide(ch, text[i + 1])
                i += 2
                continue
            }
            feedChar(ch)
            i++
        }
    }

    /**
     * True for characters a terminal renders in two columns.
     *
     * Without this a CJK character advanced the cursor by one column while the
     * server counted two, so every column after Chinese text was wrong --
     * which showed up as a misplaced cursor and scrambled redraws when the
     * arrow keys moved through such a line.
     */
    private fun isWide(ch: Char): Boolean = when (ch.code) {
        in 0x1100..0x115F,  // Hangul Jamo initial
        in 0x2E80..0x303E,  // CJK radicals, Kangxi, CJK symbols and punctuation
        in 0x3041..0x33FF,  // kana .. CJK compatibility
        in 0x3400..0x4DBF,  // CJK unified ideographs extension A
        in 0x4E00..0x9FFF,  // CJK unified ideographs
        in 0xA000..0xA4CF,  // Yi
        in 0xAC00..0xD7A3,  // Hangul syllables
        in 0xF900..0xFAFF,  // CJK compatibility ideographs
        in 0xFE30..0xFE4F,  // CJK compatibility forms
        in 0xFF00..0xFF60,  // fullwidth forms
        in 0xFFE0..0xFFE6   // fullwidth signs
        -> true
        else -> false
    }

    private fun feedChar(ch: Char) {
        when (state) {
            State.NORMAL -> when (ch) {
                '\u001b' -> {
                    state = State.ESC
                    params.setLength(0)
                    csiPrivate = false
                }
                '\n' -> {
                    newline()
                    trace?.invoke("LF -> x=$cursorX y=$cursorY")
                }
                '\r' -> {
                    cursorX = 0
                    trace?.invoke("CR -> x=0")
                }
                '\b' -> {
                    if (cursorX > 0) cursorX--
                    trace?.invoke("BS -> x=$cursorX")
                }
                '\t' -> {
                    val next = ((cursorX / 8) + 1) * 8
                    cursorX = next.coerceAtMost(cols - 1)
                }
                '\u0007' -> Unit // bell
                // 8-bit C1 controls. A terminal description that advertises
                // 8-bit controls sends e.g. 0x9B where a normal one sends ESC
                // '['. Translate what we understand and drop the rest -- they
                // must never be printed, because each one would advance the
                // cursor and corrupt the line.
                in '\u0080'..'\u009f' -> when (ch) {
                    '\u009b' -> {
                        state = State.CSI
                        params.setLength(0)
                        csiPrivate = false
                    }
                    '\u0084', '\u0085' -> newline() // IND / NEL
                    else -> Unit
                }
                else -> putChar(ch)
            }
            State.ESC -> {
                when (ch) {
                    '[' -> {
                        state = State.CSI
                        params.setLength(0)
                    }
                    // OSC (]) and DCS/SOS/PM/APC (P X ^ _): a string that runs
                    // until BEL or ST (ESC \). Its payload must be swallowed.
                    // bash puts an OSC "set window title" sequence in PS1, and
                    // printing that payload used to duplicate the whole prompt.
                    ']', 'P', 'X', '^', '_' -> {
                        state = State.STRING
                        params.setLength(0)
                    }
                    'M' -> reverseIndex()
                    // DECSC / DECRC: save and restore cursor position plus SGR
                    // state. vim's alternate-screen dance (?1049h / ?1049l)
                    // depends on this pair surviving the switch.
                    '7' -> saveCursorState()
                    '8' -> restoreCursorState()
                    else -> {
                        state = State.NORMAL
                        putChar(ch)
                    }
                }
            }
            State.STRING -> when (ch) {
                '\u0007' -> state = State.NORMAL      // BEL terminates
                '\u001b' -> state = State.STRING_ESC  // maybe ST (ESC \)
                else -> Unit                          // swallow the payload
            }
            State.STRING_ESC ->
                state = if (ch == '\\') State.NORMAL else State.STRING
            State.CSI -> {
                when {
                    ch == '?' -> csiPrivate = true
                    ch in '0'..'9' || ch == ';' -> params.append(ch)
                    else -> {
                        dispatchCsi(ch)
                        state = State.NORMAL
                    }
                }
            }
        }
    }

    private fun p(i: Int, def: Int = 1): Int =
        params.toString().split(';').getOrNull(i)?.trim()
            ?.toIntOrNull()?.takeIf { it >= 0 } ?: def

    private fun dispatchCsi(final: Char) {
        if (csiPrivate) {
            dispatchPrivate(final)
            return // e.g. DEC private modes (?25h/l, ?1049h/l) - handled below
        }
        when (final) {
            'A' -> cursorY = (cursorY - p(0)).coerceAtLeast(0)
            'B' -> cursorY = (cursorY + p(0)).coerceAtMost(rows - 1)
            'C' -> cursorX = (cursorX + p(0)).coerceAtMost(cols - 1)
            'D' -> cursorX = (cursorX - p(0)).coerceAtLeast(0)
            'E' -> {
                cursorX = 0
                cursorY = (cursorY + p(0)).coerceAtMost(rows - 1)
            }
            'F' -> {
                cursorX = 0
                cursorY = (cursorY - p(0)).coerceAtLeast(0)
            }
            'G', '`' -> cursorX = (p(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                val row = p(0)
                val col = p(1)
                cursorY = (row - 1).coerceIn(0, rows - 1)
                cursorX = (col - 1).coerceIn(0, cols - 1)
            }
            'J' -> when (p(0, 0)) {
                0 -> eraseFromCursorToEnd()
                1 -> eraseFromStartToCursor()
                else -> clearScreen()
            }
            'K' -> when (p(0, 0)) {
                0 -> eraseToEndOfLine()
                1 -> eraseFromStartOfLine()
                else -> lineAt(cursorAbs()).clear(cols)
            }
            'S' -> repeat(p(0)) { scrollUp() }
            'T' -> repeat(p(0)) { reverseIndex() }
            'm' -> applySgr()
            else -> Unit // unsupported sequence: ignore
        }
        trace?.invoke("CSI '${params}'$final -> x=$cursorX y=$cursorY")
    }

    /**
     * DEC private modes (`CSI ? n h` / `CSI ? n l`).
     *
     * Ignoring all of them made full-screen TUI apps look broken: vim/htop/less
     * run on the alternate screen (?1049), which used to dump their redraws
     * into the scrollback and left the original screen unrecoverable on exit.
     */
    private fun dispatchPrivate(final: Char) {
        if (final != 'h' && final != 'l') {
            trace?.invoke("CSI ?${params}$final (ignored)")
            return
        }
        val enable = final == 'h'
        for (tok in params.toString().split(';')) {
            when (tok.trim().toIntOrNull()) {
                // Alternate screen. 47 is the plain switch; 1047 the same plus
                // clear-on-exit (we discard the alt buffer on exit anyway);
                // 1049 additionally saves/restores the cursor, DECSC-style.
                47, 1047 -> if (enable) enterAlt() else exitAlt()
                1049 -> if (enable) {
                    saveCursorState()
                    enterAlt()
                    cursorX = 0
                    cursorY = 0
                } else {
                    exitAlt()
                    restoreCursorState()
                }
                // DECTCEM: cursor visibility.
                25 -> isCursorVisible = enable
                else -> trace?.invoke("CSI ?$tok$final (ignored)")
            }
        }
        trace?.invoke("CSI ?${params}$final -> alt=$altActive cursor=$isCursorVisible")
    }

    private fun saveCursorState() {
        savedCursor = intArrayOf(cursorX, cursorY, curFg, curBg, curFlags.toInt())
    }

    private fun restoreCursorState() {
        val s = savedCursor ?: return
        cursorX = s[0].coerceIn(0, cols - 1)
        cursorY = s[1].coerceIn(0, rows - 1)
        curFg = s[2]
        curBg = s[3]
        curFlags = s[4].toByte()
    }

    private fun enterAlt() {
        if (altActive) return
        altActive = true
        primary = lines
        val fresh = ArrayList<Line>(rows)
        repeat(rows) { fresh.add(newLine()) }
        lines = fresh
    }

    private fun exitAlt() {
        if (!altActive) return
        altActive = false
        lines = primary ?: lines
        primary = null
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
    }

    private fun applySgr() {
        val codes = params.toString().split(';')
        var i = 0
        if (codes.isEmpty() || codes.all { it.isBlank() }) {
            curFg = COLOR_DEFAULT
            curBg = COLOR_DEFAULT
            curFlags = 0
            return
        }
        while (i < codes.size) {
            // Bind to a non-null local so the `in <range>` branches below get a
            // plain Int (toIntOrNull() yields Int?, which Kotlin will not
            // smart-cast inside a range check).
            val c = codes[i].trim().toIntOrNull()
            if (c == null) {
                i++
                continue
            }
            when (c) {
                0 -> {
                    curFg = COLOR_DEFAULT
                    curBg = COLOR_DEFAULT
                    curFlags = 0
                }
                1 -> curFlags = (curFlags.toInt() or FLAG_BOLD.toInt()).toByte()
                4 -> curFlags = (curFlags.toInt() or FLAG_UNDERLINE.toInt()).toByte()
                22 -> curFlags = (curFlags.toInt() and FLAG_BOLD.toInt().inv()).toByte()
                24 -> curFlags = (curFlags.toInt() and FLAG_UNDERLINE.toInt().inv()).toByte()
                39 -> curFg = COLOR_DEFAULT
                49 -> curBg = COLOR_DEFAULT
                in 30..37 -> curFg = c
                in 90..97 -> curFg = c
                in 40..47 -> curBg = c
                in 100..107 -> curBg = c
                38 -> { // extended foreground
                    val (consumed, colour) = extendedColor(codes, i, curFg)
                    curFg = colour
                    i += consumed
                }
                48 -> { // extended background
                    val (consumed, colour) = extendedColor(codes, i, curBg)
                    curBg = colour
                    i += consumed
                }
            }
            i++
        }
    }

    /**
     * Parses an extended-colour spec whose selector sits at [codes][i + 1]
     * (i.e. right after the "38"/"48" token). Returns (tokens consumed, colour).
     *
     * 256-colour indices 0-15 become plain SGR codes so they keep rendering
     * from the View's theme palette; 16-255 and 24-bit RGB are packed as
     * COLOR_RGB_TAG | 0x00RRGGBB for the View to paint directly. Before this,
     * a 256-colour index fell through to `(code % 10)` and painted as an
     * essentially random base colour.
     */
    private fun extendedColor(codes: List<String>, i: Int, current: Int): Pair<Int, Int> {
        fun component(offset: Int): Int =
            (codes.getOrNull(i + offset)?.trim()?.toIntOrNull() ?: 0).coerceIn(0, 255)
        return when (codes.getOrNull(i + 1)?.trim()) {
            "5" -> {
                val n = codes.getOrNull(i + 2)?.trim()?.toIntOrNull()
                2 to if (n != null && n in 0..255) ansi256ToCode(n) else current
            }
            "2" -> 4 to (COLOR_RGB_TAG or (component(2) shl 16) or (component(3) shl 8) or component(4))
            else -> 0 to current
        }
    }

    private fun ansi256ToCode(n: Int): Int = when {
        n < 8 -> 30 + n
        n < 16 -> 90 + (n - 8)
        n < 232 -> {
            val v = n - 16
            val r = CUBE_LEVELS[v / 36]
            val g = CUBE_LEVELS[(v % 36) / 6]
            val b = CUBE_LEVELS[v % 6]
            COLOR_RGB_TAG or (r shl 16) or (g shl 8) or b
        }
        else -> {
            // 24 shades from dark grey to near-white.
            val gray = 8 + (n - 232) * 10
            COLOR_RGB_TAG or (gray shl 16) or (gray shl 8) or gray
        }
    }

    // ------------------------------------------------------------- primitives

    private fun putChar(ch: Char) {
        if (isWide(ch)) {
            putWide(ch)
            return
        }
        if (cursorX >= cols) {
            cursorX = 0
            newline()
        }
        val line = lineAt(cursorAbs())
        unsplitWideAt(line, cursorX)
        line.chars[cursorX] = ch
        line.fg[cursorX] = curFg
        line.bg[cursorX] = curBg
        line.flags[cursorX] = curFlags
        cursorX++
    }

    /**
     * Write a double-width character. It owns this cell and the next one;
     * [second] carries the low surrogate of an astral character (emoji) or a
     * blank for a plain CJK glyph.
     */
    private fun putWide(first: Char, second: Char = ' ') {
        // Wrap first: a wide character must never be split across two rows.
        if (cursorX + 2 > cols) {
            cursorX = 0
            newline()
        }
        val line = lineAt(cursorAbs())
        unsplitWideAt(line, cursorX)
        unsplitWideAt(line, cursorX + 1)
        line.chars[cursorX] = first
        line.chars[cursorX + 1] = second
        line.fg[cursorX] = curFg
        line.fg[cursorX + 1] = curFg
        line.bg[cursorX] = curBg
        line.bg[cursorX + 1] = curBg
        line.flags[cursorX] = curFlags
        line.flags[cursorX + 1] = curFlags
        line.cont[cursorX] = false
        line.cont[cursorX + 1] = true
        cursorX += 2
    }

    /**
     * Writing over the trailing cell of a wide glyph has to erase the glyph's
     * leading cell too, otherwise the old glyph would still be painted on top
     * of the new character (it spans both columns).
     */
    private fun unsplitWideAt(line: Line, x: Int) {
        if (x < 0 || x >= cols || !line.cont[x]) return
        val head = x - 1
        if (head >= 0) {
            line.chars[head] = ' '
            line.fg[head] = COLOR_DEFAULT
            line.bg[head] = COLOR_DEFAULT
            line.flags[head] = 0
            line.cont[head] = false
        }
        line.cont[x] = false
    }

    private fun newline() {
        cursorY++
        while (cursorY >= rows) {
            scrollUp()
            cursorY--
        }
    }

    /** Scroll the screen up by one, keeping a blank line at the bottom. */
    private fun scrollUp() {
        if (altActive) {
            // The alternate screen has no scrollback: drop the top line so the
            // screen stays exactly `rows` tall.
            if (lines.isNotEmpty()) lines.removeAt(0)
            lines.add(newLine())
            return
        }
        lines.add(newLine())
        trim()
        // Keep the buffer at least `rows` tall; dropping the oldest is fine.
        while (lines.size > maxLines) lines.removeAt(0)
        if (lines.size < rows) lines.add(newLine())
    }

    private fun reverseIndex() {
        if (cursorY == 0) {
            // Insert a blank line at the top of the screen.
            val at = screenTop
            lines.add(at, newLine())
            if (altActive) {
                // No scrollback on the alt screen: the bottom line falls off.
                if (lines.size > rows) lines.removeAt(lines.size - 1)
            } else {
                trim()
            }
        } else {
            cursorY--
        }
    }

    private fun eraseToEndOfLine() {
        val line = lineAt(cursorAbs())
        for (x in cursorX until cols) {
            line.chars[x] = ' '
            line.fg[x] = COLOR_DEFAULT
            line.bg[x] = COLOR_DEFAULT
            line.flags[x] = 0
            line.cont[x] = false
        }
    }

    private fun eraseFromStartOfLine() {
        val line = lineAt(cursorAbs())
        for (x in 0..cursorX.coerceAtMost(cols - 1)) {
            line.chars[x] = ' '
            line.fg[x] = COLOR_DEFAULT
            line.bg[x] = COLOR_DEFAULT
            line.flags[x] = 0
            line.cont[x] = false
        }
    }

    private fun eraseFromCursorToEnd() {
        eraseToEndOfLine()
        for (y in cursorY + 1 until rows) lines[screenTop + y].clear(cols)
    }

    private fun eraseFromStartToCursor() {
        eraseFromStartOfLine()
        for (y in 0 until cursorY) lines[screenTop + y].clear(cols)
    }

    /** Clear the visible screen (keeps scrollback). */
    fun clearScreen() {
        for (y in 0 until rows) lines[screenTop + y].clear(cols)
        cursorX = 0
        cursorY = 0
    }

    /** Drop everything, including scrollback and any alternate screen. */
    fun reset() {
        lines.clear()
        primary = null
        altActive = false
        savedCursor = null
        isCursorVisible = true
        ensureScreen()
        cursorX = 0
        cursorY = 0
        curFg = COLOR_DEFAULT
        curBg = COLOR_DEFAULT
        curFlags = 0
        state = State.NORMAL
    }
}
