plugins {
    id("hearth.android-library")
}

android {
    namespace = "app.hearth.media"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
