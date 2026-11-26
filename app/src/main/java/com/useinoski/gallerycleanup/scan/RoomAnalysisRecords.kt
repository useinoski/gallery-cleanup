package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.AnalysisFailure
import com.useinoski.gallerycleanup.data.RoomScanResultStore
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.model.MediaKey

/** [AnalysisRecords] backed by the process-wide Room store. */
internal class RoomAnalysisRecords(
    private val store: RoomScanResultStore,
) : AnalysisRecords {
    override suspend fun keptKeys(): Set<MediaKey> = store.keptKeys()

    override suspend fun upsert(results: List<ScanResult>) {
        store.upsert(results)
    }

    override suspend fun recordFailures(failures: List<AnalysisFailure>) {
        store.recordFailures(failures)
    }

    override suspend fun keysNeedingRescan(currentModelVersions: Collection<String>): List<MediaKey> {
        return store.keysNeedingRescan(currentModelVersions)
    }
}
