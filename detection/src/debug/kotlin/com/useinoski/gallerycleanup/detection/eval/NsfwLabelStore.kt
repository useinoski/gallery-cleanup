package com.useinoski.gallerycleanup.detection.eval

import android.content.Context
import java.io.File

/**
 * Labels keyed by MediaStore id, in one file under [Context.noBackupFilesDir].
 *
 * That directory is private to this app. Android deletes it on uninstall.
 * It is also skipped by backup, on top of `allowBackup="false"`. Nothing here
 * writes to shared storage, MediaStore, or another app.
 *
 * The file is ids and label tokens only. A Java `try-with-resources` is not
 * required: [File.writeText] closes the writer, and the temp file is renamed
 * over the real one so a crash mid-write cannot leave a half line as the
 * only copy.
 */
internal class NsfwLabelStore(
    private val directory: File,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, DIRECTORY))

    private val file = File(directory, FILE_NAME)
    private val lock = Any()

    fun read(): Map<Long, NudityLabel> = synchronized(lock) { readUnlocked() }

    fun put(mediaId: Long, label: NudityLabel) {
        synchronized(lock) {
            val updated = readUnlocked().toMutableMap()
            updated[mediaId] = label
            writeUnlocked(updated)
        }
    }

    /** Where [put] writes. Tests use this to prove the file never leaves [directory]. */
    fun storageFile(): File = file

    private fun readUnlocked(): Map<Long, NudityLabel> {
        if (!file.isFile) return emptyMap()
        val parsed = LinkedHashMap<Long, NudityLabel>()
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == VERSION) continue
            val parts = trimmed.split(' ')
            if (parts.size != 2) continue
            val id = parts[0].toLongOrNull() ?: continue
            val label = labelOrNull(parts[1]) ?: continue
            parsed[id] = label
        }
        return parsed
    }

    private fun writeUnlocked(labels: Map<Long, NudityLabel>) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            error("label directory was not created")
        }
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeText(body(labels))
        if (!temporary.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                error("label file was not replaced")
            }
            if (!temporary.renameTo(file)) {
                error("label file was not replaced")
            }
        }
    }

    private companion object {
        const val DIRECTORY = "nsfw-labeler"
        const val FILE_NAME = "labels.txt"
        const val VERSION = "v1"
    }
}

private fun body(labels: Map<Long, NudityLabel>): String = buildString {
    append("v1\n")
    for ((id, label) in labels.toSortedMap()) {
        append(id)
        append(' ')
        append(label.name)
        append('\n')
    }
}

private fun labelOrNull(token: String): NudityLabel? = try {
    NudityLabel.valueOf(token)
} catch (ignored: IllegalArgumentException) {
    null
}
