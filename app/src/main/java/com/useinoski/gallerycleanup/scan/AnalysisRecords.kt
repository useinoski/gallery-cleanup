package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.AnalysisFailure
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.model.MediaKey

/**
 * Review writes the batch observer and the rescan pass share.
 *
 * [com.useinoski.gallerycleanup.data.RoomScanResultStore] already has these
 * methods. They are not on [com.useinoski.gallerycleanup.model.ScanStore].
 * This interface exists so a JVM test can pass a fake without Room.
 */
internal interface AnalysisRecords {
    suspend fun keptKeys(): Set<MediaKey>

    suspend fun upsert(results: List<ScanResult>)

    suspend fun recordFailures(failures: List<AnalysisFailure>)

    suspend fun keysNeedingRescan(currentModelVersions: Collection<String>): List<MediaKey>
}
