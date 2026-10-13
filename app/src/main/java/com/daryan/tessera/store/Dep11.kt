// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import kotlinx.serialization.Serializable

/** One installable desktop app from a distro's AppStream catalog. */
@Serializable
data class StoreApp(
    /** AppStream id, e.g. "firefox-esr.desktop". */
    val id: String,
    val pkg: String,
    val name: String,
    val summary: String,
    /** Plain text; paragraphs separated by blank lines, list items as "• ". */
    val description: String,
    val categories: List<String>,
    /** File name inside the component's icon pack, e.g. "gimp_gimp.png". */
    val icon: String? = null,
    /** Repository component the package comes from: main, contrib, non-free. */
    val component: String,
    /** The .desktop file that starts it, when the catalog names one. */
    val desktopId: String? = null,
)

/**
 * Reader for Debian's DEP-11 catalogs (`dists/<suite>/<component>/dep11/Components-<arch>.yml`).
 * They're machine-generated YAML with a fixed shape, so this reads only the handful of fields
 * the store shows instead of pulling in a YAML library. Only desktop applications are kept.
 */
object Dep11 {

    fun parse(lines: Sequence<String>, component: String): List<StoreApp> {
        val apps = mutableListOf<StoreApp>()
        val doc = mutableListOf<String>()
        for (line in lines) {
            if (line == "---") {
                parseDoc(doc, component)?.let(apps::add)
                doc.clear()
            } else {
                doc += line
            }
        }
        parseDoc(doc, component)?.let(apps::add)
        return apps
    }

    private fun parseDoc(lines: List<String>, component: String): StoreApp? {
        if (lines.none { it == "Type: desktop-application" }) return null
        val top = sections(lines)
        val id = scalar(top["ID"]) ?: return null
        val pkg = scalar(top["Package"]) ?: return null
        return StoreApp(
            id = id,
            pkg = pkg,
            name = localized(top["Name"]) ?: pkg,
            summary = localized(top["Summary"]).orEmpty(),
            description = localized(top["Description"])?.let(::htmlToText).orEmpty(),
            categories = top["Categories"].orEmpty().mapNotNull { l -> l.trim().removePrefix("- ").trim().takeIf { l.trimStart().startsWith("- ") } },
            icon = cachedIcon(top["Icon"].orEmpty()),
            component = component,
            desktopId = launchable(top["Launchable"].orEmpty()),
        )
    }

    /** Splits a document into top-level keys -> their lines (value on the key line first). */
    private fun sections(lines: List<String>): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            val m = TOP_KEY.matchEntire(line)
            if (m != null) {
                current = mutableListOf<String>().also { out[m.groupValues[1]] = it }
                m.groupValues[2].takeIf { it.isNotBlank() }?.let { current += it.trim() }
            } else {
                current?.add(line)
            }
        }
        return out
    }

    private fun scalar(lines: List<String>?): String? = lines?.firstOrNull()?.let(::unquote)

    /** The untranslated ("C") value of a localized map, including folded `>-` blocks. */
    private fun localized(lines: List<String>?): String? {
        lines ?: return null
        val i = lines.indexOfFirst { it.startsWith("  C:") }
        if (i < 0) return null
        val value = lines[i].removePrefix("  C:").trim()
        if (value.isNotEmpty() && value[0] !in ">|") return unquote(value)
        // Block scalar: following lines indented deeper than the key belong to it.
        val block = lines.drop(i + 1).takeWhile { it.startsWith("    ") || it.isBlank() }
        val text = block.map { it.trim() }
        return if (value.startsWith("|")) text.joinToString("\n").trim()
        else fold(text)
    }

    /** YAML folding: single newlines become spaces, blank lines become newlines. */
    private fun fold(lines: List<String>): String = buildString {
        for (l in lines) {
            when {
                l.isEmpty() -> append('\n')
                isEmpty() || endsWith('\n') -> append(l)
                else -> append(' ').append(l)
            }
        }
    }.trim()

    private fun unquote(v: String): String = when {
        v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') -> v.substring(1, v.length - 1).replace("''", "'")
        v.length >= 2 && v.startsWith('"') && v.endsWith('"') ->
            v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        else -> v
    }

    /** The largest cached icon (the icon packs we download are 128x128). */
    private fun cachedIcon(lines: List<String>): String? {
        var inCached = lines.firstOrNull()?.trim() == "cached:"
        // Older catalogs list cached icons directly under Icon; newer ones under "cached:".
        val hasSubkeys = lines.any { SUB_KEY.matches(it) }
        if (!hasSubkeys) inCached = true
        var best: Pair<String, Int>? = null
        var name: String? = null
        for (line in lines) {
            SUB_KEY.matchEntire(line)?.let { inCached = it.groupValues[1] == "cached"; name = null; return@let }
            if (!inCached) continue
            val t = line.trim()
            when {
                t.startsWith("- name:") -> name = t.removePrefix("- name:").trim()
                t.startsWith("width:") -> {
                    val n = name ?: continue
                    val w = t.removePrefix("width:").trim().toIntOrNull() ?: 0
                    val current = best
                    if (current == null || w > current.second) best = n to w
                }
            }
        }
        return best?.first
    }

    private fun launchable(lines: List<String>): String? =
        lines.map { it.trim() }.firstOrNull { it.startsWith("- ") && it.endsWith(".desktop") }?.removePrefix("- ")

    /** AppStream descriptions are a small HTML subset: p, ul/ol, li, em, code. */
    fun htmlToText(html: String): String = html
        .replace(Regex("\\s*<li>\\s*"), "\n• ")
        .replace(Regex("\\s*</p>\\s*|\\s*</?(ul|ol)>\\s*"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private val TOP_KEY = Regex("^([A-Za-z][A-Za-z0-9]*):(.*)$")
    private val SUB_KEY = Regex("^  ([a-z][a-z-]*):\\s*$")
}
