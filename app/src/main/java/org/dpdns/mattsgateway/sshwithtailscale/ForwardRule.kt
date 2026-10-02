// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One SSH port-forwarding rule.
 *
 * The direction decides **who dials the target**, and therefore which network
 * the target has to be reachable from:
 *  - remote = false (-L): we listen here, the *SSH server* dials targetHost.
 *  - remote = true  (-R): the *SSH server* listens, we dial targetHost -- and
 *    that dial can be routed through the Tailscale node, so tailnet addresses
 *    work there too.
 */
data class ForwardRule(
    val remote: Boolean,
    val bindHost: String,
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int
) {
    /** e.g. "本地 127.0.0.1:8080 → 10.0.0.5:80" */
    fun label(): String =
        "${if (remote) "远程 -R" else "本地 -L"}  $bindHost:$bindPort  →  $targetHost:$targetPort"

    fun toJson(): JSONObject = JSONObject().apply {
        put("remote", remote)
        put("bindHost", bindHost)
        put("bindPort", bindPort)
        put("targetHost", targetHost)
        put("targetPort", targetPort)
    }

    companion object {
        /**
         * Port forwarding is **switched off**, entry point and all.
         *
         * Measured on the device:
         *  - -R (server listens, we dial through Tailscale) works end to end:
         *    the far side saw the request arrive from the phone's tailnet IP.
         *  - -L (we listen, the server dials) does not: the server answers the
         *    channel open with a failure, so the connection is refused. Not yet
         *    understood, and the JSch-level exchange is not observable without
         *    patching JSch.
         *
         * The code stays (ForwardRule, LocalForwarder, Socks5SocketFactory, the
         * dialog, the persistence, the integration tests) so it can be picked up
         * later. Flip this to true to bring the menu entry and the rules back.
         */
        const val ENABLED = false

        private const val FILE = "forwards"
        private const val KEY = "rules"

        fun fromJson(o: JSONObject) = ForwardRule(
            remote = o.optBoolean("remote", false),
            bindHost = o.optString("bindHost", "127.0.0.1"),
            bindPort = o.optInt("bindPort", 0),
            targetHost = o.optString("targetHost", ""),
            targetPort = o.optInt("targetPort", 0)
        )

        fun load(context: Context): MutableList<ForwardRule> {
            val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return mutableListOf()
            return runCatching {
                val arr = JSONArray(raw)
                MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
            }.getOrElse { mutableListOf() }
        }

        fun save(context: Context, rules: List<ForwardRule>) {
            val arr = JSONArray()
            rules.forEach { arr.put(it.toJson()) }
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString())
                .apply()
        }
    }
}
