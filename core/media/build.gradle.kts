plugins {
    id("gallery.android-library")
}

android {
    namespace = "com.useinoski.gallerycleanup.media"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
