package com.useinoski.gallerycleanup.model

/**
 * One row in the on-device library. Week 2's scan pages these.
 *
 * [uri] is the string form of a MediaStore content Uri, such as
 * `content://media/external/images/media/12`. It is a String rather than
 * `android.net.Uri` so this module can stay plain Kotlin: no Android SDK,
 * and JVM tests can construct items without a device.
 *
 * [dateAdded] is seconds since the Unix epoch, which is what MediaStore stores
 * in DATE_ADDED. [size] is bytes.
 *
 * [path] is a relative path such as `DCIM/Camera/IMG_001.jpg`, built from
 * RELATIVE_PATH and DISPLAY_NAME. It is not an absolute filesystem path.
 * Scoped storage does not give those out, and reading them is the reason
 * people request all-files access, which Gallery Cleanup will not do.
 */
data class MediaItem(
    val id: Long,
    val uri: String,
    val type: MediaType,
    val dateAdded: Long,
    val size: Long,
    val path: String?,
    val mimeType: String?,
)
