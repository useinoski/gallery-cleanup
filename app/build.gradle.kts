plugins {
    id("gallery.android-application")
}

android {
    namespace = "com.useinoski.gallerycleanup"

    defaultConfig {
        applicationId = "com.useinoski.gallerycleanup"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:media"))
    implementation(project(":core:data"))
    implementation(project(":detection"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

// Fails the build if a library merges network access, all-files access,
// datatransport telemetry components, or a Play Services ML Kit download
// component back in. `./gradlew test` and `./gradlew assembleDebug` both run
// the debug check.
val forbiddenPermissionNames: Set<String> = manifestNameList("gradle/forbidden-permissions.txt")
val forbiddenComponentNames: Set<String> = manifestNameList("gradle/forbidden-manifest-components.txt")

listOf("debug", "release").forEach { variant ->
    val cap = variant.replaceFirstChar { it.uppercase() }
    val check = tasks.register("check${cap}MergedManifest") {
        group = "verification"
        description = "Fails if the merged $variant manifest grants network access or ships telemetry components."
        dependsOn("process${cap}MainManifest")
        doLast {
            val root = layout.buildDirectory.get().asFile.resolve("intermediates/merged_manifest/$variant")
            val manifests = root.walkTopDown()
                .filter { file -> file.isFile && file.name == "AndroidManifest.xml" }
                .toList()
            if (manifests.isEmpty()) {
                error("No merged manifest under $root")
            }
            manifests.forEach { manifest ->
                val xml = manifest.readText()
                val permissions = forbiddenPermissionsIn(xml, forbiddenPermissionNames)
                if (permissions.isNotEmpty()) {
                    error(
                        "Merged manifest ${manifest.path} contains forbidden permissions: " +
                            permissions.joinToString() +
                            ". Gallery Cleanup must not ship INTERNET, ACCESS_NETWORK_STATE, " +
                            "MANAGE_EXTERNAL_STORAGE, or MANAGE_MEDIA.",
                    )
                }
                val components = forbiddenComponentsIn(xml, forbiddenComponentNames)
                if (components.isNotEmpty()) {
                    error(
                        "Merged manifest ${manifest.path} contains forbidden components: " +
                            components.joinToString() +
                            ". Remove datatransport telemetry, Play Services resolution UI, " +
                            "and play-services-mlkit-face-detection manifest entries.",
                    )
                }
            }
            // resolutionResult, not resolvedConfiguration: the Android runtime
            // classpath publishes many artifact variants and the file view is ambiguous.
            val playMlkit = configurations.getByName("${variant}RuntimeClasspath")
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { component ->
                    component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                }
                .filter { id ->
                    id.group == "com.google.android.gms" && id.module.startsWith("play-services-mlkit-")
                }
                .map { id -> "${id.group}:${id.module}:${id.version}" }
                .distinct()
            // FaceDetection, FaceDetector, and FaceDetectorOptions are compiled into
            // play-services-mlkit-face-detection. com.google.mlkit:face-detection
            // depends on it and the build does not compile without it. Any other
            // play-services-mlkit artifact is the downloadable model and is forbidden.
            val allowedClient = "com.google.android.gms:play-services-mlkit-face-detection:"
            val unexpected = playMlkit.filterNot { coordinate -> coordinate.startsWith(allowedClient) }
            if (unexpected.isNotEmpty()) {
                error(
                    "Unexpected unbundled ML Kit artifacts on the $variant runtime classpath: " +
                        unexpected.joinToString() +
                        ". Only the face-detection API client pulled by com.google.mlkit:face-detection is allowed.",
                )
            }
        }
    }
    tasks.matching { task -> task.name == "assemble$cap" || task.name == "check" }.configureEach {
        dependsOn(check)
    }
}

tasks.matching { task -> task.name == "testDebugUnitTest" }.configureEach {
    dependsOn("checkDebugMergedManifest")
}

fun manifestNameList(path: String): Set<String> = rootProject.file(path)
    .readLines()
    .map { line -> line.substringBefore("#").trim() }
    .filter { line -> line.isNotEmpty() }
    .toSet()

fun forbiddenPermissionsIn(xml: String, forbidden: Set<String>): List<String> {
    val tags = Regex("""<uses-permission(?:-sdk-\d+)?\b([^>]*)>""", RegexOption.IGNORE_CASE)
    val name = Regex("""android:name\s*=\s*"([^"]+)"""")
    return tags.findAll(xml).mapNotNull { match ->
        name.find(match.groupValues[1])?.groupValues?.get(1)
    }.filter { permission -> permission in forbidden }.distinct().toList()
}

fun forbiddenComponentsIn(xml: String, forbidden: Set<String>): List<String> {
    val tags = Regex("""<(service|receiver|provider|activity)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    val name = Regex("""android:name\s*=\s*"([^"]+)"""")
    val fromTags = tags.findAll(xml).mapNotNull { match ->
        name.find(match.groupValues[2])?.groupValues?.get(1)
    }.filter { component ->
        component in forbidden ||
            component.contains("datatransport") ||
            component.contains("play-services-mlkit")
    }
    val artifact = if (xml.contains("play-services-mlkit-face-detection")) {
        listOf("play-services-mlkit-face-detection")
    } else {
        emptyList()
    }
    return (fromTags + artifact).distinct().toList()
}
