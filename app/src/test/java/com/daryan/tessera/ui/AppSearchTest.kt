// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import com.daryan.tessera.container.PermissionManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {
    private fun app(id: String, name: String, description: String = "", category: String = "Apps", packages: List<String> = emptyList(), exec: String = "/usr/bin/$id") =
        PermissionManifest(id = id, name = name, description = description, category = category, packages = packages, exec = listOf(exec))

    private val apps = listOf(
        app("gimp", "GIMP", "Image editor", "Graphics", listOf("gimp")),
        app("firefox", "Firefox", "Web browser", "Internet", listOf("firefox-esr"), "/usr/bin/firefox-esr"),
        app("htop", "htop", "Process viewer", "System"),
        app("code", "Visual Studio Code", "Code editor", "Development", listOf("code")),
        app("files", "Files", "Browse your files", "System", listOf("nautilus"), "/usr/bin/nautilus"),
    )

    private fun names(q: String) = searchApps(apps, q).map { it.name }

    @Test fun `names that start with the query come first`() {
        // "Firefox" and "Files" start with it; "Visual Studio Code" only mentions files nowhere.
        assertEquals(listOf("Files", "Firefox"), names("fi"))
    }

    @Test fun `a word in the name beats a match elsewhere`() {
        // Visual Studio Code has a word starting with "code"; GIMP only says "editor".
        assertEquals("Visual Studio Code", names("code").first())
    }

    @Test fun `descriptions, packages and commands are searched too`() {
        assertEquals(listOf("GIMP", "Visual Studio Code"), names("editor"))
        assertEquals(listOf("Files"), names("nautilus"))
        assertEquals(listOf("Firefox"), names("esr"))
    }

    @Test fun `case and blank queries`() {
        assertEquals(listOf("GIMP"), names("gImP"))
        assertEquals(apps.size, names("  ").size)
        assertEquals(emptyList<String>(), names("zzz"))
    }
}
