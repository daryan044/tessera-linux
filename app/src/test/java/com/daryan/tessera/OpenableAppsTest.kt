// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import com.daryan.tessera.container.PermissionManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenableAppsTest {
    private fun app(name: String, id: String = name, gui: Boolean = true, desktop: Boolean = false) =
        PermissionManifest(id = id, name = name, exec = listOf(name), gui = gui, desktop = desktop)

    private fun names(vararg apps: PermissionManifest, installed: (PermissionManifest) -> Boolean? = { true }) =
        openableApps(apps.toList(), installed).map { it.name }

    @Test fun `every installed windowed app is offered, by name`() {
        assertEquals(listOf("apple", "Konsole", "zebra"), names(app("zebra"), app("Konsole"), app("apple")))
    }

    @Test fun `what isn't installed isn't offered, but what can't be told is`() {
        val gone = app("gone")
        val unknown = app("unknown")
        assertEquals(listOf("here", "unknown"), names(app("here"), gone, unknown) {
            when (it) { gone -> false; unknown -> null; else -> true }
        })
    }

    @Test fun `no terminal apps, desktops or shortcuts, which could run a file as a script`() {
        assertEquals(
            listOf("Konsole"),
            names(
                app("Konsole"),
                app("bash", gui = false),
                app("Desktop", desktop = true),
                app("My command", id = "shortcut-1"),
            ),
        )
    }
}
