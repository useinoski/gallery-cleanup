plugins {
    id("gallery.android-library")
}

android {
    namespace = "com.useinoski.gallerycleanup.detection"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // aapt compresses assets unless told not to. A memory-mapped .tflite file
    // has to be stored uncompressed. The loader still falls back to a byte
    // copy when a consuming app compresses the asset anyway.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // LiteRT Interpreter, plus the GPU delegate. NNAPI ships inside litert.
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    // Bundled ML Kit only. Do not add com.google.android.gms:play-services-mlkit-*.
    // Those artifacts download a model over the network. face-detection's POM
    // does pull the thin play-services-mlkit-face-detection client; the model
    // bytes themselves are inside the com.google.mlkit artifact.
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.image.labeling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
