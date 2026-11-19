package app.hearth.model

/**
 * How much of one media collection the user has granted.
 *
 * A sealed class is a closed set of types. The compiler rejects a `when` that
 * forgets one. In Java the usual substitute is an enum. A sealed class is used
 * instead because [Partial] is a different situation from [Full], and a later
 * week can add fields to one state without giving every state those fields.
 *
 * `data object` is a singleton with value equality, like an enum constant.
 * `PermissionState.Full == PermissionState.Full` is true.
 *
 * [Partial] applies only to photos and videos on Android 14 (API 34) and
 * higher, when the user chose "Select photos and videos". Audio is never partial.
 * [PermanentlyDenied] means the system will not show the dialog again; the
 * user has to change it in Settings.
 */
sealed class PermissionState {
    data object Full : PermissionState()
    data object Partial : PermissionState()
    data object Denied : PermissionState()
    data object PermanentlyDenied : PermissionState()

    /** True when MediaStore will return rows for this collection. */
    val canRead: Boolean
        get() = this is Full || this is Partial
}

/**
 * The three grants, read together. This is the value the UI branches on.
 * Do not store it. Read it again whenever the screen resumes, because the
 * user can change it in Settings while the app is in the background.
 */
data class LibraryAccess(
    val images: PermissionState,
    val videos: PermissionState,
    val audio: PermissionState,
) {
    val anyVisual: Boolean
        get() = images.canRead || videos.canRead

    /** At least one collection is partial, so the counts are not the whole library. */
    val visualIsPartial: Boolean
        get() = images is PermissionState.Partial || videos is PermissionState.Partial
}
