package com.useinoski.gallerycleanup.media

/**
 * Turns a fresh permission snapshot into the collections the scan may walk.
 *
 * [granted] and [audioEnabled] are read on every call. A scan that cached
 * the first answer would keep going after the user revoked access, or would
 * start audio after they had turned the opt-in off.
 *
 * [wasAsked] is reported as true and rationale as false. Neither changes
 * [com.useinoski.gallerycleanup.model.PermissionState.canRead], which is the
 * only thing this class uses. The home screen still does the full check,
 * because it has to tell "never asked" from "permanently denied".
 */
class ReadableLibrary(
    private val sdkInt: Int,
    private val granted: () -> Set<String>,
    private val audioEnabled: () -> Boolean,
) {
    fun current(): ReadableMedia {
        val access = PermissionStatusResolver.resolve(
            sdkInt = sdkInt,
            granted = granted(),
            shouldShowRationale = { false },
            wasAsked = { true },
        )
        return ReadableMedia(
            images = access.images.canRead,
            videos = access.videos.canRead,
            audio = access.audio.canRead && audioEnabled(),
        )
    }
}
