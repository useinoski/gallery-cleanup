package app.hearth.permission

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.hearth.media.MediaPermissions
import app.hearth.media.PermissionStatusResolver
import app.hearth.model.LibraryAccess
import app.hearth.prefs.AskedPermissionStore

/**
 * Reads the device and delegates the decision to [PermissionStatusResolver].
 * Holds an Activity only because `shouldShowRequestPermissionRationale` needs one.
 * The ViewModel must not keep this object: a ViewModel outlives the Activity
 * across rotation, and a stored Activity leaks.
 */
class DevicePermissionReader(
    private val activity: Activity,
    private val asks: AskedPermissionStore,
) {
    fun snapshot(): LibraryAccess {
        val granted = MediaPermissions.all.filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }.toSet()
        return PermissionStatusResolver.resolve(
            sdkInt = Build.VERSION.SDK_INT,
            granted = granted,
            shouldShowRationale = { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            },
            wasAsked = asks::wasAsked,
        )
    }

    fun markAsked(permissions: Collection<String>) {
        asks.markAsked(permissions)
    }
}
