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
    // does pull the thin play-services-mlkit-face-detection client. The model
    // bytes are in this artifact (assets/models_bundled). The client cannot be
    // excluded: FaceDetection, FaceDetector, and FaceDetectorOptions are
    // compiled into that AAR, and :detection:compileDebugKotlin fails with
    // "Unresolved reference 'FaceDetection'" without it. The client loads the
    // local dynamite module (MODULE_VERSION 10000) instead of downloading.
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.image.labeling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
