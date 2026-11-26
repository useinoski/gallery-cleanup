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
 * The image classifier is one per scan, not one per process. It stays loaded
 * across the batches of that scan. [LibraryScan] closes it when the scan
 * ends, fails, or is cancelled, which is what releases the detector threads.
 * The next scan builds another one. It is created on the first scan, not
 * when the home screen opens, so a launch that never scans does not load it.
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
            return LibraryScan(
                scanner = scanner,
                detectors = classifier,
                afterPass = rescan::afterPass,
            )
        } catch (failure: Exception) {
            classifier.close()
            throw failure
        }
    }
}
