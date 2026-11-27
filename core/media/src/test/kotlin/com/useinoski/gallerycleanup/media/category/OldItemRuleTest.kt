package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OldItemRuleTest {
    private val today = LocalDate.parse("2026-11-26")
    private val clock = fixedClock("2026-11-26T12:00:00Z")

    @Test
    fun cutoffDayIsNotOldAndTheDayBeforeIs() {
        listOf(
            OldItemMonths.One,
            OldItemMonths.Three,
            OldItemMonths.Six,
            OldItemMonths.Twelve,
        ).forEach { window ->
            val rule = OldItemRule(clock = clock, months = window)
            val cutoff = today.minusMonths(window.months.toLong())
            assertEquals(
                "${window.months} months, cutoff $cutoff",
                emptySet<MediaCategory>(),
                rule.categorize(taken(cutoff)),
            )
            assertEquals(
                "${window.months} months, day before $cutoff",
                setOf(MediaCategory.OldItems),
                rule.categorize(taken(cutoff.minusDays(1))),
            )
        }
    }

    @Test
    fun theDefaultWindowIsSixMonths() {
        val rule = OldItemRule(clock = clock)
        val cutoff = today.minusMonths(6)
        assertEquals(emptySet<MediaCategory>(), rule.categorize(taken(cutoff)))
        assertEquals(setOf(MediaCategory.OldItems), rule.categorize(taken(cutoff.minusDays(1))))
    }

    @Test
    fun theClockZoneDecidesTheCutoffDay() {
        // 2026-06-15 03:00 UTC is still 2026-06-14 in New York (EDT, UTC-4).
        // The photo instant is 2026-05-14 04:30 UTC: 14 May in UTC, 14 May
        // 00:30 in New York. One month back from 15 Jun UTC is 15 May, so
        // 14 May is old. One month back from 14 Jun in New York is 14 May,
        // and the cutoff day itself is not old.
        val instant = "2026-06-15T03:00:00Z"
        val photo = java.time.Instant.parse("2026-05-14T04:30:00Z").toEpochMilli()
        val utc = OldItemRule(clock = fixedClock(instant), months = OldItemMonths.One)
        val newYork = OldItemRule(
            clock = fixedClock(instant, ZoneId.of("America/New_York")),
            months = OldItemMonths.One,
        )
        val item = imageAt(dateTakenMillis = photo)
        assertEquals(setOf(MediaCategory.OldItems), utc.categorize(item))
        assertEquals(emptySet<MediaCategory>(), newYork.categorize(item))
    }

    @Test
    fun marchThirtyFirstClampsToTheEndOfFebruary() {
        val march = fixedClock("2026-03-31T12:00:00Z")
        val rule = OldItemRule(clock = march, months = OldItemMonths.One)
        assertEquals(emptySet<MediaCategory>(), rule.categorize(taken(LocalDate.parse("2026-02-28"))))
        assertEquals(setOf(MediaCategory.OldItems), rule.categorize(taken(LocalDate.parse("2026-02-27"))))
    }

    @Test
    fun missingOrZeroTakenDateFallsBackToModifiedThenAdded() {
        val oldModified = epochSeconds(LocalDate.parse("2020-01-01"))
        val oldAdded = epochSeconds(LocalDate.parse("2019-06-01"))
        val recentAdded = epochSeconds(LocalDate.parse("2026-11-01"))
        val rule = OldItemRule(clock = clock, months = OldItemMonths.Twelve)

        assertEquals(
            setOf(MediaCategory.OldItems),
            rule.categorize(imageAt(dateTakenMillis = null, dateModified = oldModified, dateAdded = recentAdded)),
        )
        assertEquals(
            setOf(MediaCategory.OldItems),
            rule.categorize(imageAt(dateTakenMillis = 0L, dateModified = oldModified, dateAdded = recentAdded)),
        )
        assertEquals(
            setOf(MediaCategory.OldItems),
            rule.categorize(imageAt(dateTakenMillis = -1L, dateModified = null, dateAdded = oldAdded)),
        )
        assertEquals(
            setOf(MediaCategory.OldItems),
            rule.categorize(imageAt(dateTakenMillis = null, dateModified = 0L, dateAdded = oldAdded)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = null, dateModified = null, dateAdded = recentAdded)),
        )
    }

    @Test
    fun dateModifiedAndDateAddedAreSeconds() {
        // 1_577_836_800 seconds is 2020-01-01. The same number as milliseconds
        // is 19 Jan 1970, which the pre-2000 guard would reject.
        val newYear2020 = 1_577_836_800L
        val rule = OldItemRule(clock = clock, months = OldItemMonths.Twelve)
        assertEquals(
            setOf(MediaCategory.OldItems),
            rule.categorize(imageAt(dateModified = newYear2020)),
        )
        assertEquals(
            setOf(MediaCategory.OldItems),
            rule.categorize(imageAt(dateAdded = newYear2020)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = newYear2020, dateAdded = newYear2020)),
        )
    }

    @Test
    fun aPresentTakenDateWinsOverTheLaterStamps() {
        val recent = takenMillis(LocalDate.parse("2026-11-01"))
        val ancient = epochSeconds(LocalDate.parse("2010-01-01"))
        val rule = OldItemRule(clock = clock, months = OldItemMonths.One)
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = recent, dateModified = ancient, dateAdded = ancient)),
        )
    }

    @Test
    fun futureDatesAreNotOld() {
        val rule = OldItemRule(clock = clock, months = OldItemMonths.One)
        val tomorrow = takenMillis(LocalDate.parse("2026-11-27"))
        val ancient = epochSeconds(LocalDate.parse("2010-01-01"))
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = tomorrow, dateAdded = ancient)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateModified = epochSeconds(LocalDate.parse("2027-01-01")), dateAdded = ancient)),
        )
    }

    @Test
    fun pre2000ResetClocksAreNotOld() {
        val rule = OldItemRule(clock = clock, months = OldItemMonths.One)
        val recent = epochSeconds(LocalDate.parse("2024-01-01"))
        assertEquals(LocalDate.parse("2000-01-01"), OldItemRule.EARLIEST_LOCAL_DATE)
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = takenMillis(LocalDate.parse("1999-12-31")), dateAdded = recent)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = takenMillis(LocalDate.parse("1970-01-02")), dateAdded = recent)),
        )
        // One second after the epoch is a real modified stamp, and it is
        // pre-2000, so the rule does not continue on to dateAdded.
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateModified = 1L, dateAdded = epochSeconds(LocalDate.parse("2010-01-01")))),
        )
        assertEquals(
            setOf(MediaCategory.OldItems),
            rule.categorize(imageAt(dateTakenMillis = takenMillis(LocalDate.parse("2000-01-01")))),
        )
    }

    @Test
    fun noUsableDateIsNotOld() {
        val rule = OldItemRule(clock = clock)
        assertEquals(emptySet<MediaCategory>(), rule.categorize(imageAt()))
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = 0L, dateModified = 0L, dateAdded = 0L)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            rule.categorize(imageAt(dateTakenMillis = null, dateModified = null, dateAdded = -5L)),
        )
        assertNull(imageAt().dateModified)
    }

    @Test
    fun videosUseTheSameWindow() {
        val rule = OldItemRule(clock = clock, months = OldItemMonths.Twelve)
        val item = imageAt(
            folder = "DCIM/Camera/",
            name = "clip.mp4",
            type = MediaType.Video,
            dateTakenMillis = takenMillis(LocalDate.parse("2020-01-01")),
        )
        assertEquals(setOf(MediaCategory.OldItems), rule.categorize(item))
    }

    private fun taken(day: LocalDate) = imageAt(dateTakenMillis = takenMillis(day))
}
