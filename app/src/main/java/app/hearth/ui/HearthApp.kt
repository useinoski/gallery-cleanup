package app.hearth.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hearth.R
import app.hearth.media.AccessRequest
import app.hearth.media.AudioScanRequest
import app.hearth.media.audioScanRequest
import app.hearth.media.permissionsToRequest
import app.hearth.model.PermissionState
import app.hearth.permission.DevicePermissionReader
import app.hearth.permission.openAppSettings
import app.hearth.prefs.HearthPreferences
import app.hearth.prefs.HEARTH_PREFS
import app.hearth.ui.home.HomeScreen
import app.hearth.ui.home.HomeViewModel
import app.hearth.ui.navigation.HearthDestination
import app.hearth.ui.navigation.hearthDestination
import app.hearth.ui.onboarding.OnboardingScreen
import app.hearth.ui.permission.PermissionGate

/**
 * Switches onboarding, the permission explanation, and the counts.
 *
 * `rememberLauncherForActivityResult` is the Compose form of
 * `registerForActivityResult`. It has to run on every composition, not inside
 * the click handler. Registering a launcher from a click is the same bug as
 * calling `registerForActivityResult` after the Activity is started: the
 * registration is lost or the app crashes.
 *
 * The launcher is created here and the screens only receive lambdas. The
 * screens do not know how a permission is requested. That split is what makes
 * them testable without a permission dialog.
 */
@Composable
fun HearthApp(
    viewModel: HomeViewModel,
    reader: DevicePermissionReader,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refresh(reader.snapshot())
    }

    LifecycleResumeEffect(reader) {
        viewModel.refresh(reader.snapshot())
        onPauseOrDispose { }
    }

    fun launch(permissions: List<String>) {
        if (permissions.isEmpty() || activity == null) return
        reader.markAsked(permissions)
        launcher.launch(permissions.toTypedArray())
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        val destination = hearthDestination(state.showOnboarding, state.access)
        val body: @Composable () -> Unit = {
            when (destination) {
                HearthDestination.Loading -> LoadingScreen()
                HearthDestination.Onboarding -> OnboardingScreen(
                    onContinue = {
                        viewModel.completeOnboarding()
                        viewModel.refresh(reader.snapshot())
                    },
                )
                is HearthDestination.Permission -> PermissionGate(
                    access = destination.access,
                    onAllowPhotos = {
                        launch(
                            permissionsToRequest(
                                Build.VERSION.SDK_INT,
                                destination.access,
                                AccessRequest.PhotosAndVideos,
                            ),
                        )
                    },
                    onOpenSettings = { activity?.openAppSettings() },
                )
                is HearthDestination.Home -> {
                    val access = destination.access
                    val sdk = Build.VERSION.SDK_INT
                    HomeScreen(
                        access = access,
                        counts = state.counts,
                        audioScanningEnabled = state.audioScanningEnabled,
                        loading = state.loading,
                        failed = state.failed,
                        sdkInt = sdk,
                        onSelectMore = {
                            launch(permissionsToRequest(sdk, access, AccessRequest.SelectMore))
                        },
                        onAllowAll = { activity?.openAppSettings() },
                        onAllowImages = {
                            if (access.images is PermissionState.PermanentlyDenied) {
                                activity?.openAppSettings()
                            } else {
                                launch(permissionsToRequest(sdk, access, AccessRequest.Images))
                            }
                        },
                        onAllowVideos = {
                            if (access.videos is PermissionState.PermanentlyDenied) {
                                activity?.openAppSettings()
                            } else {
                                launch(permissionsToRequest(sdk, access, AccessRequest.Videos))
                            }
                        },
                        onTurnOnAudio = {
                            when (val action = audioScanRequest(sdk, access)) {
                                is AudioScanRequest.AskPermission -> {
                                    viewModel.setAudioScanningEnabled(true)
                                    launch(action.permissions)
                                }
                                AudioScanRequest.AlreadyAllowed -> {
                                    viewModel.setAudioScanningEnabled(true)
                                    viewModel.refresh(reader.snapshot())
                                }
                                AudioScanRequest.OpenSettings -> activity?.openAppSettings()
                                AudioScanRequest.NeedPhotoAccessFirst -> Unit
                            }
                        },
                        onOpenSettings = { activity?.openAppSettings() },
                        onRetry = { viewModel.refresh(reader.snapshot()) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            body()
        }
    }
}

@Composable
fun rememberPermissionReader(): DevicePermissionReader {
    val activity = LocalActivity.current
        ?: error("HearthApp must be hosted by an Activity")
    return remember(activity) {
        val preferences = HearthPreferences(
            activity.getSharedPreferences(HEARTH_PREFS, Context.MODE_PRIVATE),
        )
        DevicePermissionReader(activity, preferences)
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(text = stringResource(R.string.loading_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator()
    }
}
