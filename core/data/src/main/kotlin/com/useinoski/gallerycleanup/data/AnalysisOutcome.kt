package com.useinoski.gallerycleanup.data

/**
 * Whether a detector or a rule produced the row, or the attempt failed.
 *
 * Room stores the constant name (`Scored`, not `0`). Renaming a constant is
 * a schema change.
 *
 * This is not [ReviewStatus]. Review status is the user's decision. Outcome
 * is how the scan got the score. A failed row is not a clean photo, even
 * when [ScanCategory] is [ScanCategory.Unflagged].
 */
enum class AnalysisOutcome {
    /**
     * A detector or a rule wrote this row. [ScanCategory.Unflagged] with this
     * outcome is a clean photo: the file was scored and nothing was suggested.
     */
    Scored,

    /**
     * Decode or detection failed. Category, confidence, and model version are
     * placeholders. Counts and pending lists skip the row. It is not clean.
     */
    Failed,
}

/**
 * `model_version` for a scored row that nothing classified, and the
 * placeholder [RoomScanResultStore.recordFailure] writes.
 *
 * The two cases share this string. [AnalysisOutcome] is what tells them
 * apart. Version 2 stored both as [ScanCategory.Unflagged] plus this text.
 * [MIGRATION_2_3] leaves that pair [AnalysisOutcome.Scored], because a clean
 * photo cannot be picked out of it. [MIGRATION_3_4] restores rows an earlier
 * migration marked [AnalysisOutcome.Failed] with `failure_count` 0.
 */
const val MODEL_VERSION_NONE: String = "none"
