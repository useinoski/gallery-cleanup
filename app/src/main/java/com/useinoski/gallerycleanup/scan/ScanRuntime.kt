package com.useinoski.gallerycleanup.scan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.useinoski.gallerycleanup.data.RoomScanResultStore
import com.useinoski.gallerycleanup.data.roomScanResultStore
import com.useinoski.gallerycleanup.media.LibraryScanner
import com.useinoski.gallerycleanup.media.MediaPermissions
import com.useinoski.gallerycleanup.media.MediaStoreMediaRepository
import com.useinoski.gallerycleanup.media.MediaStoreVolumeStamps
import com.useinoski.gallerycleanup.media.ReadableLibrary
import com.useinoski.gallerycleanup.prefs.GALLERY_PREFS
import com.useinoski.gallerycleanup.prefs.GalleryPreferences

/**
 * Process-wide binding for the scan.
 *
 * [store] is the Room scan store. The worker and the home screen must share
 * one instance, or progress and the checkpoint would be written somewhere the
 * screen does not read. Do not add a second store next to it. The database
 * survives process death, which is what a later start resumes from.
 *
 * The image classifier is not cached. [open] returns a [LibraryScan] whose
 * [ScanPassFactory] builds a new [OnDeviceImageClassifier] at the start of
 * every pass, and [LibraryScan] closes it when that pass ends, fails, or is
 * cancelled. Calling [store] or [open] does not enqueue work. A rescan of
 * rows the upgrade marked failed runs only inside that pass, after a scan
 * the user started or WorkManager resumed. It is not a second job, and it
 * does not start on its own when the database opens.
 * A closed Marqo detector throws if it is used again, so the next pass must
 * not keep the previous instance. The model stays loaded across the batches
 * of the one pass. It is created when the pass starts, not when the home
 * screen opens, so a launch that never scans does not load it.
 */
internal object ScanRuntime {
    private val gate = Any()
    private var storeRef: RoomScanResultStore? = null

    fun store(context: Context): RoomScanResultStore = synchronized(gate) {
        storeRef ?: roomScanResultStore(context.applicationContext).also { created ->
            storeRef = created
        }
    }

    fun open(context: Context): LibraryScan {
        val appContext = context.applicationContext
        return LibraryScan { openPass(appContext) }
    }

    private fun openPass(appContext: Context): LibraryScan.Pass {
        val results = store(appContext)
        val records = RoomAnalysisRecords(results)
        val classifier = OnDeviceImageClassifier(appContext)
        try {
            val observer = DetectionBatchObserver(
                results = records,
                classifyImage = classifier::classify,
            )
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
            val scanner = LibraryScanner(
                repository = MediaStoreMediaRepository(appContext.contentResolver),
                store = results,
                readable = { access.current() },
                stamps = MediaStoreVolumeStamps(appContext),
                observer = observer,
            )
            val rescan = PendingRescan(
                records = records,
                runs = results,
                currentModelVersions = classifier.currentModelVersions(),
                resolvePresent = results::presentItems,
                observer = observer,
            )
            return LibraryScan.Pass(
                scanner = scanner,
                detectors = classifier,
                afterPass = rescan::afterPass,
                runs = results,
            )
        } catch (failure: Exception) {
            classifier.close()
            throw failure
        }
    }
}
