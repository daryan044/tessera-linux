// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.files

/**
 * What another Android app handed Tessera, through "Open with" or "Share", to open in Linux.
 *
 * Any app can send these, so only what's safe to act on for a stranger gets through: nothing runs
 * until the user picks an app, and files only come from content:// URIs of other apps. A file://
 * one, or one of our own providers, would have Tessera read its own private files on the sender's
 * behalf and put them where the sender may reach them.
 */
sealed interface SharedIn {
    /** content:// URIs, as strings. */
    data class Files(val uris: List<String>) : SharedIn

    /** An http or https address, with nothing else in it, as it goes on a command line. */
    data class Link(val url: String) : SharedIn

    /** Text that isn't a link; it opens as a file. */
    data class Text(val text: String) : SharedIn

    companion object {
        private const val ACTION_VIEW = "android.intent.action.VIEW"
        private const val ACTION_SEND = "android.intent.action.SEND"
        private const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private const val MAX_NAME = 200

        /**
         * What an intent asks for, or null when it asks for nothing we can do. [own] is Tessera's
         * package name: its providers' authorities start with it.
         */
        fun of(action: String?, data: String?, streams: List<String>, text: String?, own: String): SharedIn? {
            fun files(uris: List<String>) = uris.filter { isForeignContent(it, own) }.takeIf { it.isNotEmpty() }?.let(::Files)
            return when (action) {
                ACTION_VIEW -> data?.let { if (LINK.matches(it)) Link(it) else files(listOf(it)) }
                ACTION_SEND -> if (streams.isNotEmpty()) files(streams) else text?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                    if (LINK.matches(t)) Link(t) else Text(t)
                }
                ACTION_SEND_MULTIPLE -> files(streams)
                else -> null
            }
        }

        /** Whether [uri] is a content:// one of another app's. An authority may carry a user ("10@..."). */
        private fun isForeignContent(uri: String, own: String): Boolean {
            if (!uri.startsWith("content://", ignoreCase = true)) return false
            val authority = uri.substring("content://".length).substringBefore('/').substringAfterLast('@').lowercase()
            return authority.isNotEmpty() && !authority.startsWith(own.lowercase())
        }

        /** A name a file another app named can safely have in a Linux folder. */
        fun safeName(name: String?): String {
            val clean = name.orEmpty().substringAfterLast('/').filterNot { it.isISOControl() }.trim().take(MAX_NAME)
            return if (clean.isEmpty() || clean == "." || clean == "..") "file" else clean
        }
    }
}
