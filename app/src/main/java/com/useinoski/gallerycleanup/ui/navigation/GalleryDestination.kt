package com.useinoski.gallerycleanup.ui.navigation

import com.useinoski.gallerycleanup.model.LibraryAccess

/**
 * The three places week 1 can be. A sealed interface is the same idea as a
 * sealed class, used when the types share no fields or constructor.
 * [Permission] and [Home] carry the grant they were decided from, so the
 * screen does not have to look it up again and risk a null.
 */
sealed interface GalleryDestination {
    data object Loading : GalleryDestination
    data object Onboarding : GalleryDestination
    data class Permission(val access: LibraryAccess) : GalleryDestination
    data class Home(val access: LibraryAccess) : GalleryDestination
}

/**
 * Onboarding wins until the privacy promise has been accepted. After that,
 * the home screen is shown only when at least one of photos or videos can be
 * read, including a partial selection. Audio alone does not unlock home:
 * the audio opt-in lives on the home screen, after the photo grant.
 */
fun galleryDestination(showOnboarding: Boolean, access: LibraryAccess?): GalleryDestination {
    if (showOnboarding) return GalleryDestination.Onboarding
    if (access == null) return GalleryDestination.Loading
    return if (access.anyVisual) {
        GalleryDestination.Home(access)
    } else {
        GalleryDestination.Permission(access)
    }
}
