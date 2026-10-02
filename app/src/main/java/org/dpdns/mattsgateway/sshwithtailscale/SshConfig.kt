// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import com.jcraft.jsch.Session

/**
 * One set of SSH connection parameters -- exactly what the connection panel
 * collects, captured so the file manager can reuse it instead of making the
 * user retype the host, user and password.
 */
data class SshConfig(
    val host: String,
    val port: Int = 22,
    val user: String = "",
    val password: String = "",
    val privateKeyPem: String = ""
) {
    /** True when there is enough to actually attempt a connection. */
    val usable: Boolean get() = host.isNotBlank() && user.isNotBlank()
}

/**
 * The connection the user has *actually* established, shared with the file
 * manager so it never asks for a password a second time.
 *
 * The password is process-lifetime only -- it mirrors the connection panel's
 * own rule (ConnectionPrefs never persists it) and dies with the process.
 *
 * [liveSession] is the transport of the running shell. The file manager rides
 * on it for browsing, so opening the remote file list costs no second handshake
 * or authentication prompt. It is cleared when the shell disconnects.
 */
object ActiveConnection {
    @Volatile
    var config: SshConfig? = null

    @Volatile
    var liveSession: Session? = null

    /** Record the connection that just came up. */
    fun set(cfg: SshConfig, session: Session?) {
        config = cfg
        liveSession = session
    }

    /** The shell went away: stop lending its transport to the file manager. */
    fun clearSession() {
        liveSession = null
    }
}
