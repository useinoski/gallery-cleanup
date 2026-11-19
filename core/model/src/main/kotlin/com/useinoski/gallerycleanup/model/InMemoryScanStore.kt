package com.useinoski.gallerycleanup.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local [ScanStore].
 *
 * The app binds this until `:core:data` provides a Room implementation. The
 * worker tests bind it too: a "kill" in those tests drops the worker and
 * keeps this object, which is the resume the durable store will provide
 * across process death. This object itself does not survive process death
 * or reboot, and it must not be described as durable.
 *
 * A single [Mutex] orders the suspend calls. [observeProgress] is a
 * [kotlinx.coroutines.flow.StateFlow] updated inside that lock, so a collector
 * sees a [saveRun] that has already returned and never sees a torn
 * [ScanRun.byType].
 */
class InMemoryScanStore : ScanStore {
    private val mutex = Mutex()
    private val rows = LinkedHashMap<MediaKey, Stored>()
    private var checkpoint: ScanCheckpoint? = null
    private var run: ScanRun = ScanRun.IDLE
    private val progress = MutableStateFlow(ScanProgress.from(ScanRun.IDLE))

    override suspend fun upsertBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        mutex.withLock {
            for (item in items) {
                rows[MediaKey(item.type, item.id)] = Stored(item = item, removed = false)
            }
        }
    }

    override suspend fun markRemoved(keys: List<MediaKey>) {
        if (keys.isEmpty()) return
        mutex.withLock {
            for (key in keys) {
                val stored = rows[key] ?: continue
                if (!stored.removed) {
                    rows[key] = stored.copy(removed = true)
                }
            }
        }
    }

    override suspend fun presentKeys(type: MediaType): List<MediaKey> = mutex.withLock {
        rows.mapNotNull { (key, stored) ->
            if (key.type == type && !stored.removed) key else null
        }
    }

    override suspend fun readCheckpoint(): ScanCheckpoint? = mutex.withLock { checkpoint }

    override suspend fun saveCheckpoint(checkpoint: ScanCheckpoint) {
        mutex.withLock {
            this.checkpoint = checkpoint
        }
    }

    override suspend fun clearCheckpoint() {
        mutex.withLock {
            checkpoint = null
        }
    }

    override suspend fun readRun(): ScanRun = mutex.withLock { run }

    override suspend fun saveRun(run: ScanRun) {
        val snapshot = run.copy(byType = run.byType.toList())
        mutex.withLock {
            this.run = snapshot
            progress.value = ScanProgress.from(snapshot)
        }
    }

    override fun observeProgress(): Flow<ScanProgress> = progress.asStateFlow()

    /** Present row, or null when it was never stored or is marked removed. */
    suspend fun storedItem(key: MediaKey): MediaItem? = mutex.withLock {
        rows[key]?.takeUnless { stored -> stored.removed }?.item
    }

    private data class Stored(
        val item: MediaItem,
        val removed: Boolean,
    )
}
