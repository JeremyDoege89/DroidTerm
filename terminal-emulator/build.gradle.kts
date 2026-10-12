// Vendored from termux-app v0.118.1 (Apache-2.0, derived from jackpal/Android-Terminal-Emulator).
// DroidTerm change: TerminalSession can also be backed by a byte stream (see TerminalSession.StreamBackend).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 30
        externalNativeBuild {
            ndkBuild { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Os", "-fno-stack-protector", "-Wl,--gc-sections") }
        }
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
