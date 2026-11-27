package com.useinoski.gallerycleanup.media.category

/**
 * Folder pieces of a MediaStore relative path.
 *
 * `DCIM/Screenshots/` and `DCIM\Screenshots` are both `DCIM`, `Screenshots`.
 * A blank or missing path has no segments. The file name is not in here when
 * the caller kept it on [com.useinoski.gallerycleanup.model.MediaItemMetadata.displayName].
 */
internal fun String?.pathSegments(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return split('/', '\\')
        .map { segment -> segment.trim() }
        .filter { segment -> segment.isNotEmpty() }
}

/**
 * True when [sequence] sits in this path as whole segments, in order.
 *
 * A longer path still matches, so `Sent` and `Private` under a chat folder
 * count. Matching is case-insensitive. An empty sequence matches nothing.
 */
internal fun List<String>.containsSequence(sequence: List<String>): Boolean {
    if (sequence.isEmpty() || size < sequence.size) return false
    val lastStart = size - sequence.size
    for (start in 0..lastStart) {
        val matches = sequence.indices.all { index ->
            this[start + index].equals(sequence[index], ignoreCase = true)
        }
        if (matches) return true
    }
    return false
}
