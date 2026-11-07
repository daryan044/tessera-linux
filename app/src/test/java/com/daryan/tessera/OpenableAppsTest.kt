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

    @Test fun `an App widget offers installed apps, and a Quick command only shortcuts`() {
        val all = listOf(app("Zed"), app("bash", gui = false), app("Desktop", desktop = true), app("My command", id = "shortcut-1"), app("Gone"))
        val installed: (PermissionManifest) -> Boolean? = { it.name != "Gone" }
        assertEquals(listOf("bash", "Zed"), widgetTargets(WidgetKind.APP, all, installed).map { it.name })
        assertEquals(listOf("My command"), widgetTargets(WidgetKind.COMMAND, all, installed).map { it.name })
        assertEquals(emptyList<PermissionManifest>(), widgetTargets(WidgetKind.STATS, all, installed))
    }

    @Test fun `two entries for one program are the same app, as they share its profile`() {
        val launcher = app("Firefox", id = "firefox-esr").copy(exec = listOf("firefox-esr"))
        val linux = app("Firefox ESR", id = "system-firefox").copy(exec = listOf("/usr/bin/firefox-esr", "/tmp/a.png"))
        assertEquals(true, sameApp(launcher, linux))
        assertEquals(true, sameApp(launcher, launcher.copy(exec = listOf("firefox-esr", "https://example.org"))))
        assertEquals(false, sameApp(launcher, app("GIMP").copy(exec = listOf("gimp"))))
    }

    @Test fun `a wrapper doesn't make two apps the same, and terminals aren't asked about`() {
        fun wrapped(id: String) = app(id).copy(exec = listOf("sh", "-c", id))
        assertEquals(false, sameApp(wrapped("a"), wrapped("b")))
        assertEquals(true, sameApp(wrapped("a"), wrapped("a")))
        assertEquals(false, sameApp(app("a", gui = false), app("a", gui = false)))
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
