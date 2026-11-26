import com.android.build.gradle.LibraryExtension

plugins {
    id("gallery.android-library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The library plugin sets the SDK. Compose is opt-in so :core modules do not
// run the compose compiler. Kotlin 2 applies the compiler as this plugin;
// there is no composeOptions { kotlinCompilerExtensionVersion } to set.
extensions.configure<LibraryExtension> {
    buildFeatures {
        compose = true
    }
}
