package com.useinoski.gallerycleanup.model

/**
 * A review group a library file can belong to.
 *
 * A file may belong to more than one. [CategoryRule] reports every match and
 * does not pick a winner. Declaration order is the order to show the groups.
 * It is not a priority, and a second match is not dropped because a first
 * one also matched.
 *
 * Persist [id]. [fromId] reads that string back. The Kotlin [name]
 * (`Sensitive`) and the ordinal are not ids. `fromId("sensitive")` is
 * [Sensitive]. `fromId("Sensitive")` is null. Renaming a constant is not a
 * schema change. Changing an [id] is.
 *
 * [sensitive] means the review screen blurs the thumbnail. Blur when any
 * matched category has the flag, not only when it is the first match. Only
 * [Sensitive] is sensitive. Nudity and immodest content are that one group.
 * The scan still stores them as two buckets (`Sensitive` and `Immodest` in
 * `:core:data`). This type does not.
 *
 * [experimental] means the label is a guess and the screen should say so.
 * Only [Experimental] is experimental. Alcohol and gambling labels are that
 * group. The scan still stores them as `AlcoholGambling`.
 *
 * How today's stored names line up. This module does not read or write them:
 *
 * - `Sensitive` and `Immodest` → [Sensitive]
 * - `Screenshot` → [Screenshots]. A meme filed there stays there until a
 *   later rule places it in [ChatImages].
 * - `People` → [People]
 * - `Video` → [Videos]
 * - `Audio` → [Music] or [OtherAudio]. The stored row has no IS_MUSIC
 *   column, so the database alone cannot split it.
 * - `AlcoholGambling` → [Experimental]
 * - `Unflagged` → no category here. An empty match set is not a value of
 *   this type.
 *
 * [ChatImages] and [OldItems] have no stored bucket yet.
 *
 * An enum, not a sealed interface. Every category has the same three facts,
 * which is also why [MediaType] is an enum. [PermissionState] is a sealed
 * class because one state may later carry a field the others do not. An
 * enum constant cannot grow its own fields.
 *
 * No path rule and no detector lives in this module. Nothing here deletes a
 * file.
 */
enum class MediaCategory(
    val id: String,
    val sensitive: Boolean,
    val experimental: Boolean,
) {
    /**
     * Nudity or immodest content, from the existing NSFW detector.
     * Thumbnails are blurred. This is not a path rule.
     */
    Sensitive(
        id = "sensitive",
        sensitive = true,
        experimental = false,
    ),

    /**
     * Screenshots. A later rule matches the Screenshots folder.
     * Memes and chat-app downloads are [ChatImages].
     */
    Screenshots(
        id = "screenshots",
        sensitive = false,
        experimental = false,
    ),

    /**
     * Memes and pictures saved by a chat app. The scan today files a meme
     * label with the screenshots. This category is that split. The rule is
     * a later change.
     */
    ChatImages(
        id = "chat-images",
        sensitive = false,
        experimental = false,
    ),

    /**
     * Files old enough to review. The age cutoff belongs to the rule, which
     * is not in this module. Prefer [MediaItemMetadata.dateTakenMillis] when
     * it is present, and [MediaItemMetadata.dateAdded] otherwise.
     */
    OldItems(
        id = "old-items",
        sensitive = false,
        experimental = false,
    ),

    /**
     * Audio that MediaStore flags with IS_MUSIC, excluding voice notes.
     * A voice note belongs to [OtherAudio] even when that flag is set.
     * No rule here applies the exclusion yet. [MediaItemMetadata.isMusic] is
     * the flag the rule will read.
     */
    Music(
        id = "music",
        sensitive = false,
        experimental = false,
    ),

    /**
     * Other audio and voice notes. The product plan calls this
     * OtherAudio/VoiceNotes: recordings, ringtones, and chat voice notes.
     * [MediaType] stays [MediaType.Audio] for both this and [Music].
     */
    OtherAudio(
        id = "other-audio",
        sensitive = false,
        experimental = false,
    ),

    /**
     * Videos a person looks through. Manual review. This is not
     * [MediaType.Video]. A video of a person can also be [People].
     */
    Videos(
        id = "videos",
        sensitive = false,
        experimental = false,
    ),

    /**
     * At least one face, from the existing face detector. Manual review.
     * The app does not guess who the person is.
     */
    People(
        id = "people",
        sensitive = false,
        experimental = false,
    ),

    /**
     * Alcohol or gambling, from the existing image-label detector.
     * Experimental, so expect misses. Thumbnails are not blurred.
     */
    Experimental(
        id = "experimental",
        sensitive = false,
        experimental = true,
    );

    companion object {
        /**
         * The category stored under [id], or null when this version has no
         * such id.
         *
         * The match is exact, including case. Null means the string is not a
         * known id. It does not mean the file was scanned and nothing
         * matched. Callers keep a row with an unknown id out of the review
         * groups rather than filing it under a nearby category.
         */
        fun fromId(id: String): MediaCategory? =
            entries.find { category -> category.id == id }
    }
}
