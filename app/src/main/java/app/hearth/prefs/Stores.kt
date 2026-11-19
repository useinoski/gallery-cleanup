package app.hearth.prefs

/**
 * The privacy screen has been dismissed. This is not a permission grant.
 */
interface OnboardingStore {
    fun isComplete(): Boolean
    fun setComplete()
}

/** The user asked Hearth to count audio. Separate from the system grant. */
interface AudioScanStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

/**
 * Which system dialogs have already been shown.
 * Android uses the same "do not show rationale" signal for "never asked" and
 * "permanently denied". This flag is how those two are told apart.
 */
interface AskedPermissionStore {
    fun wasAsked(permission: String): Boolean
    fun markAsked(permissions: Collection<String>)
}
