package com.useinoski.gallerycleanup.data

/**
 * How many items are still waiting for a decision, per [ScanCategory].
 *
 * Every category is present, including those with a count of zero. Kept and
 * trashed rows are not included. A row whose media item is marked removed is
 * not included; it comes back when that item is present again. A failed
 * analysis is not included in any category, including [ScanCategory.Unflagged].
 * [flagged] skips [ScanCategory.Unflagged], which is a scored file with
 * nothing to suggest.
 */
data class CategoryCounts(
    val pending: Map<ScanCategory, Int>,
) {
    init {
        val missing = ScanCategory.entries.filter { category -> category !in pending }
        require(missing.isEmpty()) { "Missing counts for $missing." }
    }

    operator fun get(category: ScanCategory): Int = pending.getValue(category)

    val flagged: Int
        get() = ScanCategory.entries
            .filter { category -> category != ScanCategory.Unflagged }
            .sumOf { category -> pending.getValue(category) }
}
