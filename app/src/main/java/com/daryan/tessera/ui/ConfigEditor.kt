// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.daryan.tessera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daryan.tessera.container.ConfigFile
import com.daryan.tessera.container.ConfigKind
import com.daryan.tessera.container.ConfigStore
import kotlinx.coroutines.launch

/** Full-screen plain-text editor for one container config file. */
@Composable
fun ConfigEditor(
    file: ConfigFile,
    store: ConfigStore,
    engine: String,
    onClose: () -> Unit,
    savedMessage: String = "Saved. The original is kept as ${file.path.substringAfterLast('/')}.bak-launcher",
) {
    var original by remember(file) { mutableStateOf<String?>(null) }
    var text by remember(file) { mutableStateOf("") }
    var loaded by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Forms first; the app bar button flips to the file's text and back. Both edit `text`.
    var showText by remember(file) { mutableStateOf(file.kind == ConfigKind.TEXT) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val dirty = loaded && text != (original ?: "")

    LaunchedEffect(file) {
        runCatching { store.read(file.path) }
            .onSuccess { original = it; text = it.orEmpty() }
            .onFailure { error = it.message }
        loaded = true
    }

    val close = { if (dirty) confirmDiscard = true else onClose() }
    // Unsaved changes: back asks first, and the page stays.
    val back = rememberBackSwipe(closes = !dirty, onBack = close)

    Scaffold(
        modifier = Modifier.backSwipePage(back),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(file.title) },
                subtitle = { Text("${file.path} · $engine") },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (file.kind != ConfigKind.TEXT) {
                        IconButton(onClick = { showText = !showText }) {
                            if (showText) Icon(Icons.Rounded.Edit, "Show form")
                            else Icon(LauncherIcons.Code, "Show file text")
                        }
                    }
                    Button(
                        enabled = dirty && !saving && error == null,
                        onClick = {
                            formProblem(file.kind, text)?.takeIf { !showText }?.let { problem ->
                                scope.launch { snackbar.showSnackbar(problem) }
                                return@Button
                            }
                            saving = true
                            scope.launch {
                                runCatching { store.write(file.path, text) }
                                    .onSuccess {
                                        original = text
                                        snackbar.showSnackbar(savedMessage)
                                    }
                                    .onFailure { snackbar.showSnackbar("Couldn't save: ${it.message}") }
                                saving = false
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                error != null -> Text(
                    "Couldn't open ${file.path}: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                !showText -> {
                    if (original == null) {
                        Text(
                            "This file doesn't exist yet; saving creates it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    ConfigForm(file.kind, text, onText = { text = it }, modifier = Modifier.fillMaxSize())
                }
                else -> {
                    Text(
                        if (original == null) "${file.hint}. This file doesn't exist yet; saving creates it."
                        else file.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to ${file.title} haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}
