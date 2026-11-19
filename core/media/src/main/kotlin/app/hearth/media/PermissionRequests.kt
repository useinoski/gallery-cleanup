package app.hearth.media

import app.hearth.model.LibraryAccess
import app.hearth.model.PermissionState

/**
 * What the user just asked to do. The lists below are the only permissions
 * the app is allowed to put in a system dialog for that action.
 */
enum class AccessRequest {
    PhotosAndVideos,
    SelectMore,
    Images,
    Videos,
    Audio,
}

/**
 * What tapping "Turn on audio scanning" should do, given the current grant.
 * The preference flag (whether they already opted in) is not part of this.
 * The screen hides the button once scanning is on and the grant can be read.
 */
sealed interface AudioScanRequest {
    data class AskPermission(val permissions: List<String>) : AudioScanRequest
    data object AlreadyAllowed : AudioScanRequest
    data object NeedPhotoAccessFirst : AudioScanRequest
    data object OpenSettings : AudioScanRequest
}

/**
 * Permissions to pass to `RequestMultiplePermissions` for [request].
 * An empty list means there is nothing to ask the system. Callers must not
 * invent a dialog in that case.
 *
 * Audio is never included in the photo requests. On Android 13 and higher it
 * is its own permission. On Android 11 and 12 the storage permission covers
 * audio too, but this function still will not return it for [AccessRequest.Audio]:
 * the photo button is what asks for storage, and the audio button only opts in.
 */
fun permissionsToRequest(
    sdkInt: Int,
    access: LibraryAccess,
    request: AccessRequest,
): List<String> {
    if (sdkInt < ApiLevel.TIRAMISU) {
        return when (request) {
            AccessRequest.Audio -> emptyList()
            else -> {
                if (access.images is PermissionState.Full) {
                    emptyList()
                } else {
                    listOf(MediaPermissions.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }
    return when (request) {
        AccessRequest.PhotosAndVideos -> buildList {
            add(MediaPermissions.READ_MEDIA_IMAGES)
            add(MediaPermissions.READ_MEDIA_VIDEO)
            if (sdkInt >= ApiLevel.UPSIDE_DOWN_CAKE) {
                add(MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }
        AccessRequest.SelectMore -> reselection(sdkInt, access)
        AccessRequest.Images -> oneVisual(sdkInt, MediaPermissions.READ_MEDIA_IMAGES, access.images)
        AccessRequest.Videos -> oneVisual(sdkInt, MediaPermissions.READ_MEDIA_VIDEO, access.videos)
        AccessRequest.Audio -> listOf(MediaPermissions.READ_MEDIA_AUDIO)
    }
}

fun audioScanRequest(sdkInt: Int, access: LibraryAccess): AudioScanRequest {
    return when (access.audio) {
        PermissionState.Full, PermissionState.Partial -> AudioScanRequest.AlreadyAllowed
        PermissionState.PermanentlyDenied -> AudioScanRequest.OpenSettings
        PermissionState.Denied -> {
            if (sdkInt >= ApiLevel.TIRAMISU) {
                AudioScanRequest.AskPermission(listOf(MediaPermissions.READ_MEDIA_AUDIO))
            } else {
                AudioScanRequest.NeedPhotoAccessFirst
            }
        }
    }
}

private fun reselection(sdkInt: Int, access: LibraryAccess): List<String> = buildList {
    val imagesNeedMore = access.images !is PermissionState.Full
    val videosNeedMore = access.videos !is PermissionState.Full
    if (imagesNeedMore) add(MediaPermissions.READ_MEDIA_IMAGES)
    if (videosNeedMore) add(MediaPermissions.READ_MEDIA_VIDEO)
    if (sdkInt >= ApiLevel.UPSIDE_DOWN_CAKE && (imagesNeedMore || videosNeedMore)) {
        add(MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED)
    }
}

private fun oneVisual(sdkInt: Int, permission: String, state: PermissionState): List<String> {
    if (state is PermissionState.Full) return emptyList()
    return buildList {
        add(permission)
        if (sdkInt >= ApiLevel.UPSIDE_DOWN_CAKE) {
            add(MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }
}
