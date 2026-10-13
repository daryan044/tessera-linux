// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.daryan.tessera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.daryan.tessera.config.AptSource
import com.daryan.tessera.config.AptSources
import com.daryan.tessera.config.EnvironmentFile
import com.daryan.tessera.config.HostsFile
import com.daryan.tessera.config.HostsLine
import com.daryan.tessera.config.RcLine
import com.daryan.tessera.config.ResolvConf
import com.daryan.tessera.config.ResolvLine
import com.daryan.tessera.config.ShellRc
import com.daryan.tessera.container.ConfigKind

/**
 * The form for a [kind] of file. Works on text in and text out: [text] is parsed, edits are
 * rendered back through [onText]. The models only rewrite lines the form changed.
 */
@Composable
fun ConfigForm(kind: ConfigKind, text: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        when (kind) {
            ConfigKind.SHELL -> ShellForm(ShellRc.parse(text)) { onText(it.render()) }
            ConfigKind.ENVIRONMENT -> EnvironmentForm(EnvironmentFile.parse(text)) { onText(it.render()) }
            ConfigKind.SOURCES -> SourcesForm(AptSources.parse(text)) { onText(it.render()) }
            ConfigKind.RESOLV -> ResolvForm(ResolvConf.parse(text)) { onText(it.render()) }
            ConfigKind.HOSTS -> HostsForm(HostsFile.parse(text)) { onText(it.render()) }
            ConfigKind.TEXT -> {}
        }
    }
}

/** Why [text] can't be saved from the form, or null when it's fine. */
fun formProblem(kind: ConfigKind, text: String): String? = when (kind) {
    ConfigKind.SHELL -> ShellRc.parse(text).lines.let { lines ->
        when {
            lines.any { it is RcLine.Export && it.key.isEmpty() } -> "Give every variable a name"
            lines.any { it is RcLine.Alias && it.name.isEmpty() } -> "Give every alias a name"
            else -> null
        }
    }
    ConfigKind.ENVIRONMENT -> "Give every variable a name".takeIf { EnvironmentFile.parse(text).lines.any { it is RcLine.Export && it.key.isEmpty() } }
    ConfigKind.SOURCES -> AptSources.parse(text).sources.let { s ->
        when {
            s.any { it.uris.isBlank() } -> "Every source needs an address"
            s.any { it.suites.isBlank() } -> "Every source needs a release, like trixie"
            s.any { it.components.isEmpty() } -> "Pick at least one component for every source"
            s.any { it.types.isEmpty() } -> "Pick binary and/or source packages for every source"
            else -> null
        }
    }
    else -> null
}

// ---------------------------------------------------------------- shell rc

@Composable
private fun ShellForm(rc: ShellRc, onChange: (ShellRc) -> Unit) {
    fun set(i: Int, line: RcLine?) = onChange(rc.copy(lines = rc.lines.toMutableList().also { if (line == null) it.removeAt(i) else it[i] = line }))

    Section("Environment variables", "Set for every shell that reads this file. Use \$HOME or \$PATH to build on existing values.")
    rc.lines.forEachIndexed { i, line ->
        if (line is RcLine.Export) {
            PairRow(line.key, "Name", line.value, "Value", onFirst = { set(i, line.copy(key = it.filter(::isNameChar))) },
                onSecond = { set(i, line.copy(value = it)) }, onDelete = { set(i, null) }, firstWeight = 0.4f)
        }
    }
    AddButton("Add variable") { onChange(rc.copy(lines = rc.lines + RcLine.Export("", ""))) }

    Section("Aliases", "Short names for commands, like ll for ls -l.")
    rc.lines.forEachIndexed { i, line ->
        if (line is RcLine.Alias) {
            PairRow(line.name, "Name", line.command, "Command", onFirst = { set(i, line.copy(name = it.filterNot(Char::isWhitespace))) },
                onSecond = { set(i, line.copy(command = it)) }, onDelete = { set(i, null) }, firstWeight = 0.3f)
        }
    }
    AddButton("Add alias") { onChange(rc.copy(lines = rc.lines + RcLine.Alias("", ""))) }

    if (rc.otherCount > 0) KeptNote("${rc.otherCount} other lines (comments, functions, conditions) are kept as written. Edit them in the text view.")
}

private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'

@Composable
private fun EnvironmentForm(env: EnvironmentFile, onChange: (EnvironmentFile) -> Unit) {
    fun set(i: Int, line: RcLine?) = onChange(env.copy(lines = env.lines.toMutableList().also { if (line == null) it.removeAt(i) else it[i] = line }))
    Section("Variables", "Set for every login session. Plain values: no \$VARIABLES here.")
    env.lines.forEachIndexed { i, line ->
        if (line is RcLine.Export) {
            PairRow(line.key, "Name", line.value, "Value", onFirst = { set(i, line.copy(key = it.filter(::isNameChar))) },
                onSecond = { set(i, line.copy(value = it)) }, onDelete = { set(i, null) }, firstWeight = 0.4f)
        }
    }
    AddButton("Add variable") { onChange(env.copy(lines = env.lines + RcLine.Export("", ""))) }
}

// ---------------------------------------------------------------- apt sources

@Composable
private fun SourcesForm(model: AptSources, onChange: (AptSources) -> Unit) {
    fun set(i: Int, s: AptSource?) = onChange(model.copy(sources = model.sources.toMutableList().also { if (s == null) it.removeAt(i) else it[i] = s }))

    Section("Repositories", "Where apt finds packages. Turn one off instead of deleting it if you might want it back.")
    model.sources.forEachIndexed { i, s ->
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.uris.substringAfter("://").ifBlank { "New source" }, style = MaterialTheme.typography.titleMedium)
                        Text(if (s.enabled) "On" else "Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = s.enabled, onCheckedChange = { set(i, s.copy(enabled = it)) })
                    IconButton(onClick = { set(i, null) }) { Icon(Icons.Rounded.Delete, "Delete source") }
                }
                Spacer(Modifier.height(8.dp))
                Field(s.uris, "Address", { set(i, s.copy(uris = it.trim())) })
                Field(s.suites, "Releases (e.g. trixie trixie-updates)", { set(i, s.copy(suites = it)) })
                Label("Components")
                val components = (AptSources.KNOWN_COMPONENTS + s.components).distinct()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    components.forEach { c ->
                        FilterChip(selected = c in s.components, label = { Text(c) }, onClick = {
                            set(i, s.copy(components = if (c in s.components) s.components - c else components.filter { it in s.components || it == c }))
                        })
                    }
                }
                Label("Packages")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("deb" to "Apps (binary)", "deb-src" to "Source code").forEach { (t, label) ->
                        FilterChip(selected = t in s.types, label = { Text(label) }, onClick = {
                            set(i, s.copy(types = if (t in s.types) s.types - t else listOf("deb", "deb-src").filter { it in s.types || it == t }))
                        })
                    }
                }
                Field(s.signedBy, "Signing key file", { set(i, s.copy(signedBy = it.trim())) })
            }
        }
    }
    AddButton("Add source") { onChange(model.copy(sources = model.sources + AptSource(suites = model.sources.firstOrNull()?.suites?.substringBefore(' ') ?: "trixie"))) }
}

// ---------------------------------------------------------------- resolv.conf, hosts

@Composable
private fun ResolvForm(model: ResolvConf, onChange: (ResolvConf) -> Unit) {
    fun set(i: Int, l: ResolvLine?) = onChange(model.copy(lines = model.lines.toMutableList().also { if (l == null) it.removeAt(i) else it[i] = l }))
    Section("DNS servers", "Asked in order to turn names like debian.org into addresses.")
    model.lines.forEachIndexed { i, l ->
        if (l is ResolvLine.Server) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(l.address, { set(i, ResolvLine.Server(it.trim())) }, label = { Text("Server address") },
                    singleLine = true, textStyle = mono(), modifier = Modifier.weight(1f).padding(vertical = 4.dp))
                IconButton(onClick = { set(i, null) }) { Icon(Icons.Rounded.Delete, "Remove") }
            }
        }
    }
    AddButton("Add server") { onChange(model.copy(lines = model.lines + ResolvLine.Server(""))) }
    val other = model.lines.count { it is ResolvLine.Other && it.text.isNotBlank() }
    if (other > 0) KeptNote("$other other lines (search domains, options, comments) are kept as written.")
}

@Composable
private fun HostsForm(model: HostsFile, onChange: (HostsFile) -> Unit) {
    fun set(i: Int, l: HostsLine?) = onChange(model.copy(lines = model.lines.toMutableList().also { if (l == null) it.removeAt(i) else it[i] = l }))
    Section("Host names", "Names that resolve to a fixed address without asking DNS.")
    model.lines.forEachIndexed { i, l ->
        if (l is HostsLine.Entry) {
            PairRow(l.address, "Address", l.names, "Names", onFirst = { set(i, l.copy(address = it.trim())) },
                onSecond = { set(i, l.copy(names = it)) }, onDelete = { set(i, null) }, firstWeight = 0.45f)
        }
    }
    AddButton("Add host") { onChange(model.copy(lines = model.lines + HostsLine.Entry("", ""))) }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Section(title: String, hint: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun mono() = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun Field(value: String, label: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, textStyle = mono(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable
private fun PairRow(
    first: String, firstLabel: String, second: String, secondLabel: String,
    onFirst: (String) -> Unit, onSecond: (String) -> Unit, onDelete: () -> Unit, firstWeight: Float,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(first, onFirst, label = { Text(firstLabel) }, singleLine = true, textStyle = mono(), modifier = Modifier.weight(firstWeight))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(second, onSecond, label = { Text(secondLabel) }, singleLine = true, textStyle = mono(), modifier = Modifier.weight(1f - firstWeight))
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Remove") }
    }
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun KeptNote(text: String) {
    Spacer(Modifier.height(20.dp))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
