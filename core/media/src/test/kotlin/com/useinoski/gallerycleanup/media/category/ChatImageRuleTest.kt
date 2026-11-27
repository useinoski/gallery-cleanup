package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageRuleTest {
    @Test
    fun rootsLiveInOneList() {
        assertTrue(chatImageRoots.contains(listOf("WhatsApp", "Media", "WhatsApp Images")))
        assertTrue(chatImageRoots.contains(listOf("WhatsApp Business", "Media", "WhatsApp Business Images")))
        assertTrue(chatImageRoots.contains(listOf("WhatsApp Business", "Media", "WhatsApp Images")))
        assertTrue(chatImageRoots.contains(listOf("Telegram", "Telegram Images")))
        assertTrue(chatImageRoots.contains(listOf("Pictures", "Telegram")))
        assertTrue(chatImageRoots.contains(listOf("org.telegram.messenger")))
        assertTrue(chatImageRoots.contains(listOf("Signal", "Media")))
        assertTrue(chatImageRoots.contains(listOf("org.thoughtcrime.securesms")))
        assertTrue(chatImageRoots.contains(listOf("Viber", "Media", "Viber Images")))
        assertTrue(chatImageRoots.contains(listOf("Pictures", "Messenger")))
        assertTrue(chatImageRoots.contains(listOf("com.facebook.orca")))
        assertTrue(chatImageRoots.all { root -> root.isNotEmpty() && root.all { segment -> segment.isNotBlank() } })
    }

    @Test
    fun whatsAppImagesMatchIncludingSentPrivateAndBusiness() {
        val folders = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private/",
            "WhatsApp/Media/WhatsApp Images/",
            "WhatsApp/Media/WhatsApp Images/Sent/",
            "WhatsApp/Media/WhatsApp Images/Private/",
            "whatsapp/media/whatsapp images/sent/",
            "WhatsApp\\Media\\WhatsApp Images\\",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/Sent/",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/Private/",
            "WhatsApp Business/Media/WhatsApp Business Images/",
            "WhatsApp Business/Media/WhatsApp Images/Sent/",
            "WhatsApp/Media/WhatsApp Animated Gifs/",
        )
        folders.forEach { folder ->
            assertEquals(
                folder,
                setOf(MediaCategory.ChatImages),
                ChatImageRule.categorize(imageAt(folder, name = "IMG-20250101-WA0001.jpg")),
            )
        }
    }

    @Test
    fun telegramAndSignalMatchSentAndReceived() {
        val folders = listOf(
            "Pictures/Telegram/",
            "Pictures/Telegram/Sent/",
            "Telegram/Telegram Images/",
            "Telegram/Telegram Images/Sent/",
            "Android/media/org.telegram.messenger/Telegram/Telegram Images/",
            "Android/media/org.telegram.messenger/Telegram/Telegram Images/Sent/",
            "Signal/Media/",
            "Signal/Media/Sent/",
            "Android/media/org.thoughtcrime.securesms/Signal/Media/",
            "Android/media/org.thoughtcrime.securesms/Signal/Media/Sent/",
            "Pictures/Signal/",
            "Pictures/Signal/Sent/",
        )
        folders.forEach { folder ->
            assertEquals(
                folder,
                setOf(MediaCategory.ChatImages),
                ChatImageRule.categorize(imageAt(folder, name = "photo.jpg")),
            )
        }
    }

    @Test
    fun viberAndMessengerMatch() {
        val folders = listOf(
            "Viber/Media/Viber Images/",
            "Viber/Media/Viber Images/Sent/",
            "Android/media/com.viber.voip/Viber/Media/Viber Images/Private/",
            "Pictures/Messenger/",
            "Pictures/Messenger/Sent/",
            "Android/media/com.facebook.orca/Messenger/",
            "Android/media/com.facebook.orca/Messenger/Sent/",
        )
        folders.forEach { folder ->
            assertEquals(folder, setOf(MediaCategory.ChatImages), ChatImageRule.categorize(imageAt(folder)))
        }
    }

    @Test
    fun otherWhatsAppMediaAndLookalikeFoldersDoNotMatch() {
        val folders = listOf(
            "WhatsApp/Media/WhatsApp Video/",
            "WhatsApp/Media/WhatsApp Audio/",
            "WhatsApp/Media/WhatsApp Voice Notes/",
            "WhatsApp/Media/WhatsApp Documents/",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/",
            "DCIM/Camera/",
            "Download/",
            "Notes/Signal/",
            "DCIM/Messenger/",
            "Pictures/TelegramBackup/",
        )
        folders.forEach { folder ->
            assertEquals(
                folder,
                emptySet<MediaCategory>(),
                ChatImageRule.categorize(imageAt(folder, name = "photo.jpg")),
            )
        }
    }

    @Test
    fun aCameraFileNamedWhatsAppIsNotAChatImage() {
        val item = imageAt(folder = "DCIM/Camera/", name = "WhatsApp.jpg")
        assertEquals(emptySet<MediaCategory>(), ChatImageRule.categorize(item))
    }

    @Test
    fun onlyImagesMatch() {
        val folder = "WhatsApp/Media/WhatsApp Images/Sent/"
        assertEquals(
            emptySet<MediaCategory>(),
            ChatImageRule.categorize(imageAt(folder, name = "clip.mp4", type = MediaType.Video)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            ChatImageRule.categorize(
                imageAt(
                    folder = "WhatsApp/Media/WhatsApp Voice Notes/",
                    name = "PTT-20250101.opus",
                    type = MediaType.Audio,
                ),
            ),
        )
    }
}
