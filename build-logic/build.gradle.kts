plugins {
    `kotlin-dsl`
}

// Keep these versions aligned with gradle/libs.versions.toml (agp, kotlin).
dependencies {
    implementation("com.android.tools.build:gradle:8.13.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
}
