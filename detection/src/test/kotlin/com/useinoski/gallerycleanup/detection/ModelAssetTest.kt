package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.nsfw.NsfwVersions
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAssetTest {
    @Test
    fun bundledModelMatchesTheInspectedConversion() {
        val model = File("src/main/assets/${NsfwVersions.ASSET_NAME}")
        assertTrue(model.isFile)
        assertEquals(NsfwVersions.BYTE_SIZE, model.length())
        val header = ByteArray(8)
        model.inputStream().use { input -> check(input.read(header) == 8) }
        assertEquals("TFL3", header.copyOfRange(4, 8).toString(Charsets.US_ASCII))
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = model.inputStream().use { input -> digest.digest(input.readBytes()) }
        val hex = hash.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        assertEquals(NsfwVersions.SHA256, hex)
    }
}
