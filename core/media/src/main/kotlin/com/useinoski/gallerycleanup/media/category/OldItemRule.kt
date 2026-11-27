package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.CategoryRule
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaItemMetadata
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate

/**
 * How many calendar months old a file must be before it is [MediaCategory.OldItems].
 *
 * The allowed windows are the ones the review screen offers. Six is the
 * default on [OldItemRule].
 */
enum class OldItemMonths(val months: Int) {
    One(1),
    Three(3),
    Six(6),
    Twelve(12),
}

/**
 * Files whose best date is strictly older than [months] calendar months.
 *
 * The date is the first usable stamp on the item:
 *
 * 1. [MediaItemMetadata.dateTakenMillis] when it is greater than 0.
 *    That field is milliseconds. A value of `0`, null, or negative is missing.
 * 2. Otherwise [MediaItemMetadata.dateModified] when it is greater than 0.
 *    That field is seconds, the same unit as [MediaItemMetadata.dateAdded].
 * 3. Otherwise [MediaItemMetadata.dateAdded] when it is greater than 0,
 *    also seconds.
 *
 * No usable stamp means the file is not old. The rule does not guess.
 *
 * The first usable stamp wins. A taken date of 1970 does not fall through
 * to a later [MediaItemMetadata.dateAdded]. A stamp before
 * [EARLIEST_LOCAL_DATE] (a camera clock reset to the epoch) is not old.
 * A stamp on a later calendar day than today is not old. Today and the
 * cutoff day are not old: 6 months before 26 Nov is 26 May, and a file from
 * 26 May stays out. The day before the cutoff is old.
 *
 * "Month" is a [java.time.LocalDate] month in [clock]'s zone, not 30 days.
 * 31 Mar minus one month is 28 Feb, or 29 Feb in a leap year. The zone is
 * the clock's zone, so the cutoff day does not depend on the phone's default
 * zone. Tests pass [Clock.fixed].
 *
 * Images, videos, and audio all use this rule.
 *
 * The rule returns [MediaCategory.OldItems] or an empty set. It does not
 * write the library and it does not remove the file. It reads [clock] and
 * does not call the system clock.
 */
class OldItemRule(
    private val clock: Clock,
    private val months: OldItemMonths = OldItemMonths.Six,
) : CategoryRule {
    override fun categorize(item: MediaItemMetadata): Set<MediaCategory> {
        val instant = chosenInstant(item) ?: return emptySet()
        val zone = clock.zone
        val itemDate = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(clock)
        return if (isOld(itemDate, today)) setOf(MediaCategory.OldItems) else emptySet()
    }

    private fun isOld(itemDate: LocalDate, today: LocalDate): Boolean = when {
        itemDate.isBefore(EARLIEST_LOCAL_DATE) -> false
        itemDate.isAfter(today) -> false
        else -> itemDate.isBefore(today.minusMonths(months.months.toLong()))
    }

    companion object {
        /**
         * A local date before this day is a reset camera clock, not an old
         * photo. 1 Jan 2000 itself can be old. 31 Dec 1999 cannot.
         */
        val EARLIEST_LOCAL_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    }
}

private fun chosenInstant(item: MediaItemMetadata): Instant? {
    val taken = item.dateTakenMillis
    val modified = item.dateModified
    return when {
        taken != null && taken > 0L -> instantOfEpochMilli(taken)
        modified != null && modified > 0L -> instantOfEpochSecond(modified)
        item.dateAdded > 0L -> instantOfEpochSecond(item.dateAdded)
        else -> null
    }
}

private fun instantOfEpochMilli(millis: Long): Instant? = try {
    Instant.ofEpochMilli(millis)
} catch (_: DateTimeException) {
    null
}

private fun instantOfEpochSecond(seconds: Long): Instant? = try {
    Instant.ofEpochSecond(seconds)
} catch (_: DateTimeException) {
    null
}
