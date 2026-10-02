// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.dpdns.mattsgateway.sshwithtailscale.BuildConfig
import kotlin.math.abs
import kotlin.math.max

/**
 * Renders a [TerminalBuffer] and is itself the IME target.
 *
 * The view implements [onCreateInputConnection] (the way Termux does) rather
 * than relaying keystrokes through an off-screen EditText. With a real input
 * connection the system shows the soft keyboard as soon as the view is focused,
 * whereas calling InputMethodManager.showSoftInput() on a hidden 1dp EditText is
 * silently ignored on several ROMs, MIUI included -- which made the terminal
 * impossible to type into.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var buffer = TerminalBuffer(cols = 80, rows = 24)
        private set

    /** Raw bytes; used by the modifier / arrow-key buttons. */
    var onInput: ((ByteArray) -> Unit)? = null

    /** Committed text typed on the soft keyboard. */
    var onTextInput: ((String) -> Unit)? = null

    /** Called when the view is tapped, so the Activity can help show the IME. */
    var onFocusRequest: (() -> Unit)? = null

    /**
     * Called with the new (cols, rows) whenever the buffer is resized.
     *
     * The SSH session must be told, otherwise the server keeps wrapping output
     * at the old width while this buffer wraps at the new one -- the result is
     * overlapping, apparently scrambled text (and typed characters look like
     * they produce no feedback, because they land on a garbled line).
     */
    var onResized: ((cols: Int, rows: Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 30f
    }
    private val bgPaint = Paint()

    private var charWidth = 0f
    private var charHeight = 0f
    private var scrollOffset = 0 // lines scrolled back from the bottom

    // ----------------------------------------------------- text selection

    /** Notified when a selection becomes active (true) or is cleared (false). */
    var onSelectionChanged: ((active: Boolean) -> Unit)? = null

    private val selectionHandler = Handler(Looper.getMainLooper())

    /** Fires after a press is held still long enough to mean "start selecting". */
    private val longPressRunnable = Runnable { startSelectionAt(downX, downY) }

    /** Touch where the current gesture began (used by the long-press anchor). */
    private var downX = 0f
    private var downY = 0f

    /** True once the finger moved enough to count as a scroll, not a tap. */
    private var movedBeyondSlop = false

    /** When true, drags extend the selection instead of scrolling. */
    private var selectionMode = false

    /** [col, absoluteLine] for the two ends of the selected block. */
    private var selStart: IntArray? = null
    private var selEnd: IntArray? = null

    /** True between the long-press that opened selection and the finger lift. */
    private var draggingSelection = false

    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG)


    // Normal and bright ANSI colours, as ARGB ints. They must go through
    // .toInt(): Kotlin types 0xFFxxxxxx as an unsigned Int that does not fit in
    // a signed Int literal.
    private val palette = intArrayOf(
        0xFF3B3B3B.toInt(), // 0 black
        0xFFCC4040.toInt(), // 1 red
        0xFF3FBF6F.toInt(), // 2 green
        0xFFC7B041.toInt(), // 3 yellow
        0xFF4A8BE0.toInt(), // 4 blue
        0xFFB060C0.toInt(), // 5 magenta
        0xFF3FB8C7.toInt(), // 6 cyan
        0xFFC8C8C8.toInt(), // 7 white
    )
    private val brightPalette = intArrayOf(
        0xFF808080.toInt(),
        0xFFFF6B6B.toInt(),
        0xFF5BE08B.toInt(),
        0xFFFFE066.toInt(),
        0xFF7BB4FF.toInt(),
        0xFFE08BEE.toInt(),
        0xFF6FD8E8.toInt(),
        0xFFFFFFFF.toInt(),
    )

    // These must be readable on a near-black background. The previous defaultFg
    // was 0xFF212121 -- dark grey on black, effectively invisible (reported).
    private val defaultFg = 0xFFE0E0E0.toInt()
    private val defaultBg = 0xFF101010.toInt()

    init {
        // The terminal *is* the text editor, so it must be able to take focus in
        // touch mode; the system then shows the IME on its own.
        isFocusable = true
        isFocusableInTouchMode = true
        if (BuildConfig.DEBUG) {
            // Log every control sequence the parser applies, so a "the cursor
            // jumped" report can be traced back to what the server sent.
            // (One line per control byte, hence debug-only.)
            buffer.trace = { Log.d("TermTrace", it) }
        }
    }

    // ------------------------------------------------------------ cursor blink

    private var cursorVisible = true

    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            if (isAttachedToWindow && hasFocus()) postDelayed(this, BLINK_MS)
        }
    }

    /**
     * Blink only while focused. When focus goes elsewhere the cursor is left
     * solidly visible (restarting the blink would be pointless and a missing
     * cursor is worse than a still one).
     */
    private fun updateBlink() {
        removeCallbacks(blinkRunnable)
        cursorVisible = true
        if (isAttachedToWindow && hasFocus()) postDelayed(blinkRunnable, BLINK_MS)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBlink()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
        selectionHandler.removeCallbacks(longPressRunnable)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        updateBlink()
    }

    // ---------------------------------------------------------------- IME

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TermInputConnection(this)
    }

    private inner class TermInputConnection(target: View) :
        BaseInputConnection(target, false) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) {
                // The IME delivers Enter as a newline through commitText. A PTY
                // (and full-screen programs in raw mode, e.g. vim) expect CR.
                val s = text.toString().replace("\n", "\r")
                Log.d(TAG, "commitText: ${s.length} char(s)")
                onTextInput?.invoke(s)
            }
            return true
        }

        // Swallow composition: a shell wants committed characters only, and
        // echoing each intermediate composing string would duplicate input.
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = true
        override fun finishComposingText() = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Backspace arrives here, not as a key event.
            Log.d(TAG, "deleteSurroundingText(before=$beforeLength, after=$afterLength)")
            repeat(beforeLength.coerceAtLeast(0)) { send(byteArrayOf(DEL)) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            Log.d(TAG, "sendKeyEvent(keyCode=${event.keyCode})")
            return handleKey(event.keyCode, event.unicodeChar)
        }
    }

    /**
     * Map a key to the bytes a terminal expects.
     *
     * Shared by the IME's key events and [onKeyDown], so a Bluetooth keyboard
     * behaves exactly like the on-screen modifier row.
     */
    private fun handleKey(keyCode: Int, unicode: Int): Boolean {
        Log.d(TAG, "handleKey(keyCode=$keyCode, unicode=$unicode)")
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> send(byteArrayOf(DEL))
            KeyEvent.KEYCODE_FORWARD_DEL ->
                send(byteArrayOf(ESC, BRACKET, '3'.code.toByte(), '~'.code.toByte()))
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> send(byteArrayOf(CR))
            KeyEvent.KEYCODE_TAB -> send(byteArrayOf(TAB))
            KeyEvent.KEYCODE_ESCAPE -> send(byteArrayOf(ESC))
            KeyEvent.KEYCODE_DPAD_UP -> send(byteArrayOf(ESC, BRACKET, 'A'.code.toByte()))
            KeyEvent.KEYCODE_DPAD_DOWN -> send(byteArrayOf(ESC, BRACKET, 'B'.code.toByte()))
            KeyEvent.KEYCODE_DPAD_RIGHT -> send(byteArrayOf(ESC, BRACKET, 'C'.code.toByte()))
            KeyEvent.KEYCODE_DPAD_LEFT -> send(byteArrayOf(ESC, BRACKET, 'D'.code.toByte()))
            else -> {
                if (unicode == 0) return false
                val s = String(Character.toChars(unicode))
                if (s == "\n") send(byteArrayOf(CR)) else onTextInput?.invoke(s)
            }
        }
        return true
    }

    /**
     * Physical keyboards (and injected key events) never reach the IME, so they
     * are handled here. Text typed on the soft keyboard does not come through
     * this path -- it arrives as commitText -- so there is no double delivery.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d(TAG, "onKeyDown(keyCode=$keyCode, ctrl=${event.isCtrlPressed}, " +
            "alt=${event.isAltPressed}, unicode=${event.unicodeChar})")
        if (event.isCtrlPressed || event.isAltPressed) {
            // Ctrl/Alt combinations stay the modifier row's job.
            return super.onKeyDown(keyCode, event)
        }
        return handleKey(keyCode, event.unicodeChar) || super.onKeyDown(keyCode, event)
    }

    fun textSizePx(px: Float) {
        paint.textSize = px.coerceIn(MIN_FONT_PX, MAX_FONT_PX)
        measureChars()
        requestLayout()
        invalidate()
    }

    /** The size a fresh view uses, so callers can persist/compare sensibly. */
    fun defaultFontSizePx(): Float = DEFAULT_FONT_SIZE_PX

    // ---------------------------------------------------------- pinch zoom

    /** Invoked when a pinch gesture ends, with the final font size in px. */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (paint.textSize * detector.scaleFactor)
                    .coerceIn(MIN_FONT_PX, MAX_FONT_PX)
                if (next != paint.textSize) textSizePx(next)
                return true
            }
        }
    )

    private fun measureChars() {
        charWidth = paint.measureText("M")
        val fm = paint.fontMetrics
        charHeight = (fm.descent - fm.ascent)
    }

    fun resizeToFit() {
        if (charWidth <= 0f || charHeight <= 0f) measureChars()
        val c = max(20, (width / charWidth).toInt())
        val r = max(4, (height / charHeight).toInt())
        if (c != buffer.cols || r != buffer.rows) {
            buffer.setSize(c, r)
            onResized?.invoke(c, r)
        }
    }

    /** Append data coming from the SSH PTY. */
    fun append(text: String) {
        buffer.feed(text)
        postInvalidate()
    }

    fun clear() {
        buffer.reset()
        scrollOffset = 0
        postInvalidate()
    }

    /** Send raw bytes (used by the modifier key row, arrow keys, etc.). */
    fun send(bytes: ByteArray) = onInput?.invoke(bytes)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureChars()
        resizeToFit()
        // setSize() may have moved (or re-clamped) the cursor; make sure the new
        // position is painted rather than waiting for the next blink/append.
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(defaultBg)

        if (charWidth <= 0f) measureChars()

        val rows = buffer.rows
        val visible = buffer.lineCount
        val firstLine = firstVisibleLine()

        // Selection highlight is painted behind the glyphs so the text stays
        // readable on top of it.
        if (selectionMode && selStart != null && selEnd != null) {
            val top = if (selStart!![1] <= selEnd!![1]) selStart!! else selEnd!!
            val bot = if (selStart!![1] <= selEnd!![1]) selEnd!! else selStart!!
            selPaint.color = SEL_COLOR
            for (y in 0 until rows) {
                val abs = firstLine + y
                if (abs < top[1] || abs > bot[1]) continue
                val cs = if (abs == top[1]) top[0] else 0
                val ce = if (abs == bot[1]) bot[0] else (buffer.cols - 1)
                if (ce < cs) continue
                canvas.drawRect(
                    cs * charWidth, y * charHeight,
                    (ce + 1) * charWidth, (y + 1) * charHeight, selPaint
                )
            }
        }

        fun paintBg(line: TerminalBuffer.Line, start: Int, end: Int, y: Int) {
            val bg = line.bg[start]
            if (bg != TerminalBuffer.COLOR_DEFAULT) {
                bgPaint.color = colorFor(bg, false)
                canvas.drawRect(
                    start * charWidth, y * charHeight,
                    end * charWidth, (y + 1) * charHeight, bgPaint
                )
            }
        }

        fun applyPaint(line: TerminalBuffer.Line, col: Int) {
            val fg = line.fg[col]
            val fl = line.flags[col]
            paint.color = if (fg == TerminalBuffer.COLOR_DEFAULT) defaultFg else colorFor(fg, true)
            paint.isFakeBoldText = (fl.toInt() and TerminalBuffer.FLAG_BOLD.toInt()) != 0
            paint.isUnderlineText = (fl.toInt() and TerminalBuffer.FLAG_UNDERLINE.toInt()) != 0
        }

        for (y in 0 until rows) {
            val idx = firstLine + y
            if (idx >= visible) break
            val line = buffer.lineAt(idx)
            val baseline = y * charHeight - paint.fontMetrics.ascent

            var col = 0
            while (col < buffer.cols) {
                // Trailing half of a wide glyph: the glyph to its left already
                // covers this cell.
                if (line.cont[col]) {
                    col++
                    continue
                }

                if (col + 1 < buffer.cols && line.cont[col + 1]) {
                    // A double-width character owns two cells. Paint it in its
                    // own drawText call: the monospace font advances two cells
                    // for it, so leaving it inside a longer string would shift
                    // everything after it by one column.
                    paintBg(line, col, col + 2, y)
                    applyPaint(line, col)
                    // A surrogate pair is one astral glyph (usually an emoji)
                    // and needs both chars to render.
                    val n = if (line.chars[col].isHighSurrogate() &&
                        line.chars[col + 1].isLowSurrogate()
                    ) 2 else 1
                    canvas.drawText(
                        String(line.chars, col, n), col * charWidth, baseline, paint
                    )
                    col += 2
                    continue
                }

                // Narrow cells with identical attributes are batched into a
                // single drawText, but the run must stop before a wide char.
                var end = col + 1
                while (end < buffer.cols && !line.cont[end] &&
                    !(end + 1 < buffer.cols && line.cont[end + 1]) &&
                    line.fg[end] == line.fg[col] &&
                    line.bg[end] == line.bg[col] &&
                    line.flags[end] == line.flags[col]
                ) {
                    end++
                }
                paintBg(line, col, end, y)
                applyPaint(line, col)
                canvas.drawText(
                    String(line.chars, col, end - col), col * charWidth, baseline, paint
                )
                col = end
            }
        }

        // Cursor (only when scrolled to the bottom, only while it is "on" --
        // it blinks -- and only while the app has not hidden it, CSI ?25l).
        if (scrollOffset == 0 && cursorVisible && buffer.isCursorVisible) {
            val cy = buffer.cursorY
            var cx = buffer.cursorX.coerceIn(0, buffer.cols - 1)
            // On the trailing half of a wide glyph, cover the whole glyph.
            if (buffer.isContinuation(cy, cx) && cx > 0) cx--
            val wide = buffer.isWideHead(cy, cx)
            val end = cx + if (wide) 2 else 1

            paint.isFakeBoldText = false
            paint.isUnderlineText = false
            paint.color = defaultFg
            canvas.drawRect(
                cx * charWidth, cy * charHeight,
                end * charWidth, (cy + 1) * charHeight, paint
            )

            // Repaint the character underneath in the background colour. A solid
            // block hid whatever it sat on, so you could not tell which
            // character the cursor was on -- which is exactly what you need to
            // know while moving around with the arrow keys.
            val line = buffer.lineAt(buffer.screenTop + cy)
            if (cx < line.chars.size && line.chars[cx] != ' ') {
                val n = if (line.chars[cx].isHighSurrogate() && cx + 1 < line.chars.size &&
                    line.chars[cx + 1].isLowSurrogate()
                ) 2 else 1
                paint.color = defaultBg
                canvas.drawText(
                    String(line.chars, cx, n),
                    cx * charWidth,
                    cy * charHeight - paint.fontMetrics.ascent,
                    paint
                )
            }
        }
    }

    private fun colorFor(code: Int, foreground: Boolean): Int {
        if (code < 0) return if (foreground) defaultFg else defaultBg
        // Extended colours (256-colour cube, 24-bit true colour) arrive already
        // packed as RGB by the buffer.
        if (code and TerminalBuffer.COLOR_RGB_TAG != 0) {
            return 0xFF000000.toInt() or (code and 0xFFFFFF)
        }
        val bright = code >= 90
        val idx = when {
            code in 30..37 -> code - 30
            code in 90..97 -> code - 90
            code in 40..47 -> code - 40
            code in 100..107 -> code - 100
            else -> (code % 10).coerceIn(0, 7)
        }
        return if (foreground && bright) brightPalette[idx] else palette[idx]
    }

    // ------------------------------------------------------------- scrolling

    private var lastTouchY = 0f

    /**
     * Two gestures share this view:
     *  - a short tap focuses the terminal and pops the IME;
     *  - a scroll drags the scrollback;
     *  - a long-press (hold still ~350ms) opens text selection, after which the
     *    same drag extends the selection. Dragging past the top/bottom edge
     *    scrolls the scrollback so you can select text that is not on screen.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Two fingers are a pinch-zoom, never a scroll or a selection drag.
        // (Termux-style: this is the only way to reach a comfortable font size
        // on a phone; the buttons alone cannot do it.)
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            selectionHandler.removeCallbacks(longPressRunnable)
            movedBeyondSlop = true
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) {
                    // The second-to-last finger lifted: the gesture is over.
                    onFontSizeChanged?.invoke(paint.textSize)
                }
                MotionEvent.ACTION_CANCEL ->
                    onFontSizeChanged?.invoke(paint.textSize)
            }
            return true
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                movedBeyondSlop = false
                lastTouchY = event.y
                if (selectionMode) {
                    // Start a fresh selection anchored where the finger went down.
                    startSelectionAt(event.x, event.y)
                } else {
                    selectionHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectionMode && draggingSelection) {
                    updateSelEnd(event.x, event.y)
                } else if (!selectionMode) {
                    val dy = event.y - lastTouchY
                    if (abs(dy) > charHeight) {
                        // A move before the long-press fires means "scroll", not
                        // "select": drop the pending long-press and scroll.
                        selectionHandler.removeCallbacks(longPressRunnable)
                        val lines = (dy / charHeight).toInt()
                        // Dragging down pulls earlier output into view, i.e. it
                        // increases the offset back from the bottom.
                        scrollOffset = (scrollOffset + lines)
                            .coerceIn(0, max(0, buffer.lineCount - buffer.rows))
                        lastTouchY = event.y
                        movedBeyondSlop = true
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selectionMode && draggingSelection) {
                    draggingSelection = false
                    // A long-press that never dragged selects a single cell --
                    // not useful, so just clear it instead of showing the bar.
                    if (selStart != null && selEnd != null &&
                        selStart!![0] == selEnd!![0] && selStart!![1] == selEnd!![1]
                    ) {
                        exitSelection()
                    } else {
                        onSelectionChanged?.invoke(true)
                    }
                } else if (!selectionMode) {
                    selectionHandler.removeCallbacks(longPressRunnable)
                    if (!movedBeyondSlop) {
                        // Taking focus is what makes the system show the IME.
                        requestFocus()
                        onFocusRequest?.invoke()
                    }
                }
            }
        }
        return true
    }

    // ------------------------------------------------------- selection logic

    private fun firstVisibleLine(): Int =
        max(0, buffer.lineCount - buffer.rows - scrollOffset)

    /** Map a screen point to [col, absoluteLine] using the current scroll. */
    private fun cellFromPoint(x: Float, y: Float): Pair<Int, Int> {
        val col = (x / charWidth).toInt().coerceIn(0, buffer.cols - 1)
        val firstLine = firstVisibleLine()
        val row = (y / charHeight).toInt().coerceIn(0, buffer.rows - 1)
        return col to (firstLine + row)
    }

    private fun startSelectionAt(x: Float, y: Float) {
        selectionMode = true
        draggingSelection = true
        val (c, a) = cellFromPoint(x, y)
        selStart = intArrayOf(c, a)
        selEnd = intArrayOf(c, a)
        invalidate()
        onSelectionChanged?.invoke(true)
    }

    /**
     * Extend the selection to the finger, scrolling the scrollback when the
     * finger rises above the first visible row or below the last.
     */
    private fun updateSelEnd(x: Float, y: Float) {
        val rawRow = (y / charHeight).toInt()
        val maxOff = max(0, buffer.lineCount - buffer.rows)
        if (rawRow < 0) {
            // Finger above the top: scroll back further to reveal older lines.
            scrollOffset = (scrollOffset - rawRow).coerceIn(0, maxOff)
        } else if (rawRow >= buffer.rows) {
            // Finger below the bottom: scroll forward toward the prompt.
            scrollOffset = (scrollOffset - (rawRow - (buffer.rows - 1))).coerceIn(0, maxOff)
        }
        val (c, a) = cellFromPoint(x, y)
        selEnd = intArrayOf(c, a)
        invalidate()
    }

    /** The selected text, one line per terminal row, or "" when nothing is selected. */
    fun getSelectedText(): String {
        val a = selStart ?: return ""
        val b = selEnd ?: return ""
        val top = if (a[1] <= b[1]) a else b
        val bot = if (a[1] <= b[1]) b else a
        val sb = StringBuilder()
        for (abs in top[1]..bot[1]) {
            val line = buffer.lineAt(abs)
            val cs = if (abs == top[1]) top[0] else 0
            val ce = if (abs == bot[1]) bot[0] else (buffer.cols - 1)
            if (ce < cs) continue
            // Trailing spaces (including the second cell of a wide glyph) are
            // noise; trim them so a selection copies clean text.
            sb.append(String(line.chars, cs, ce - cs + 1).trimEnd()).append('\n')
        }
        return sb.toString()
    }

    /** Clear the selection and hide the action bar. */
    fun exitSelection() {
        selectionMode = false
        draggingSelection = false
        selStart = null
        selEnd = null
        selectionHandler.removeCallbacks(longPressRunnable)
        invalidate()
        onSelectionChanged?.invoke(false)
    }

    /** Jump back to the live bottom of the terminal. */
    fun scrollToBottom() {
        scrollOffset = 0
        invalidate()
    }

    fun cursorRows(): Int = buffer.rows
    fun cursorCols(): Int = buffer.cols

    private companion object {
        private const val TAG = "TerminalView"

        /** Half-period of the cursor blink. */
        const val BLINK_MS = 500L

        /** How long a press must be held still before it opens text selection. */
        const val LONG_PRESS_MS = 350L

        /** Opaque blue used behind selected terminal text. */
        const val SEL_COLOR = 0xFF1565C0.toInt()

        const val DEL: Byte = 0x7f
        const val CR: Byte = 0x0d
        const val TAB: Byte = 0x09
        const val ESC: Byte = 0x1b
        const val BRACKET: Byte = '['.code.toByte()

        /** Default glyph size; also the reset point for pinch zoom. */
        const val DEFAULT_FONT_SIZE_PX = 30f
        const val MIN_FONT_PX = 12f
        const val MAX_FONT_PX = 80f
    }
}
