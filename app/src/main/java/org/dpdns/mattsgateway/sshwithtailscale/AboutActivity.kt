// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Matt <matt@mattsgateway.dpdns.org>
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version. See LICENSE for the full text.

package org.dpdns.mattsgateway.sshwithtailscale

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Static "about" page: what this app does and what it is built on.
 *
 * Deliberately dumb -- it touches no Tailscale state and owns nothing beyond its
 * own views, so opening it can never disturb the node MainActivity is running.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // The theme is NoActionBar, so this toolbar is the whole app bar and it
        // is what provides the back affordance.
        findViewById<MaterialToolbar>(R.id.aboutToolbar).setNavigationOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        setUpAuthorContact()
    }

    /**
     * Make the contact line tappable.
     *
     * Done in code rather than with android:autoLink because autoLink has to
     * guess where the address ends inside a line that may also carry other
     * punctuation, and it silently does nothing when it guesses wrong. An
     * explicit URLSpan over the exact address always works.
     */
    private fun setUpAuthorContact() {
        val view = findViewById<TextView>(R.id.aboutAuthorContact)
        val address = getString(R.string.about_author_contact)
        val spanned = SpannableString(address).apply {
            setSpan(
                URLSpan("mailto:$address"),
                0, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        view.text = spanned
        view.movementMethod = LinkMovementMethod.getInstance()
    }
}
