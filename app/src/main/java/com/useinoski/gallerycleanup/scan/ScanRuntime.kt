package com.useinoski.gallerycleanup.scan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.useinoski.gallerycleanup.media.LibraryScanner
import com.useinoski.gallerycleanup.media.MediaPermissions
import com.useinoski.gallerycleanup.media.MediaStoreMediaRepository
import com.useinoski.gallerycleanup.media.MediaStoreVolumeStamps
import com.useinoski.gallerycleanup.media.ReadableLibrary
import com.useinoski.gallerycleanup.media.ScanBatchObserver
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.ScanStore
import com.useinoski.gallerycleanup.prefs.GALLERY_PREFS
import com.useinoski.gallerycleanup.prefs.GalleryPreferences

/**
 * Process-wide binding for the scan.
 *
 * [store] is the in-memory stand-in until `:core:data` supplies a Room
 * [ScanStore]. The worker and the home screen must share one instance, or
 * progress and the checkpoint would be written somewhere the screen does not
 * read. Replace this one function when Room lands. Do not add a second store
 * next to it.
 *
 * The store does not survive process death. Resume across a kill is what the
 * Room implementation is for. Inside one process, a cancelled worker and the
 * next start share the checkpoint.
 */
internal object ScanRuntime {
    private val gate = Any()
    private var storeRef: ScanStore? = null

    fun store(): ScanStore = synchronized(gate) {
        storeRef ?: InMemoryScanStore().also { created -> storeRef = created }
    }

    fun scanner(context: Context): LibraryScanner {
        val appContext = context.applicationContext
        val preferences = GalleryPreferences(
            appContext.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE),
        )
        val access = ReadableLibrary(
            sdkInt = Build.VERSION.SDK_INT,
            granted = {
                MediaPermissions.all.filter { permission ->
                    ContextCompat.checkSelfPermission(appContext, permission) ==
                        PackageManager.PERMISSION_GRANTED
                }.toSet()
            },
            audioEnabled = { preferences.isEnabled() },
        )
        return LibraryScanner(
            repository = MediaStoreMediaRepository(appContext.contentResolver),
            store = store(),
            readable = { access.current() },
            stamps = MediaStoreVolumeStamps(appContext),
            observer = ScanBatchObserver.NONE,
        )
    }
}
