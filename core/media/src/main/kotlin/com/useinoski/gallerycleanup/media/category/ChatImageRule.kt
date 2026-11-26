package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.CategoryRule
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaItemMetadata
import com.useinoski.gallerycleanup.model.MediaType

/**
 * Chat-image folders, as whole path segments in order.
 *
 * This is the only list. A path matches when it contains one of these
 * sequences, so `Sent` and `Private` under the folder still match, and so
 * does a file sitting directly in the folder (the received copy).
 * Comparison ignores case.
 *
 * The sequence is the image folder, not the whole app tree. `WhatsApp Video`,
 * `WhatsApp Audio`, and `WhatsApp Voice Notes` are not here. A folder that
 * is only named `Signal` or `Messenger` is not enough: `Notes/Signal` must
 * not match.
 *
 * Scoped-storage trees are covered when the same folder names appear inside
 * them (`Android/media/com.whatsapp/.../WhatsApp Images`,
 * `Android/media/com.whatsapp.w4b/...`, `org.telegram.messenger`,
 * `org.thoughtcrime.securesms`). The package segment is listed only where
 * the files do not also sit under one of the folder sequences.
 */
val chatImageRoots: List<List<String>> = listOf(
    listOf("WhatsApp", "Media", "WhatsApp Images"),
    listOf("WhatsApp", "Media", "WhatsApp Animated Gifs"),
    listOf("WhatsApp Business", "Media", "WhatsApp Business Images"),
    listOf("WhatsApp Business", "Media", "WhatsApp Images"),
    listOf("Telegram", "Telegram Images"),
    listOf("Pictures", "Telegram"),
    listOf("org.telegram.messenger"),
    listOf("Signal", "Media"),
    listOf("Pictures", "Signal"),
    listOf("org.thoughtcrime.securesms"),
    listOf("Viber", "Media", "Viber Images"),
    listOf("Pictures", "Messenger"),
    listOf("com.facebook.orca"),
)

/**
 * Images saved by a chat app, from [chatImageRoots].
 *
 * Only [MediaType.Image]. A video in `WhatsApp Images` is not a chat image
 * here. The display name is not consulted: `DCIM/Camera/WhatsApp.jpg` is a
 * camera photo. The rule returns [MediaCategory.ChatImages] or an empty set.
 * It does not write MediaStore and it does not remove the file.
 */
object ChatImageRule : CategoryRule {
    override fun categorize(item: MediaItemMetadata): Set<MediaCategory> {
        if (item.type != MediaType.Image) return emptySet()
        val segments = item.relativePath.pathSegments()
        val matched = chatImageRoots.any { root -> segments.containsSequence(root) }
        return if (matched) setOf(MediaCategory.ChatImages) else emptySet()
    }
}
