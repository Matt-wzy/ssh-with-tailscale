// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal gzip-tar extractor for the archives produced by [RemoteArchive].
 *
 * We deliberately do not pull in a tar library: ustar plus the two vendor
 * extensions that matter in practice (GNU long-name 'L' and POSIX pax 'x'
 * headers) cover what `tar` on a Linux server emits, and the format is small
 * enough to implement in one file. Symlinks/hardlinks are skipped.
 */
object TarExtractor {

    private const val BLOCK = 512

    /** Sanity ceiling for 'L'/pax metadata blocks (see [readBytes]). */
    private const val MAX_META_BYTES = 1 shl 20

    /**
     * Extract [archive] into [destDir]. Returns the number of regular files
     * written. Throws [SecurityException] if an entry tries to escape
     * [destDir] (path traversal).
     */
    @Throws(IOException::class)
    fun extract(archive: File, destDir: File): Int {
        destDir.mkdirs()
        val root = destDir.canonicalPath
        // extractTo only invokes [openOut] for regular files; parent directories
        // are created lazily, so we just map each entry to a real FileOutputStream.
        return extractTo(archive) { rel ->
            val f = childOf(destDir, root, rel)
            f.parentFile?.mkdirs()
            FileOutputStream(f)
        }
    }

    /**
     * Streaming variant: for every regular file in [archive] call
     * [openOut](relPath) to obtain an [OutputStream] and write the bytes into
     * it. [openOut] is responsible for creating parent directories. Directory
     * entries are skipped (created lazily when a file under them is written),
     * which is acceptable because empty directories are rarely meaningful here.
     *
     * Returns the number of regular files written. The caller must enforce any
     * path-traversal guard inside [openOut] for the `File`-based case.
     */
    @Throws(IOException::class)
    fun extractTo(archive: File, openOut: (String) -> OutputStream): Int {
        var count = 0
        GZIPInputStream(FileInputStream(archive)).use { gz ->
            val input = BufferedInputStream(gz)
            var pendingName: String? = null
            while (true) {
                val header = ByteArray(BLOCK)
                if (!readExact(input, header)) break
                if (header.all { it == 0.toByte() }) break // zero block = end of archive

                val type = header[156].toInt().toChar()
                val size = parseNumeric(header, 124, 12)
                val name = headerName(header, pendingName).also { pendingName = null }

                when (type) {
                    'L' -> { // GNU long name: the data block *is* the name
                        pendingName = readString(input, size).trimEnd('\u0000')
                        skip(input, pad(size))
                        continue
                    }
                    'x', 'g' -> { // POSIX pax: key=value records in the data.
                        pendingName = parsePaxPath(readBytes(input, size))
                        skip(input, pad(size))
                        continue
                    }
                }

                when (type) {
                    '0', 0.toChar() -> { // regular file
                        openOut(name).use { copyExact(input, it, size) }
                        count++
                    }
                    else -> Unit // directory / symlink / device: ignore the data
                }
                // Every entry's data (file bytes, or 0) is followed by block
                // padding; consume it so the next header reads cleanly.
                skip(input, pad(size))
            }
        }
        return count
    }

    /** Resolve [name] (archive-relative) safely inside [destDir]. */
    private fun childOf(destDir: File, root: String, name: String): File {
        val f = File(destDir, name)
        val fp = f.canonicalPath
        if (fp != root && !fp.startsWith("$root${File.separator}")) {
            throw SecurityException("tar 条目越界，已拒绝：$name")
        }
        return f
    }

    private fun headerName(header: ByteArray, override: String?): String {
        if (override != null) return override
        val name = String(header, 0, 100, Charsets.UTF_8).trim('\u0000')
        // ustar/pax carry a dir-name prefix in bytes 345..500.
        val magic = String(header, 257, 5, Charsets.US_ASCII)
        return if (magic.startsWith("ustar")) {
            val prefix = String(header, 345, 155, Charsets.UTF_8).trim('\u0000')
            if (prefix.isEmpty()) name else "$prefix/$name"
        } else name
    }

    /** Octal, or base-256 when the high bit is set (large-file GNU extension). */
    private fun parseNumeric(b: ByteArray, off: Int, len: Int): Long {
        if (len == 0) return 0
        if ((b[off].toInt() and 0x80) != 0) {
            var v = (b[off].toInt() and 0x7f).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toInt() and 0xff).toLong()
            return v
        }
        val s = String(b, off, len, Charsets.US_ASCII).trim().trim('\u0000')
        return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: 0
    }

    /** Pull "path=..." out of a pax extended-header record. */
    private fun parsePaxPath(data: ByteArray): String? {
        for (line in String(data, Charsets.UTF_8).split('\n')) {
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val kv = line.substring(sp + 1)
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            if (kv.substring(0, eq) == "path") return kv.substring(eq + 1)
        }
        return null
    }

    private fun readExact(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        // Only GNU 'L' and pax 'x' metadata lands here (file data goes through
        // copyExact). A real long name or pax header is a few hundred bytes; a
        // crafted header claiming gigabytes would OOM the phone on allocation.
        if (size > MAX_META_BYTES) {
            throw IOException("tar 元数据块过大（$size 字节），压缩包疑似损坏")
        }
        val buf = ByteArray(size.toInt())
        return if (readExactInto(input, buf, buf.size)) buf else ByteArray(0)
    }

    private fun readString(input: InputStream, size: Long): String =
        String(readBytes(input, size), Charsets.UTF_8)

    /** Read exactly [size] bytes of file data into [out]. */
    private fun copyExact(input: InputStream, out: OutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            if (!readExactInto(input, buf, toRead)) break
            out.write(buf, 0, toRead)
            remaining -= toRead
        }
    }

    /** Discard [n] bytes from the stream (entry data or block padding). */
    private fun skip(input: InputStream, n: Int) {
        var left = n
        val buf = ByteArray(4096)
        while (left > 0) {
            val toRead = minOf(buf.size, left)
            if (!readExactInto(input, buf, toRead)) break
            left -= toRead
        }
    }

    private fun readExactInto(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun pad(size: Long): Int = ((BLOCK - (size % BLOCK).toInt()) % BLOCK)
    private fun minOf(a: Int, b: Int): Int = if (a < b) a else b
    private fun minOf(a: Long, b: Long): Long = if (a < b) a else b
}
