package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.detection.MediaDetector

/**
 * Every `model_version` the current pipeline still writes.
 *
 * A detector row stores `detectorId|modelVersion`, the same text
 * [com.useinoski.gallerycleanup.detection.DetectionScoring.reviewIdentity]
 * builds. Rule rows store [FlagPolicy.VIDEO_RULE], [FlagPolicy.AUDIO_RULE],
 * or [FlagPolicy.SCREENSHOT_PATH_RULE]. A clean photo stores [FlagPolicy.NONE].
 *
 * [com.useinoski.gallerycleanup.data.RoomScanResultStore.keysNeedingRescan]
 * returns a scored row whose version is missing from this set. Leaving out
 * [FlagPolicy.NONE] would classify every clean photo again on the next scan.
 * Leaving out a rule would do the same to every video, audio file, or
 * screenshot that rule filed. Failed rows are returned either way.
 */
internal fun pipelineModelVersions(detectors: Iterable<MediaDetector>): Set<String> {
    val versions = LinkedHashSet<String>()
    for (detector in detectors) {
        require(detector.id.isNotBlank()) { "detectorId is blank" }
        require(detector.modelVersion.isNotBlank()) { "modelVersion is blank" }
        versions += detector.id + "|" + detector.modelVersion
    }
    versions += FlagPolicy.VIDEO_RULE
    versions += FlagPolicy.AUDIO_RULE
    versions += FlagPolicy.SCREENSHOT_PATH_RULE
    versions += FlagPolicy.NONE
    return versions
}
