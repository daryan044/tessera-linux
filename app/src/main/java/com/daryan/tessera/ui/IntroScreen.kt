// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One page of the intro. */
private data class Slide(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val points: List<String> = emptyList(),
)

private val SLIDES = listOf(
    Slide(
        LauncherIcons.Apps,
        "Linux apps on your phone",
        "Firefox, VS Code, GIMP, LibreOffice, all of Debian and Flathub, each with its own icon " +
            "and its own window, on your phone's GPU and in your phone's colors. No terminal " +
            "setup, no VNC, no guides to follow.",
    ),
    Slide(
        LauncherIcons.Terminal,
        "Set it up once",
        "Open Setup and tap Install. Tessera downloads Debian and gets it ready; everything after " +
            "that happens on its own.",
        listOf(
            "Allow it to lift Android's background limit when asked, so your apps don't get stopped.",
            "Root is optional. Without it everything still works; with it, apps are better isolated and run faster.",
        ),
    ),
    Slide(
        LauncherIcons.Store,
        "Pick apps from the Store",
        "Nearly 2,000 Debian desktop apps, and with one switch Flathub's 2,800 more, with icons, " +
            "screenshots and search. Installs run in the background while you keep using your phone.",
        listOf(
            "Flathub is optional: the first time, one tap sets it up. Its apps run like any other, GPU included, no root needed.",
            "Anything you install yourself with apt or a .deb shows up in the launcher too.",
        ),
    ),
    Slide(
        LauncherIcons.Keyboard,
        "They behave like Android apps",
        "Each app gets its own window and its own card in Recents, and can be pinned to your home " +
            "screen next to everything else.",
        listOf(
            "The keyboard opens by itself when you tap a text field.",
            "Clipboard works both ways, and sound plays through Android.",
            "Scroll and pinch with your fingers, or switch to touchpad or mouse mode, where touch and hold right-clicks.",
            "Swipe in from the edge for the window panel: right click, keys, Scale (bigger or smaller, live, kept per app), cast and USB.",
        ),
    ),
    Slide(
        LauncherIcons.Palette,
        "Your phone's look, on Linux",
        "Linux apps take your wallpaper's colors and follow dark mode, live: flip Android to dark " +
            "and every open Linux app follows within seconds.",
        listOf(
            "Prefer a Linux theme? Import one in Settings → Appearance, and Linux apps and Tessera itself both wear it, colors and corners.",
            "A white, rounded pointer in every app.",
            "An app looks wrong themed? Hold its icon, Look and graphics, and leave it out.",
        ),
    ),
    Slide(
        LauncherIcons.Monitor,
        "Real GPU acceleration",
        "On Snapdragon phones Tessera brings its own graphics driver, so apps get OpenGL and " +
            "Vulkan with no setup.",
        listOf(
            "If something draws wrong, hold the app's icon and open Look and graphics.",
            "Each fix is named after what you see: black blocks, flicker, missing parts.",
        ),
    ),
    Slide(
        Icons.Rounded.Lock,
        "Every app in its own sandbox",
        "An app only gets what you grant it: certain folders, the network, sound, a display, " +
            "devices. Change any of it from the app's page.",
        listOf("Tessera tells you honestly which permissions it can enforce and which are best-effort."),
    ),
    Slide(
        LauncherIcons.Folder,
        "And the tools around them",
        "The dock has more than apps.",
        listOf(
            "Files: your phone's storage and the Linux system side by side. Copy, compress, edit, open with any app, install a .deb.",
            "Sessions: a task manager with every Linux process, live CPU and memory, and End task (tap twice to be sure).",
            "PC trackpad, in the side menu: your phone becomes a trackpad and keyboard for a computer, over Bluetooth (or USB, with root).",
        ),
    ),
    Slide(
        LauncherIcons.Spark,
        "If something goes wrong",
        "Running desktop Linux on a phone is not always tidy, so Tessera comes with a way to get " +
            "help that knows how it works.",
        listOf(
            "Open the Claude Code shortcut and sign in with your own Anthropic account. It " +
                "installs itself the first time.",
            "It is given Tessera's architecture and its known problems, and it reads the logs of " +
                "apps that just failed, so it can usually name the fix.",
            "No account, or you'd rather use something else? Setup → Copy diagnostics puts the " +
                "same notes and logs on your clipboard, ready to paste into any AI.",
            "If it turns out to be a real bug, it will write the report and hand it to you to " +
                "post. Nothing is ever sent anywhere without you.",
        ),
    ),
)

/**
 * The first-run introduction: what Tessera is, what it can do, and where to turn when an app
 * misbehaves. Shown once, and openable again from Setup.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                SlidePage(SLIDES[page])
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Keeps the dots centred once Skip is gone on the last page.
                if (last) Spacer(Modifier.width(1.dp)) else TextButton(onClick = onDone) { Text("Skip") }
                Spacer(Modifier.weight(1f))
                Dots(pager.currentPage, SLIDES.size)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }) { Text(if (last) "Get started" else "Next") }
            }
        }
    }
}

@Composable
private fun SlidePage(slide: Slide) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        ) {
            Icon(slide.icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            slide.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            slide.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (slide.points.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                slide.points.forEach { point ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            point,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Which page of how many, as the usual row of dots. */
@Composable
private fun Dots(current: Int, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val here = i == current
            val width by animateDpAsState(if (here) 20.dp else 6.dp, label = "dot")
            val color by animateColorAsState(
                if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                label = "dotColor",
            )
            Box(Modifier.height(6.dp).width(width).background(color, CircleShape))
        }
    }
}
