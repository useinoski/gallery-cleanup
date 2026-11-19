package app.hearth.prefs

import android.content.SharedPreferences

internal const val HEARTH_PREFS = "hearth"

/**
 * On-device flags. None of these is a cached permission grant. The grant is
 * re-read from the system every time the screen resumes, because Android can
 * revoke it while the process is still alive.
 *
 * `commit()` is used instead of `apply()` so the following line can read the
 * new value. These writes are a few booleans; blocking the main thread for
 * that is cheaper than racing the read.
 */
class HearthPreferences(
    private val prefs: SharedPreferences,
) : OnboardingStore, AudioScanStore, AskedPermissionStore {

    override fun isComplete(): Boolean = prefs.getBoolean(KEY_ONBOARDING, false)

    override fun setComplete() {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).commit()
    }

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_AUDIO, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO, enabled).commit()
    }

    override fun wasAsked(permission: String): Boolean = asked().contains(permission)

    override fun markAsked(permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        // getStringSet can return the live set stored in the preferences object.
        // Mutating that set and writing it back is silently ignored. `asked()`
        // copies it, and `+` builds a new set.
        prefs.edit().putStringSet(KEY_ASKED, asked() + permissions).commit()
    }

    private fun asked(): Set<String> = prefs.getStringSet(KEY_ASKED, emptySet()).orEmpty().toSet()

    private companion object {
        const val KEY_ONBOARDING = "onboarding_complete"
        const val KEY_AUDIO = "audio_scanning_enabled"
        const val KEY_ASKED = "asked_permissions"
    }
}
