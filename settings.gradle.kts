pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Termux's terminal-emulator / terminal-view (Apache-2.0) are only published there.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "linux-launcher"
include(":app")

// Termux:X11's X server and window (our fork, see third_party/termux-x11).
include(":lorie")
project(":lorie").projectDir = file("third_party/termux-x11/lorie")
include(":shell-loader:stub")
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")
// lorie only needs the stub; the real :shell-loader is an app module we don't build.
project(":shell-loader").projectDir = file("third_party/gradle-empty")
