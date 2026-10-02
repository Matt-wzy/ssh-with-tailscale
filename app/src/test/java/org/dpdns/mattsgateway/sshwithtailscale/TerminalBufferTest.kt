// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import org.dpdns.mattsgateway.sshwithtailscale.terminal.TerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the ANSI/VT100 screen buffer.
 *
 * TerminalBuffer has no Android dependencies on purpose, so these run on a
 * plain JVM with `./gradlew testDebugUnitTest`.
 */
class TerminalBufferTest {

    private fun lineText(b: TerminalBuffer, y: Int): String {
        val idx = b.screenTop + y
        return String(b.lineAt(idx).chars).trimEnd()
    }

    @Test
    fun plainTextIsWrittenAndCursorAdvances() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("hello")
        assertEquals("hello", lineText(b, 0))
        assertEquals(5, b.cursorX)
        assertEquals(0, b.cursorY)
    }

    @Test
    fun newlineAndCarriageReturnMoveCursor() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("ab\r\ncd")
        assertEquals("ab", lineText(b, 0))
        assertEquals("cd", lineText(b, 1))
    }

    @Test
    fun backspaceMovesLeftWithoutErasing() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("abc\b\bX")
        // After "abc" the cursor sits at column 3; two backspaces leave it at
        // column 1, so 'X' overwrites 'b' -> "aXc".
        assertEquals("aXc", lineText(b, 0))
        assertEquals(2, b.cursorX)
    }

    @Test
    fun clearScreenErasesVisibleLines() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("one\r\ntwo\u001b[2J")
        assertEquals("", lineText(b, 0))
        assertEquals("", lineText(b, 1))
    }

    @Test
    fun eraseToEndOfLineOnlyClearsFromCursor() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("abcdef")
        b.feed("\u001b[3G") // move to column 3 (1-based) -> index 2
        b.feed("\u001b[K")
        assertEquals("ab", lineText(b, 0))
    }

    @Test
    fun cursorPositionSequencePlacesCursor() {
        val b = TerminalBuffer(cols = 10, rows = 4)
        b.feed("\u001b[3;2H") // row 3, col 2 -> (2,1)
        b.feed("Z")
        assertEquals("Z", lineText(b, 2).trim())
        assertEquals(2, b.cursorY)
    }

    @Test
    fun sgrColorIsStoredPerCell() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("\u001b[31mR\u001b[0mN")
        val line = b.lineAt(b.screenTop)
        assertEquals(31, line.fg[0])
        assertEquals(TerminalBuffer.COLOR_DEFAULT, line.fg[1])
    }

    @Test
    fun boldFlagIsStored() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("\u001b[1mB")
        val line = b.lineAt(b.screenTop)
        assertTrue((line.flags[0].toInt() and TerminalBuffer.FLAG_BOLD.toInt()) != 0)
    }

    @Test
    fun scrollAddsLinesAndKeepsScreenFull() {
        val b = TerminalBuffer(cols = 5, rows = 3)
        repeat(6) { b.feed("line$it\r\n") }
        assertEquals(3, b.rows)
        assertTrue("buffer should have grown past the screen", b.lineCount > 3)
        // The trailing \r\n scrolls: "line5" moves up and a fresh blank line is
        // left at the bottom with the cursor on it (standard terminal behaviour).
        assertEquals("", lineText(b, b.rows - 1))
        assertEquals("line5", lineText(b, b.rows - 2))
    }

    @Test
    fun resizePreservesExistingContent() {
        val b = TerminalBuffer(cols = 12, rows = 3)
        b.feed("keepme")
        b.setSize(6, 3)
        assertEquals(6, b.cols)
        // "keepme" is exactly 6 chars, so it survives a resize to width 6 intact.
        assertEquals("keepme", lineText(b, 0))
    }

    @Test
    fun tabStopsAlignToEightColumns() {
        val b = TerminalBuffer(cols = 24, rows = 2)
        b.feed("a\tb")
        assertEquals("a", lineText(b, 0).substring(0, 1))
        val line = b.lineAt(b.screenTop)
        assertEquals('b', line.chars[8])
    }

    // ------------------------------------------------- double-width characters

    @Test
    fun cjkCharacterTakesTwoColumns() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("中")
        val line = b.lineAt(b.screenTop)
        assertEquals('中', line.chars[0])
        // The trailing cell is marked as the glyph's second half...
        assertTrue(line.cont[1])
        // ...and the cursor counted two columns, matching what the server does.
        assertEquals(2, b.cursorX)
    }

    @Test
    fun textAfterCjkStartsTwoColumnsLater() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("中a")
        val line = b.lineAt(b.screenTop)
        assertEquals('a', line.chars[2])
        assertEquals(3, b.cursorX)
    }

    @Test
    fun cjkWrapsInsteadOfBeingSplitAcrossLines() {
        // 3 columns: "ab" leaves one column, not enough for a 2-column glyph.
        val b = TerminalBuffer(cols = 3, rows = 3)
        b.feed("ab中")
        assertEquals("ab", lineText(b, 0))
        assertEquals("中", lineText(b, 1))
    }

    @Test
    fun overwritingTheTrailingCellErasesTheWholeGlyph() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("中")
        b.feed("\u001b[2G") // column 2 (1-based) -> index 1, the trailing half
        b.feed("X")
        val line = b.lineAt(b.screenTop)
        // The old glyph would otherwise still be painted over the new cell.
        assertEquals(' ', line.chars[0])
        assertEquals('X', line.chars[1])
    }

    @Test
    fun emojiSurrogatePairTakesTwoColumns() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("\uD83D\uDE00a") // 😀 then 'a'
        val line = b.lineAt(b.screenTop)
        assertEquals('\uD83D', line.chars[0])
        assertEquals('\uDE00', line.chars[1])
        assertTrue(line.cont[1])
        assertEquals('a', line.chars[2])
        assertEquals(3, b.cursorX)
    }

    @Test
    fun resizeKeepsWideCellMarkers() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("中")
        b.setSize(8, 2)
        val line = b.lineAt(b.screenTop)
        assertEquals('中', line.chars[0])
        assertTrue(line.cont[1])
    }

    // ------------------------------------------------- 8-bit (C1) controls

    @Test
    fun eightBitCsiIsHandledLikeEscapeBracket() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("abcd")
        assertEquals(4, b.cursorX)
        b.feed("\u009bD") // 8-bit CSI + 'D' == ESC [ D, cursor left
        assertEquals(3, b.cursorX)
    }

    @Test
    fun eightBitControlsAreDroppedNotPrinted() {
        // A terminal description advertising 8-bit controls sends this where a
        // normal one sends ESC-equivalents. Printing it advanced the cursor.
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("a\u0090b")
        assertEquals(2, b.cursorX)
        assertEquals("ab", lineText(b, 0))
    }

    // --------------------------------------------------- OSC string handling

    @Test
    fun oscTerminatedByBelIsSwallowedNotPrinted() {
        // bash puts ESC ] 0 ; title BEL in PS1; printing the payload duplicated
        // the prompt on screen and pushed the cursor along.
        val b = TerminalBuffer(cols = 40, rows = 2)
        b.feed("\u001b]0;matt@host: ~ \u0007abc")
        assertEquals("abc", lineText(b, 0))
        assertEquals(3, b.cursorX)
    }

    @Test
    fun oscTerminatedByEscapeBackslashIsSwallowedNotPrinted() {
        val b = TerminalBuffer(cols = 40, rows = 2)
        b.feed("\u001b]0;a title\u001b\\xyz")
        assertEquals("xyz", lineText(b, 0))
        assertEquals(3, b.cursorX)
    }

    @Test
    fun dcsStringIsSwallowedNotPrinted() {
        val b = TerminalBuffer(cols = 40, rows = 2)
        b.feed("\u001bP1;2|payload\u001b\\ok")
        assertEquals("ok", lineText(b, 0))
    }

    // -------------------------------------------------------- resize + cursor

    @Test
    fun growingRowsKeepsCursorOnTheSameLogicalLine() {
        // The soft keyboard hiding grows the view. The screen is just the last
        // `rows` lines, so the cursor must be re-derived to stay on the line it
        // was on; previously its screen row was left untouched and it drifted.
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("a\r\nb\r\nc")
        val absBefore = b.screenTop + b.cursorY
        b.setSize(10, 6)
        assertEquals(absBefore, b.screenTop + b.cursorY)
        assertEquals("c", lineText(b, b.cursorY))
    }

    @Test
    fun growingRowsWithScrollbackKeepsCursorAtTheBottom() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        // More lines than rows, so there is scrollback above the screen.
        b.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6")
        assertEquals(2, b.cursorY)
        b.setSize(10, 5)
        // Growing reveals more history above; the cursor stays on the last line.
        assertEquals(4, b.cursorY)
        assertEquals("6", lineText(b, b.cursorY))
    }

    @Test
    fun shrinkingRowsKeepsCursorVisible() {
        val b = TerminalBuffer(cols = 10, rows = 6)
        b.feed("a\r\nb\r\nc\r\nd\r\ne\r\nf")
        b.setSize(10, 3)
        assertTrue("cursorY=${b.cursorY}", b.cursorY in 0..2)
        assertEquals(2, b.cursorY)
        assertEquals("f", lineText(b, b.cursorY))
    }

    // ------------------------------------------------------- alternate screen

    @Test
    fun altScreenSwapsContentAndRestoresItOnExit() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("main")
        b.feed("\u001b[?1049h")
        // Fresh blank screen; what we draw now goes onto the alt buffer.
        assertEquals("", lineText(b, 0))
        b.feed("alt")
        assertEquals("alt", lineText(b, 0))
        b.feed("\u001b[?1049l")
        assertEquals("main", lineText(b, 0))
    }

    @Test
    fun altScreenRestoresTheCursorLikeDecsc() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("ab\r\ncd")
        b.feed("\u001b[?1049h")
        b.feed("\u001b[3;3H")
        b.feed("\u001b[?1049l")
        // Exiting vim must land the cursor back where it was, not where the
        // alt-screen app left it.
        assertEquals("cd", lineText(b, b.cursorY))
        assertEquals(2, b.cursorX)
    }

    @Test
    fun altScreenScrollDoesNotGrowTheBuffer() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        val before = b.lineCount
        b.feed("\u001b[?1049h")
        repeat(10) { b.feed("row$it\r\n") }
        assertEquals("alt screen must stay rows tall", before, b.lineCount)
        b.feed("\u001b[?1049l")
    }

    @Test
    fun altScreenResizeResizesBothBuffers() {
        val b = TerminalBuffer(cols = 12, rows = 3)
        b.feed("normal")
        b.feed("\u001b[?1049h")
        b.feed("\u001b[?1049l")
        b.setSize(6, 3)
        assertEquals("normal", lineText(b, 0))
    }

    @Test
    fun plainScreenStillHasScrollback() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        val before = b.lineCount
        repeat(10) { b.feed("row$it\r\n") }
        assertTrue("normal screen must keep scrollback", b.lineCount > before)
    }

    // ---------------------------------------------------- cursor visibility

    @Test
    fun cursorHideAndShowFollowsDecset25() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        assertTrue(b.isCursorVisible)
        b.feed("\u001b[?25l")
        assertTrue(!b.isCursorVisible)
        b.feed("\u001b[?25h")
        assertTrue(b.isCursorVisible)
    }

    // ------------------------------------------------------- DECSC / DECRC

    @Test
    fun escSevenAndEightSaveAndRestoreTheCursor() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        b.feed("abc")
        b.feed("\u001b7") // save at (3,0)
        b.feed("\r\nxyz")
        b.feed("\u001b8") // restore
        assertEquals(3, b.cursorX)
        assertEquals(0, b.cursorY)
    }

    // -------------------------------------------------- extended colours

    @Test
    fun sgr256BasicColoursBecomePlainSgrCodes() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("\u001b[38;5;4mA") // 4 = blue -> 30 + 4
        assertEquals(34, b.lineAt(b.screenTop).fg[0])
        b.feed("\u001b[0m\u001b[38;5;12mB") // bright blue -> 90 + 4
        assertEquals(94, b.lineAt(b.screenTop).fg[1])
    }

    @Test
    fun sgr256CubeColourIsPackedAsRgb() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        // 208 is the classic "orange" of many shell prompts: cube cell 5,2,0.
        b.feed("\u001b[38;5;208mA")
        val expected = TerminalBuffer.COLOR_RGB_TAG or (255 shl 16) or (135 shl 8) or 0
        assertEquals(expected, b.lineAt(b.screenTop).fg[0])
    }

    @Test
    fun sgrTruecolourIsPackedAsRgb() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.feed("\u001b[38;2;12;34;56mA")
        val expected = TerminalBuffer.COLOR_RGB_TAG or (12 shl 16) or (34 shl 8) or 56
        assertEquals(expected, b.lineAt(b.screenTop).fg[0])
        b.feed("\u001b[48;2;1;2;3mB")
        val expectedBg = TerminalBuffer.COLOR_RGB_TAG or (1 shl 16) or (2 shl 8) or 3
        assertEquals(expectedBg, b.lineAt(b.screenTop).bg[1])
    }
}
