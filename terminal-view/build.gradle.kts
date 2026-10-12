// Vendored from termux-app v0.118.1 (Apache-2.0, derived from jackpal/Android-Terminal-Emulator).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.annotation)
    api(project(":terminal-emulator"))
}
