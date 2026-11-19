package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.nsfw.NsfwVersions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyNoticesTest {
    @Test
    fun assetMatchesTheComponentsTheAppCanShow() {
        val text = File("src/main/assets/${ThirdPartyNotices.ASSET_PATH}").readText()
        ThirdPartyNotices.components.forEach { component ->
            assertTrue(text.contains(component.name))
            assertTrue(text.contains(component.url))
            assertTrue(text.contains(component.license))
        }
        assertTrue(text.contains("Apache License"))
        assertTrue(text.contains("Version 2.0, January 2004"))
        assertTrue(text.contains(NsfwVersions.SHA256))
        assertTrue(text.contains("No face is identified"))
        assertEquals(4, ThirdPartyNotices.components.size)
    }
}
