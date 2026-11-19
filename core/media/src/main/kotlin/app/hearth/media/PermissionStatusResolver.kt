package app.hearth.media

import app.hearth.model.LibraryAccess
import app.hearth.model.PermissionState

/**
 * Permission name strings. They match `android.Manifest.permission` but are
 * copied here so the resolver does not touch the Android framework. The
 * framework jar used by JVM unit tests throws if you call real Android methods;
 * these are just strings, so the tests stay plain JUnit.
 */
object MediaPermissions {
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    val all: List<String> = listOf(
        READ_EXTERNAL_STORAGE,
        READ_MEDIA_IMAGES,
        READ_MEDIA_VIDEO,
        READ_MEDIA_AUDIO,
        READ_MEDIA_VISUAL_USER_SELECTED,
    )
}

object ApiLevel {
    /** Android 13. Photos, video, and audio become separate permissions. */
    const val TIRAMISU = 33

    /** Android 14. The user can grant a selected set of photos and videos. */
    const val UPSIDE_DOWN_CAKE = 34
}

/**
 * Turns the grants the system reports into [LibraryAccess].
 *
 * The two function arguments are the Kotlin form of a Java `Predicate<String>`:
 * one method, no class of its own. Callers pass `asks::wasAsked`.
 *
 * "Never asked" and "permanently denied" both make
 * `shouldShowRequestPermissionRationale` return false. [wasAsked] separates
 * them. It records that a dialog was shown. It does not record the grant.
 * The grant is the [granted] set, re-read from the system on every call.
 */
object PermissionStatusResolver {
    fun resolve(
        sdkInt: Int,
        granted: Set<String>,
        shouldShowRationale: (String) -> Boolean,
        wasAsked: (String) -> Boolean,
    ): LibraryAccess {
        if (sdkInt < ApiLevel.TIRAMISU) {
            val storage = level(
                permission = MediaPermissions.READ_EXTERNAL_STORAGE,
                granted = granted,
                shouldShowRationale = shouldShowRationale,
                wasAsked = wasAsked,
            )
            return LibraryAccess(images = storage, videos = storage, audio = storage)
        }
        return LibraryAccess(
            images = visual(
                sdkInt = sdkInt,
                fullPermission = MediaPermissions.READ_MEDIA_IMAGES,
                granted = granted,
                shouldShowRationale = shouldShowRationale,
                wasAsked = wasAsked,
            ),
            videos = visual(
                sdkInt = sdkInt,
                fullPermission = MediaPermissions.READ_MEDIA_VIDEO,
                granted = granted,
                shouldShowRationale = shouldShowRationale,
                wasAsked = wasAsked,
            ),
            audio = level(
                permission = MediaPermissions.READ_MEDIA_AUDIO,
                granted = granted,
                shouldShowRationale = shouldShowRationale,
                wasAsked = wasAsked,
            ),
        )
    }

    private fun visual(
        sdkInt: Int,
        fullPermission: String,
        granted: Set<String>,
        shouldShowRationale: (String) -> Boolean,
        wasAsked: (String) -> Boolean,
    ): PermissionState {
        if (fullPermission in granted) return PermissionState.Full
        // Checked before "denied". A partial grant leaves the full permission
        // ungranted, and rationale is often false, which would otherwise look
        // like a permanent denial.
        if (
            sdkInt >= ApiLevel.UPSIDE_DOWN_CAKE &&
            MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED in granted
        ) {
            return PermissionState.Partial
        }
        return level(fullPermission, granted, shouldShowRationale, wasAsked)
    }

    private fun level(
        permission: String,
        granted: Set<String>,
        shouldShowRationale: (String) -> Boolean,
        wasAsked: (String) -> Boolean,
    ): PermissionState {
        if (permission in granted) return PermissionState.Full
        if (!wasAsked(permission)) return PermissionState.Denied
        if (shouldShowRationale(permission)) return PermissionState.Denied
        return PermissionState.PermanentlyDenied
    }
}
