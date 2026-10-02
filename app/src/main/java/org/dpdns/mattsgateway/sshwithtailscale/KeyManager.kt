// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context
import java.io.File

/**
 * Stores SSH private keys (PEM) in the app's private files directory.
 *
 * Files created with Context.MODE_PRIVATE are only readable by this app, which
 * is the correct place for key material on Android without root.
 */
object KeyManager {

    private fun dir(context: Context): File =
        File(context.filesDir, "keys").apply { mkdirs() }

    fun list(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension == "pem" }
            ?.map { it.nameWithoutExtension }
            ?.sorted() ?: emptyList()

    /** Save (or overwrite) a key under [name]. Returns the stored file. */
    fun save(context: Context, name: String, pem: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "id_rsa" }
        val f = File(dir(context), "$safe.pem")
        f.writeText(pem)
        return f
    }

    fun load(context: Context, name: String): String? =
        runCatching { File(dir(context), "$name.pem").readText() }.getOrNull()

    fun delete(context: Context, name: String): Boolean =
        File(dir(context), "$name.pem").delete()

    /** Very cheap sanity check so the UI can warn early. */
    fun looksLikePem(pem: String): Boolean =
        pem.contains("PRIVATE KEY")
}
