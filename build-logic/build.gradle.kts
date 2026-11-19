plugins {
    `kotlin-dsl`
}

// Keep these versions aligned with gradle/libs.versions.toml (agp, kotlin, ksp, room).
// KSP and the Room plugin live here, next to the Kotlin plugin, so a module
// does not load a second copy of the Kotlin plugin from the plugins DSL.
dependencies {
    implementation("com.android.tools.build:gradle:8.13.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
    implementation("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:${libs.versions.ksp.get()}")
    implementation("androidx.room:room-gradle-plugin:${libs.versions.room.get()}")
}
