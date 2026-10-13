import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The release key: from keystore.properties at the top of the project (storeFile, storePassword,
 * keyAlias, keyPassword; never committed), or from LL_KEYSTORE, LL_KEYSTORE_PASSWORD, LL_KEY_ALIAS
 * and LL_KEY_PASSWORD in the environment (CI). Null when neither is there.
 */
val releaseKey: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) {
        val p = Properties().apply { file.inputStream().use(::load) }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { p.getProperty(it).orEmpty() }
    } else {
        System.getenv("LL_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { store ->
            mapOf(
                "storeFile" to store,
                "storePassword" to System.getenv("LL_KEYSTORE_PASSWORD").orEmpty(),
                "keyAlias" to System.getenv("LL_KEY_ALIAS").orEmpty(),
                "keyPassword" to System.getenv("LL_KEY_PASSWORD").orEmpty(),
            )
        }
    }
}

android {
    namespace = "com.daryan.linuxlauncher"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.daryan.linuxlauncher"
        minSdk = 24
        // Deliberately 28: from 29 on, SELinux forbids exec() of files in the app's data dir,
        // which is where the rootfs lives. Same trade-off as Termux; blocks Play Store, not F-Droid.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getValue("storeFile"))
                storePassword = releaseKey.getValue("storePassword")
                keyAlias = releaseKey.getValue("keyAlias")
                keyPassword = releaseKey.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release key, the debug key: it still installs over a debug build (same
            // signature, data kept), and isn't debuggable, which makes Compose much smoother.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // xkb keyboard data for the X server, staged by scripts/x11/build-native.sh.
    sourceSets.getByName("main").assets.directories.add(rootProject.file("build/x11/assets").path)
    // The GPU driver (Mesa Turnip + Zink), staged by scripts/gpu/build-mesa.sh; optional.
    sourceSets.getByName("main").assets.directories.add(rootProject.file("build/gpu/assets").path)

    packaging {
        // proot & friends ship as lib*.so and must exist as real files in nativeLibraryDir
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.haze)
    implementation(libs.haze.blur.materials)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":lorie"))
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)

    testImplementation(libs.junit)
}
