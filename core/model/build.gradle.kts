plugins {
    id("gallery.kotlin-jvm")
}

dependencies {
    // ScanStore is a suspend API and its progress is a Flow. core, not the
    // Android artifact: this module must stay free of the Android SDK.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
