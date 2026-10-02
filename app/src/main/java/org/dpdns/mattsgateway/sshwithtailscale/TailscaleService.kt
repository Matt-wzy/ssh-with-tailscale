// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service keeping the process alive while the Tailscale node runs.
 *
 * Without it, backgrounding the app lets Android freeze (and eventually kill)
 * the process: the tsnet node stops answering, SSH sessions die, and the
 * reverse-exported ports -- sold as "stays up as long as Tailscale is" -- go
 * dark. The service holds a quiet persistent notification for exactly as long
 * as the node is up.
 *
 * It also survives process death: START_STICKY brings it back, and it then
 * re-launches the node from the persisted state (empty authkey reuses the
 * login) and re-issues the saved reverse-export rules, so a low-memory kill
 * in the background does not silently unplug the phone from the tailnet.
 */
class TailscaleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW // silent, just a status entry
            )
        )
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (TailscaleManager.isRunning()) return START_STICKY

        // A fresh process. Revive the node only if the user had it running when
        // the process died -- after an explicit 停止 it must stay down.
        if (!isDesired(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        thread(name = "ts-revive") {
            val ok = TailscaleManager.start(applicationContext, "")
            if (!ok) {
                Log.w(TAG, "revive failed: ${TailscaleManager.lastError()}")
                return@thread
            }
            // Wait for the node to rejoin, then re-issue the saved rules.
            repeat(120) {
                if (!TailscaleManager.isRunning()) return@thread
                if (TailscaleManager.isUp()) {
                    for (r in PublishRule.load(this@TailscaleService)) {
                        val err = if (r.isProxy) {
                            TailscaleManager.publishProxy(r.addr())
                        } else {
                            TailscaleManager.publish(r.addr(), "${r.targetHost}:${r.targetPort}")
                        }
                        if (err.isNotEmpty()) Log.w(TAG, "revive publish :${r.listenPort}: $err")
                    }
                    return@thread
                }
                Thread.sleep(1000)
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TailscaleService"
        private const val CHANNEL_ID = "tailscale-node"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "service"

        /**
         * Whether the user wants the node up. Set from MainActivity's start/stop
         * toggle and consulted when START_STICKY restarts this service after a
         * process death, so an explicit stop is honoured.
         */
        fun setDesired(context: Context, running: Boolean) {
            prefs(context).edit().putBoolean("node_desired", running).apply()
        }

        private fun isDesired(context: Context): Boolean =
            prefs(context).getBoolean("node_desired", false)

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, TailscaleService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TailscaleService::class.java))
        }
    }
}
