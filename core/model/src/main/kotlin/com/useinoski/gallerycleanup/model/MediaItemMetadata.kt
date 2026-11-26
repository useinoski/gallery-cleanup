package com.useinoski.gallerycleanup.model

/**
 * The facts a [CategoryRule] may read.
 *
 * This is not [MediaItem]. The scan already stores that type, and it has no
 * separate display name, no date taken, no duration, and no IS_MUSIC flag.
 * Those stay off [MediaItem] so the `media_item` table does not grow columns
 * for a rule that can read them while it scans. A later scan copies this
 * value off the MediaStore row and passes it to the rule. It does not have
 * to be written down.
 *
 * No Android type appears here. There is no `Uri`, no `ContentResolver`, and
 * no `Bitmap`, so this module stays plain Kotlin.
 *
 * Units are the ones MediaStore uses. [MediaItem] already uses the same
 * units for the fields the two types share.
 *
 * [type] is the collection the row came from. [mimeType] is sometimes null,
 * so a rule does not have to guess video from a missing type string.
 *
 * [relativePath] is the MediaStore folder, such as `DCIM/Camera/` or
 * `Screenshots/`. The store often keeps the trailing slash. It is not an
 * absolute filesystem path. [displayName] is the file name, such as
 * `IMG_001.jpg`. [MediaItem.path] is those two joined, with the slash
 * trimmed. They are split here so a folder rule does not have to parse the
 * file name back out.
 *
 * [dateAdded] is seconds since the Unix epoch, the same unit as
 * [MediaItem.dateAdded]. [dateTakenMillis] is milliseconds, which is what
 * `DATE_TAKEN` stores. Null when the row has no date taken. A negative
 * number is a broken timestamp, and a rule treats it as missing. This type
 * does not reject it, because the value came from the file.
 *
 * [durationMillis] is milliseconds. Null for a still image and for any row
 * that does not report a duration. [size] is bytes, the same unit as
 * [MediaItem.size].
 *
 * [isMusic] is `IS_MUSIC`. True only when the store set that flag. False for
 * a voice note, for other audio the store does not call music, and for
 * images and videos, which have no such column. The flag alone does not make
 * a file [MediaCategory.Music]: voice notes are excluded from that category
 * even when the flag is set. The rule that excludes them is a later change.
 */
data class MediaItemMetadata(
    val type: MediaType,
    val relativePath: String?,
    val displayName: String?,
    val mimeType: String?,
    val dateTakenMillis: Long?,
    val dateAdded: Long,
    val durationMillis: Long?,
    val isMusic: Boolean,
    val size: Long,
)
