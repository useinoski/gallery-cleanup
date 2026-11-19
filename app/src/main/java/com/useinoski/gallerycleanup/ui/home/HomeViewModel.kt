package com.useinoski.gallerycleanup.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.media.MediaRepository
import com.useinoski.gallerycleanup.media.ReadableMedia
import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.ScanProgress
import com.useinoski.gallerycleanup.model.ScanRun
import com.useinoski.gallerycleanup.prefs.AudioScanStore
import com.useinoski.gallerycleanup.prefs.OnboardingStore
import com.useinoski.gallerycleanup.scan.ScanControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val showOnboarding: Boolean,
    val access: LibraryAccess? = null,
    val audioScanningEnabled: Boolean = false,
    val counts: MediaCounts = MediaCounts(null, null, null, null),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val scan: ScanProgress = ScanProgress.from(ScanRun.IDLE),
)

/**
 * Owns the onboarding flag, the audio opt-in, and the latest counts.
 *
 * It does not read permissions itself. The screen passes a [LibraryAccess]
 * it just read from the Activity. That keeps the Activity out of a type that
 * survives rotation: storing an Activity in a ViewModel is a leak, the same
 * as storing it in a retained fragment.
 *
 * [state] is a StateFlow. Unlike a one-shot callback, it always has a current
 * value, so a new collector (a rotated screen) gets the latest counts immediately.
 */
class HomeViewModel(
    private val repository: MediaRepository,
    private val onboardingStore: OnboardingStore,
    private val audioScanStore: AudioScanStore,
    private val scans: ScanControls,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AppUiState(
            showOnboarding = !onboardingStore.isComplete(),
            audioScanningEnabled = audioScanStore.isEnabled(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            scans.progress.collect { progress ->
                _state.update { current -> current.copy(scan = progress) }
            }
        }
    }

    fun startScan() {
        scans.start()
    }

    fun cancelScan() {
        scans.cancel()
    }

    fun completeOnboarding() {
        onboardingStore.setComplete()
        _state.update { current -> current.copy(showOnboarding = false) }
    }

    fun setAudioScanningEnabled(enabled: Boolean) {
        audioScanStore.setEnabled(enabled)
        _state.update { current -> current.copy(audioScanningEnabled = enabled) }
    }

    fun refresh(access: LibraryAccess) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val audioEnabled = audioScanStore.isEnabled()
            val readable = ReadableMedia(
                images = access.images.canRead,
                videos = access.videos.canRead,
                audio = access.audio.canRead && audioEnabled,
            )
            _state.update { current ->
                current.copy(access = access, audioScanningEnabled = audioEnabled, loading = true, failed = false)
            }
            if (!readable.images && !readable.videos && !readable.audio) {
                _state.update { current ->
                    current.copy(
                        counts = MediaCounts(null, null, null, null),
                        loading = false,
                    )
                }
                return@launch
            }
            try {
                val counts = repository.count(readable)
                _state.update { current ->
                    current.copy(counts = counts, loading = false, failed = false)
                }
            } catch (cancelled: CancellationException) {
                // CancellationException extends Exception. Swallowing it here
                // would break coroutine cancellation and look like a hung load.
                throw cancelled
            } catch (_: Exception) {
                _state.update { current ->
                    current.copy(loading = false, failed = true)
                }
            }
        }
    }
}
