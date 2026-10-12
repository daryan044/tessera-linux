plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
    sourceSets.getByName("main").assets.srcDir(rootProject.file("build/x11/assets"))

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
