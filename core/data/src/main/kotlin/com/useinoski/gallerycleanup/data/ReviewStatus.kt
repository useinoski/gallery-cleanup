package com.useinoski.gallerycleanup.data

/**
 * What the user has decided for one scanned item.
 *
 * Room stores the constant name (`Pending`, not `0`). Renaming a constant is
 * a schema change.
 */
enum class ReviewStatus {
    /** Waiting for a decision. The review lists show only these rows. */
    Pending,

    /** The user chose keep. A later scan must not replace the row. */
    Kept,

    /**
     * The user chose trash. The row stays until
     * [RoomScanResultStore.remove] runs after the system trash dialog succeeds,
     * so a cancelled dialog can be put back with [RoomScanResultStore.markPending].
     */
    Trashed,
}
