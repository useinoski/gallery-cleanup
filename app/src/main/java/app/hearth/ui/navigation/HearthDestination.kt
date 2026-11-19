package app.hearth.ui.navigation

import app.hearth.model.LibraryAccess

/**
 * The three places week 1 can be. A sealed interface is the same idea as a
 * sealed class, used when the types share no fields or constructor.
 * [Permission] and [Home] carry the grant they were decided from, so the
 * screen does not have to look it up again and risk a null.
 */
sealed interface HearthDestination {
    data object Loading : HearthDestination
    data object Onboarding : HearthDestination
    data class Permission(val access: LibraryAccess) : HearthDestination
    data class Home(val access: LibraryAccess) : HearthDestination
}

/**
 * Onboarding wins until the privacy promise has been accepted. After that,
 * the home screen is shown only when at least one of photos or videos can be
 * read, including a partial selection. Audio alone does not unlock home:
 * the audio opt-in lives on the home screen, after the photo grant.
 */
fun hearthDestination(showOnboarding: Boolean, access: LibraryAccess?): HearthDestination {
    if (showOnboarding) return HearthDestination.Onboarding
    if (access == null) return HearthDestination.Loading
    return if (access.anyVisual) {
        HearthDestination.Home(access)
    } else {
        HearthDestination.Permission(access)
    }
}
