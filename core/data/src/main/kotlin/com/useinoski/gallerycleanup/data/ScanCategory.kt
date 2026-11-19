package com.useinoski.gallerycleanup.data

/**
 * The bucket a scanned item was placed in.
 *
 * One row stores one category. When several detectors match, the scan worker
 * picks one before it calls [RoomScanResultStore.upsert]. A useful order,
 * strongest suggestion first, is [Sensitive], [Immodest], [AlcoholGambling],
 * [People], [Screenshot], [Video], [Audio], then [Unflagged]. The database
 * does not enforce that order.
 *
 * Room stores the constant name (`Sensitive`, not `0`). Renaming a constant
 * is a schema change and needs a migration.
 */
enum class ScanCategory {
    /** Nudity or other sensitive images. The review screen should blur these. */
    Sensitive,

    /** Suggestive, not explicit. Kept separate so its threshold can differ. */
    Immodest,

    /**
     * Screenshots. Memes and chat-folder images are not a separate bucket yet;
     * the worker may file those here until one is added.
     */
    Screenshot,

    /** At least one face. The app does not guess who the person is. */
    People,

    /**
     * Videos listed for a person to look through. This is not the file type.
     * The file type is [com.useinoski.gallerycleanup.model.MediaType].
     */
    Video,

    /** Audio, including music. */
    Audio,

    /** Experimental alcohol or gambling match. Expect misses. */
    AlcoholGambling,

    /** The file was scanned and nothing was suggested. Not a review group. */
    Unflagged,
}
