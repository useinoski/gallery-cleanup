package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.MediaItemMetadata
import com.useinoski.gallerycleanup.model.MediaType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal fun imageAt(
    folder: String? = "DCIM/Camera/",
    name: String? = "IMG_001.jpg",
    type: MediaType = MediaType.Image,
    dateTakenMillis: Long? = null,
    dateModified: Long? = null,
    dateAdded: Long = 0L,
): MediaItemMetadata = MediaItemMetadata(
    type = type,
    relativePath = folder,
    displayName = name,
    mimeType = when (type) {
        MediaType.Image -> "image/jpeg"
        MediaType.Video -> "video/mp4"
        MediaType.Audio -> "audio/mpeg"
    },
    dateTakenMillis = dateTakenMillis,
    dateAdded = dateAdded,
    dateModified = dateModified,
    durationMillis = null,
    isMusic = false,
    size = 2_048L,
)

internal fun fixedClock(isoInstant: String, zone: ZoneId = ZoneOffset.UTC): Clock =
    Clock.fixed(Instant.parse(isoInstant), zone)

internal fun takenMillis(day: LocalDate, zone: ZoneId = ZoneOffset.UTC): Long =
    day.atStartOfDay(zone).toInstant().toEpochMilli()

internal fun epochSeconds(day: LocalDate, zone: ZoneId = ZoneOffset.UTC): Long =
    day.atStartOfDay(zone).toInstant().epochSecond
