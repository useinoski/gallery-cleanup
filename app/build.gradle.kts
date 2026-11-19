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

// Fails the build if a library merges network or all-files access back in.
// `./gradlew test` and `./gradlew assembleDebug` both run the debug check.
val forbiddenPermissionNames: Set<String> = rootProject.file("gradle/forbidden-permissions.txt")
    .readLines()
    .map { line -> line.substringBefore("#").trim() }
    .filter { line -> line.isNotEmpty() }
    .toSet()

listOf("debug", "release").forEach { variant ->
    val cap = variant.replaceFirstChar { it.uppercase() }
    val check = tasks.register("check${cap}MergedManifest") {
        group = "verification"
        description = "Fails if the merged $variant manifest grants network or all-files access."
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
                val found = forbiddenPermissionsIn(manifest.readText(), forbiddenPermissionNames)
                if (found.isNotEmpty()) {
                    error(
                        "Merged manifest ${manifest.path} contains forbidden permissions: " +
                            found.joinToString() +
                            ". Gallery Cleanup must not ship INTERNET, ACCESS_NETWORK_STATE, " +
                            "MANAGE_EXTERNAL_STORAGE, or MANAGE_MEDIA.",
                    )
                }
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

fun forbiddenPermissionsIn(xml: String, forbidden: Set<String>): List<String> {
    val tags = Regex("""<uses-permission(?:-sdk-\d+)?\b([^>]*)>""", RegexOption.IGNORE_CASE)
    val name = Regex("""android:name\s*=\s*"([^"]+)"""")
    return tags.findAll(xml).mapNotNull { match ->
        name.find(match.groupValues[1])?.groupValues?.get(1)
    }.filter { permission -> permission in forbidden }.distinct().toList()
}
