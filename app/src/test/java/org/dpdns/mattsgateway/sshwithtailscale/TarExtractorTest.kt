// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Exercises [TarExtractor] against archives produced by the real `tar` command,
 * covering a nested tree and a GNU long-name entry (>100 chars) -- the two
 * vendor extensions our parser special-cases.
 */
class TarExtractorTest {

    private fun haveTar(): Boolean =
        runCatching { ProcessBuilder("tar", "--version").start().waitFor() }
            .getOrDefault(1) == 0

    @Test
    fun extractsNestedTree() {
        assumeTrue("tar not available", haveTar())
        val dir = createTempDir("tarx")
        File(dir, "a.txt").writeText("hello")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.txt").writeText("world")
        File(dir, "c.bin").writeBytes(byteArrayOf(0, 1, 2, 3))
        val archive = File(dir, "bundle.tar.gz")

        val p = ProcessBuilder(
            "tar", "-czf", archive.absolutePath,
            "-C", dir.absolutePath, "a.txt", "sub", "c.bin"
        ).redirectErrorStream(true).start()
        assertEquals("tar failed: ${p.inputStream.bufferedReader().readText()}", 0, p.waitFor())

        val out = File(dir, "out").also { it.mkdirs() }
        val n = TarExtractor.extract(archive, out)

        assertEquals(3, n)
        assertEquals("hello", File(out, "a.txt").readText())
        assertEquals("world", File(out, "sub/b.txt").readText())
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), File(out, "c.bin").readBytes())
    }

    @Test
    fun extractsGnuLongName() {
        assumeTrue("tar not available", haveTar())
        val dir = createTempDir("tarlong")
        val longName = "a_very_long_file_name_that_exceeds_one_hundred_characters_to_force_gnu_long_name_extension_in_tar_headers.txt"
        assertTrue("test fixture name too short", longName.length > 100)
        File(dir, longName).writeText("long")
        val archive = File(dir, "long.tar.gz")

        val p = ProcessBuilder(
            "tar", "-czf", archive.absolutePath,
            "-C", dir.absolutePath, longName
        ).redirectErrorStream(true).start()
        assertEquals("tar failed: ${p.inputStream.bufferedReader().readText()}", 0, p.waitFor())

        val out = File(dir, "out").also { it.mkdirs() }
        val n = TarExtractor.extract(archive, out)

        assertEquals(1, n)
        assertEquals("long", File(out, longName).readText())
    }
}
