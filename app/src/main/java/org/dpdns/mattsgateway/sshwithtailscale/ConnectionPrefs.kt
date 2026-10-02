// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context

/**
 * Remembers the last host/port/user so they do not have to be retyped after
 * every restart.
 *
 * The password is deliberately NOT stored here -- it stays in the field for the
 * lifetime of the process only.
 */
object ConnectionPrefs {
    private const val FILE = "connection"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USER = "user"

    data class Saved(val host: String, val port: String, val user: String)

    fun load(context: Context): Saved {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Saved(
            host = p.getString(KEY_HOST, "").orEmpty(),
            port = p.getString(KEY_PORT, "22").orEmpty().ifBlank { "22" },
            user = p.getString(KEY_USER, "").orEmpty()
        )
    }

    fun save(context: Context, host: String, port: String, user: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, port)
            .putString(KEY_USER, user)
            .apply()
    }
}
