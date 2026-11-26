plugins {
    id("gallery.android-library")
    id("gallery.room")
}

android {
    namespace = "com.useinoski.gallerycleanup.data"

    // MigrationTestHelper reads the exported schema JSON from test assets.
    // The Room plugin writes those files under schemas/. Unit tests run on
    // Robolectric, so the test source set needs them, not only androidTest.
    sourceSets {
        getByName("test").assets.srcDir("$projectDir/schemas")
    }
}

// exportSchema on GalleryDatabase writes one JSON file per version under this
// directory. Check those files in. A later migration test reads them back.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
}
