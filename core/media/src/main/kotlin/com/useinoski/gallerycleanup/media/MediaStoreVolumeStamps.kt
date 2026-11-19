package com.useinoski.gallerycleanup.media

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads `MediaStore.getVersion` and `getGeneration` off the main thread.
 *
 * `VOLUME_EXTERNAL` is the right name for a query that spans every volume,
 * and the wrong name for `getGeneration`: the platform wants a name from
 * [MediaStore.getExternalVolumeNames]. Queries stay on `VOLUME_EXTERNAL`.
 * The stamp is per real volume, then folded into one [VolumeStamp].
 */
class MediaStoreVolumeStamps(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VolumeStamps {
    override suspend fun read(): VolumeStamp = withContext(ioDispatcher) {
        val names = MediaStore.getExternalVolumeNames(context).sorted()
        when (names.size) {
            0 -> VolumeStamp(version = "none", generation = 0, generationUsable = true)
            1 -> {
                val name = names.single()
                VolumeStamp(
                    version = MediaStore.getVersion(context, name),
                    generation = MediaStore.getGeneration(context, name),
                    generationUsable = true,
                )
            }
            else -> {
                val version = names.joinToString(separator = "|") { name ->
                    val volumeVersion = MediaStore.getVersion(context, name)
                    val volumeGeneration = MediaStore.getGeneration(context, name)
                    "$name#$volumeVersion#$volumeGeneration"
                }
                VolumeStamp(version = version, generation = 0, generationUsable = false)
            }
        }
    }
}
