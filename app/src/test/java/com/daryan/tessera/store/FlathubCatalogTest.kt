// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlathubCatalogTest {
    // Trimmed from Flathub's aarch64 AppStream data.
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<components version="0.8" origin="flathub">
  <component type="desktop-application">
    <id>io.mpv.Mpv</id>
    <name>mpv</name>
    <name xml:lang="de">mpv (de)</name>
    <summary>A free, open source, and cross-platform media player</summary>
    <description>
      <p>Mpv is a movie player
 based on MPlayer.</p>
      <p>Features:</p>
      <ul>
        <li>Drag and drop playlist</li>
        <li>Loading external configuration files</li>
      </ul>
      <p xml:lang="de">Nicht das.</p>
    </description>
    <launchable type="desktop-id">io.mpv.Mpv.desktop</launchable>
    <icon height="64" type="cached" width="64">io.mpv.Mpv.png</icon>
    <icon height="128" type="remote" width="128">https://dl.flathub.org/media/io/mpv/Mpv/x/icons/128x128/io.mpv.Mpv.png</icon>
    <icon height="128" scale="2" type="remote" width="128">https://dl.flathub.org/media/io/mpv/Mpv/x/icons/128x128@2/io.mpv.Mpv.png</icon>
    <categories>
      <category>AudioVideo</category>
      <category>Video</category>
    </categories>
    <screenshots>
      <screenshot type="default">
        <image height="629" type="source" width="998">https://example.org/1_orig.png</image>
        <image height="473" type="thumbnail" width="752">https://example.org/1_752.png</image>
        <image height="393" type="thumbnail" width="624">https://example.org/1_624.png</image>
      </screenshot>
    </screenshots>
    <bundle type="flatpak" runtime="org.freedesktop.Platform/aarch64/25.08">app/io.mpv.Mpv/aarch64/stable</bundle>
  </component>
  <component type="runtime">
    <id>org.freedesktop.Platform</id>
    <name>Freedesktop Platform</name>
    <bundle type="flatpak">runtime/org.freedesktop.Platform/aarch64/25.08</bundle>
  </component>
  <component type="console-application">
    <id>org.example.Tool</id>
    <name>Tool</name>
    <bundle type="flatpak">app/org.example.Tool/aarch64/stable</bundle>
  </component>
</components>
"""

    @Test fun `reads windowed apps with their untranslated texts, icon and screenshots`() {
        val apps = FlathubCatalog.parse(xml.byteInputStream())
        assertEquals(listOf("io.mpv.Mpv"), apps.map { it.id })
        val mpv = apps.single()
        assertEquals("mpv", mpv.name)
        assertEquals(StoreSource.FLATHUB, mpv.source)
        assertEquals("io.mpv.Mpv", mpv.pkg)
        assertEquals(listOf("AudioVideo", "Video"), mpv.categories)
        assertEquals("io.mpv.Mpv.desktop", mpv.desktopId)
        assertEquals("https://dl.flathub.org/media/io/mpv/Mpv/x/icons/128x128/io.mpv.Mpv.png", mpv.iconUrl)
        assertEquals(listOf("https://example.org/1_624.png"), mpv.screenshots)
        assertTrue(mpv.description.startsWith("Mpv is a movie player based on MPlayer.\n\nFeatures:"))
        assertTrue("• Drag and drop playlist\n" in mpv.description)
        assertTrue("Nicht" !in mpv.description)
    }
}
