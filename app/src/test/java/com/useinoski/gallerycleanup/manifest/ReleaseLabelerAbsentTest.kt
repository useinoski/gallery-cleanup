package com.useinoski.gallerycleanup.manifest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The debug labeler is merged from :detection's debug manifest. The release
 * APK must not contain the activity, and the release source sets must not
 * contain the screen or the label file.
 *
 * Working directory is the :app module. The merged manifests are produced by
 * checkDebugMergedManifest and checkReleaseMergedManifest, which this test
 * task depends on.
 */
class ReleaseLabelerAbsentTest {
    @Test
    fun releaseMergedManifestHasNoLabeler() {
        merged("release").forEach { manifest ->
            val xml = manifest.readText()
            assertFalse(manifest.path, xml.contains("NsfwLabelActivity"))
            assertFalse(manifest.path, xml.contains("nsfw_labeler"))
            assertFalse(manifest.path, xml.contains("nsfw-labeler"))
        }
    }

    @Test
    fun debugMergedManifestHasTheLabeler() {
        val manifests = merged("debug")
        assertTrue(
            "debug merged manifest does not declare NsfwLabelActivity",
            manifests.any { manifest -> manifest.readText().contains("NsfwLabelActivity") },
        )
    }

    @Test
    fun releaseSourceSetsHaveNoLabeler() {
        val roots = listOf(
            File("src/main"),
            File("src/release"),
            File("../detection/src/main"),
            File("../detection/src/release"),
        )
        roots.flatMap { root -> filesUnder(root) }.forEach { file ->
            val text = file.readText()
            assertFalse(file.path, text.contains("NsfwLabelActivity"))
            assertFalse(file.path, text.contains("NsfwLabelStore"))
            assertFalse(file.path, text.contains("nsfw-labeler"))
        }
    }

    private fun merged(variant: String): List<File> {
        val root = File("build/intermediates/merged_manifest/$variant")
        val manifests = root.walkTopDown()
            .filter { file -> file.isFile && file.name == "AndroidManifest.xml" }
            .toList()
        assertTrue(
            "merged manifest missing under ${root.absolutePath}; check${variant.replaceFirstChar { it.uppercase() }}MergedManifest should have run first",
            manifests.isNotEmpty(),
        )
        return manifests
    }
}

private fun filesUnder(root: File): List<File> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .filter { file -> file.isFile && (file.extension == "kt" || file.extension == "java" || file.extension == "xml") }
        .toList()
}
