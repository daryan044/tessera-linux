// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daryan.tessera.Release

/**
 * Says a newer Tessera is out, with its release notes. Tessera doesn't install it: [onGet] opens
 * the release's page. [onLater] asks again next time the launcher opens; [onSkip] not until a
 * version after this one.
 */
@Composable
fun UpdateDialog(release: Release, current: String, onGet: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Rounded.Refresh, null) },
        title = { Text("Tessera ${release.version} is out") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("You have $current. Get the new version where you got this one: its release page has the APK, and Obtainium or F-Droid will offer it too.")
                val notes = releaseNotes(release.notes)
                if (notes.isNotEmpty()) {
                    Text("What's new", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    Text(notes, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onGet) { Text("See the release") } },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Skip this version") }
            TextButton(onClick = onLater) { Text("Later") }
        },
    )
}

/** A release's notes as plain text: Markdown's headings, bullets, emphasis and links read without their marks. */
internal fun releaseNotes(markdown: String): String =
    markdown.lineSequence().map { it.trimEnd() }.map { line ->
        line.replace(Regex("""^#{1,6}\s*"""), "")
            .replace(Regex("""^(\s*)[-*]\s+"""), "$1• ")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace("`", "")
    }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
