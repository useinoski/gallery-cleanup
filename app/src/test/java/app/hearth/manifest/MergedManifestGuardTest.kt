package app.hearth.manifest

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
    fun mergedManifestDoesNotGrantForbiddenPermissions() {
        val root = File("build/intermediates/merged_manifest")
        val manifests = root.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList()
        assertTrue(
            "merged manifest missing under ${root.absolutePath}; checkDebugMergedManifest should have run first",
            manifests.isNotEmpty(),
        )
        manifests.forEach { manifest ->
            val found = forbiddenPermissionsIn(manifest.readText(), forbidden)
            assertTrue("${manifest.path} grants $found", found.isEmpty())
        }
    }

    private val forbidden: Set<String> = File("../gradle/forbidden-permissions.txt")
        .readLines()
        .map { line -> line.substringBefore("#").trim() }
        .filter { line -> line.isNotEmpty() }
        .toSet()
}

internal fun forbiddenPermissionsIn(xml: String, forbidden: Set<String>): List<String> {
    val tags = Regex("""<uses-permission(?:-sdk-\d+)?\b([^>]*)>""", RegexOption.IGNORE_CASE)
    val name = Regex("""android:name\s*=\s*"([^"]+)"""")
    return tags.findAll(xml).mapNotNull { match ->
        name.find(match.groupValues[1])?.groupValues?.get(1)
    }.filter { permission -> permission in forbidden }.distinct().toList()
}

private fun permissionTag(xml: String, permission: String): String {
    val pattern = Regex("""<uses-permission\b[^>]*android:name="$permission"[^>]*>""")
    return pattern.find(xml)?.value ?: error("source manifest has no uses-permission for $permission")
}
