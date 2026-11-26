package com.useinoski.gallerycleanup.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The same check the Gradle task runs, plus fixtures so a regression in the
 * matcher fails even when the current merge is clean.
 * Working directory for this test is the :app module.
 */
class MergedManifestGuardTest {
    @Test
    fun aGrantedNetworkPermissionIsAViolation() {
        val xml = """
            <manifest>
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
            </manifest>
        """.trimIndent()
        assertEquals(listOf("android.permission.INTERNET"), forbiddenPermissionsIn(xml, forbidden))
    }

    @Test
    fun anExplicitRemoveStillCountsIfItSurvivesTheMerge() {
        val xml = """
            <uses-permission
                android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
                tools:node="remove" />
        """.trimIndent()
        assertEquals(
            listOf("android.permission.MANAGE_EXTERNAL_STORAGE"),
            forbiddenPermissionsIn(xml, forbidden),
        )
    }

    @Test
    fun mediaReadPermissionsAreAllowed() {
        val xml = """
            <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
            <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
            <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
            <uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
            <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
        """.trimIndent()
        assertTrue(forbiddenPermissionsIn(xml, forbidden).isEmpty())
    }

    @Test
    fun sourceManifestRemovesNetworkAndAllFilesAccess() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        assertTrue(xml.contains("""android:allowBackup="false""""))
        assertTrue(xml.contains("""android:maxSdkVersion="32""""))
        forbidden.forEach { permission ->
            val tag = permissionTag(xml, permission)
            assertTrue("missing remove for $permission", tag.contains("""tools:node="remove""""))
        }
        assertFalse(xml.contains("""android:name="android.permission.INTERNET" />""") &&
            !xml.contains("tools:node=\"remove\""))
    }

    @Test
    fun aDatatransportServiceIsAViolation() {
        val xml = """
            <service android:name="com.google.android.datatransport.runtime.backends.TransportBackendDiscovery" android:exported="false" />
        """.trimIndent()
        assertEquals(
            listOf("com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"),
            forbiddenComponentsIn(xml, forbiddenComponents, allowedComponents),
        )
    }

    @Test
    fun aDatatransportReceiverAndProviderAreViolations() {
        val xml = """
            <receiver android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver" />
            <provider android:name="com.google.android.datatransport.runtime.backends.TransportBackendDiscovery" />
            <service
                android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService"
                android:exported="false"
                android:permission="android.permission.BIND_JOB_SERVICE" />
        """.trimIndent()
        assertEquals(
            listOf(
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
                "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
            ),
            forbiddenComponentsIn(xml, forbiddenComponents, allowedComponents),
        )
        assertEquals(null, jobInfoSchedulerProblem(xml))
    }

    @Test
    fun anExportedJobInfoSchedulerServiceIsAViolation() {
        val xml = """
            <service
                android:name="com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService"
                android:exported="true" />
        """.trimIndent()
        assertEquals(emptyList<String>(), forbiddenComponentsIn(xml, forbiddenComponents, allowedComponents))
        assertTrue(jobInfoSchedulerProblem(xml)!!.contains("BIND_JOB_SERVICE"))
    }

    @Test
    fun playServicesMlKitFaceDetectionInTheManifestIsAViolation() {
        val xml = """
            <service android:name="com.google.android.gms.play-services-mlkit-face-detection.ModelDownloadService" />
            <meta-data android:name="com.google.android.gms:play-services-mlkit-face-detection" android:value="17.1.0" />
        """.trimIndent()
        val found = forbiddenComponentsIn(xml, forbiddenComponents, allowedComponents)
        assertTrue(found.contains("com.google.android.gms.play-services-mlkit-face-detection.ModelDownloadService"))
        assertTrue(found.contains("play-services-mlkit-face-detection"))
    }

    @Test
    fun anExplicitComponentRemoveStillCountsIfItSurvivesTheMerge() {
        val xml = """
            <activity
                android:name="com.google.android.gms.common.api.GoogleApiActivity"
                tools:node="remove" />
        """.trimIndent()
        assertEquals(
            listOf("com.google.android.gms.common.api.GoogleApiActivity"),
            forbiddenComponentsIn(xml, forbiddenComponents, allowedComponents),
        )
    }

    @Test
    fun detectionManifestRemovesTelemetryComponents() {
        // The removes live on :detection. Those classes are on its compile
        // classpath. Declaring them in the app manifest makes lint report
        // MissingClass, because implementation dependencies are not compiled
        // into the app. The library merge still strips them from the app.
        val xml = File("../detection/src/main/AndroidManifest.xml").readText()
        forbiddenComponents.forEach { component ->
            val tags = componentTags(xml, component)
            assertTrue("missing declaration for $component", tags.isNotEmpty())
            tags.forEach { tag ->
                assertTrue("missing remove for $component in $tag", tag.contains("""tools:node="remove""""))
            }
        }
        assertFalse(xml.contains("play-services-mlkit-face-detection"))
        assertEquals(null, jobInfoSchedulerProblem(xml))
        assertFalse(componentTags(xml, "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService")
            .any { tag -> tag.contains("""tools:node="remove"""") })
    }

    @Test
    fun mergedManifestDoesNotGrantForbiddenPermissions() {
        val manifests = mergedManifests()
        manifests.forEach { manifest ->
            val found = forbiddenPermissionsIn(manifest.readText(), forbidden)
            assertTrue("${manifest.path} grants $found", found.isEmpty())
        }
    }

    @Test
    fun mergedManifestDoesNotExposeTelemetryOrUnbundledMlKit() {
        val manifests = mergedManifests()
        manifests.forEach { manifest ->
            val xml = manifest.readText()
            val found = forbiddenComponentsIn(xml, forbiddenComponents, allowedComponents)
            assertTrue("${manifest.path} contains $found", found.isEmpty())
            assertEquals("${manifest.path}: ${jobInfoSchedulerProblem(xml)}", null, jobInfoSchedulerProblem(xml))
            assertFalse(xml.contains("INTERNET"))
            assertFalse(xml.contains("ACCESS_NETWORK_STATE"))
            assertFalse(xml.contains("TransportBackendDiscovery"))
            assertFalse(xml.contains("AlarmManagerSchedulerBroadcastReceiver"))
            assertFalse(xml.contains("play-services-mlkit-face-detection"))
            assertFalse(xml.contains("GoogleApiActivity"))
        }
    }

    private fun mergedManifests(): List<File> {
        val root = File("build/intermediates/merged_manifest")
        val manifests = root.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList()
        assertTrue(
            "merged manifest missing under ${root.absolutePath}; checkDebugMergedManifest should have run first",
            manifests.isNotEmpty(),
        )
        return manifests
    }

    private val forbidden: Set<String> = nameList("../gradle/forbidden-permissions.txt")
    private val forbiddenComponents: Set<String> = nameList("../gradle/forbidden-manifest-components.txt")
    private val allowedComponents: Set<String> = nameList("../gradle/allowed-manifest-components.txt")
}

private fun nameList(path: String): Set<String> = File(path)
    .readLines()
    .map { line -> line.substringBefore("#").trim() }
    .filter { line -> line.isNotEmpty() }
    .toSet()

internal fun forbiddenPermissionsIn(xml: String, forbidden: Set<String>): List<String> {
    val tags = Regex("""<uses-permission(?:-sdk-\d+)?\b([^>]*)>""", RegexOption.IGNORE_CASE)
    val name = Regex("""android:name\s*=\s*"([^"]+)"""")
    return tags.findAll(xml).mapNotNull { match ->
        name.find(match.groupValues[1])?.groupValues?.get(1)
    }.filter { permission -> permission in forbidden }.distinct().toList()
}

internal fun forbiddenComponentsIn(
    xml: String,
    forbidden: Set<String>,
    allowed: Set<String>,
): List<String> {
    val tags = Regex("""<(service|receiver|provider|activity)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    val name = Regex("""android:name\s*=\s*"([^"]+)"""")
    val fromTags = tags.findAll(xml).mapNotNull { match ->
        name.find(match.groupValues[2])?.groupValues?.get(1)
    }.filter { component ->
        component !in allowed && (
            component in forbidden ||
                component.contains("datatransport") ||
                component.contains("play-services-mlkit")
            )
    }
    val artifact = if (xml.contains("play-services-mlkit-face-detection")) {
        listOf("play-services-mlkit-face-detection")
    } else {
        emptyList()
    }
    return (fromTags + artifact).distinct().toList()
}

internal fun jobInfoSchedulerProblem(xml: String): String? {
    val service = "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService"
    val tags = Regex("""<service\b[^>]*android:name="$service"[^>]*>""")
        .findAll(xml)
        .map { match -> match.value }
        .toList()
    if (tags.isEmpty()) return "missing $service"
    val unlocked = tags.filterNot { tag ->
        tag.contains("""android:exported="false"""") &&
            tag.contains("""android:permission="android.permission.BIND_JOB_SERVICE"""")
    }
    if (unlocked.isNotEmpty()) {
        return "$service must stay exported=false and require BIND_JOB_SERVICE"
    }
    return null
}

private fun permissionTag(xml: String, permission: String): String {
    val pattern = Regex("""<uses-permission\b[^>]*android:name="$permission"[^>]*>""")
    return pattern.find(xml)?.value ?: error("source manifest has no uses-permission for $permission")
}

private fun componentTags(xml: String, component: String): List<String> {
    val pattern = Regex("""<(service|receiver|provider|activity)\b[^>]*android:name="$component"[^>]*>""")
    return pattern.findAll(xml).map { match -> match.value }.toList()
}
