package com.useinoski.gallerycleanup.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.useinoski.gallerycleanup.R
import com.useinoski.gallerycleanup.data.CategoryCounts
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.media.AudioScanRequest
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.media.audioScanRequest
import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.PermissionState
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanProgress
import com.useinoski.gallerycleanup.scan.scanAppearsRunning
import java.text.NumberFormat

/**
 * Counts for whatever the user has allowed. Every event is a parameter, so a
 * test can render a partial grant or a blocked audio grant without a device
 * and without MediaStore. The ViewModel is not referenced from here.
 */
@Composable
fun HomeScreen(
    access: LibraryAccess,
    counts: MediaCounts,
    audioScanningEnabled: Boolean,
    loading: Boolean,
    failed: Boolean,
    sdkInt: Int,
    onSelectMore: () -> Unit,
    onAllowAll: () -> Unit,
    onAllowImages: () -> Unit,
    onAllowVideos: () -> Unit,
    onTurnOnAudio: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    scan: ScanProgress,
    workActive: Boolean = false,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    flaggedCounts: CategoryCounts = emptyCategoryCounts(),
    onOpenReview: (ScanCategory) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (loading) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (loading && counts.images == null && counts.videos == null && counts.music == null) {
            Text(
                text = stringResource(R.string.counting),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (failed) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.read_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.try_again))
            }
        }
        Spacer(Modifier.height(20.dp))
        ScanCard(
            access = access,
            audioScanningEnabled = audioScanningEnabled,
            scan = scan,
            workActive = workActive,
            flaggedCounts = flaggedCounts,
            onStartScan = onStartScan,
            onCancelScan = onCancelScan,
            onOpenReview = onOpenReview,
        )
        Spacer(Modifier.height(16.dp))
        CountCards(
            access = access,
            counts = counts,
            onAllowImages = onAllowImages,
            onAllowVideos = onAllowVideos,
            onOpenSettings = onOpenSettings,
        )
        if (access.visualIsPartial) {
            Spacer(Modifier.height(16.dp))
            PartialBanner(onSelectMore = onSelectMore, onAllowAll = onAllowAll)
        }
        Spacer(Modifier.height(16.dp))
        AudioCard(
            access = access,
            counts = counts,
            audioScanningEnabled = audioScanningEnabled,
            sdkInt = sdkInt,
            onTurnOnAudio = onTurnOnAudio,
            onOpenSettings = onOpenSettings,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.home_footer),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanCard(
    access: LibraryAccess,
    audioScanningEnabled: Boolean,
    scan: ScanProgress,
    workActive: Boolean,
    flaggedCounts: CategoryCounts,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenReview: (ScanCategory) -> Unit,
) {
    val canScan = access.images.canRead || access.videos.canRead || (access.audio.canRead && audioScanningEnabled)
    val running = scanAppearsRunning(scan.phase, workActive)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.scan_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(scanMessage(if (running) ScanPhase.Running else scan.phase)),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (running) {
                Spacer(Modifier.height(12.dp))
                if (scan.total > 0) {
                    val fraction = (scan.scanned.toFloat() / scan.total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.scan_progress, scan.scanned, scan.total),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                scan.byType.forEach { progress ->
                    Text(
                        text = stringResource(
                            R.string.scan_type_progress,
                            stringResource(typeLabel(progress.type)),
                            progress.scanned,
                            progress.total,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (scan.phase != ScanPhase.Idle || flaggedCounts.flagged > 0) {
                FlaggedCounts(flaggedCounts, onOpenReview)
            }
            Spacer(Modifier.height(12.dp))
            if (running) {
                OutlinedButton(onClick = onCancelScan, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_cancel))
                }
            } else {
                Button(onClick = onStartScan, enabled = canScan, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (scan.phase == ScanPhase.Completed) R.string.scan_again else R.string.scan_start,
                        ),
                    )
                }
                if (!canScan) {
                    Text(
                        text = stringResource(R.string.scan_need_access),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

private val flaggedOrder: List<ScanCategory> = listOf(
    ScanCategory.Sensitive,
    ScanCategory.Immodest,
    ScanCategory.AlcoholGambling,
    ScanCategory.People,
    ScanCategory.Screenshot,
    ScanCategory.Video,
    ScanCategory.Audio,
)

@Composable
private fun FlaggedCounts(
    counts: CategoryCounts,
    onOpenReview: (ScanCategory) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.flagged_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    flaggedOrder.forEach { category ->
        val count = counts[category]
        Text(
            text = stringResource(
                R.string.flagged_count,
                stringResource(categoryLabel(category)),
                count,
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (count > 0) {
            TextButton(onClick = { onOpenReview(category) }) {
                Text(stringResource(R.string.review_open, stringResource(categoryLabel(category))))
            }
        }
    }
}

private fun categoryLabel(category: ScanCategory): Int = when (category) {
    ScanCategory.Sensitive -> R.string.category_nudity
    ScanCategory.Immodest -> R.string.category_suggestive
    ScanCategory.AlcoholGambling -> R.string.category_alcohol_gambling
    ScanCategory.People -> R.string.category_people
    ScanCategory.Screenshot -> R.string.category_screenshots
    ScanCategory.Video -> R.string.label_videos
    ScanCategory.Audio -> R.string.label_audio
    ScanCategory.Unflagged -> error("Unflagged is not a review count")
}

private fun scanMessage(phase: ScanPhase): Int = when (phase) {
    ScanPhase.Idle -> R.string.scan_idle
    ScanPhase.Running -> R.string.scan_running
    ScanPhase.Completed -> R.string.scan_done
    ScanPhase.Cancelled -> R.string.scan_cancelled
    ScanPhase.AccessRevoked -> R.string.scan_revoked
    ScanPhase.Failed -> R.string.scan_failed
}

private fun typeLabel(type: MediaType): Int = when (type) {
    MediaType.Image -> R.string.label_photos
    MediaType.Video -> R.string.label_videos
    MediaType.Audio -> R.string.label_audio
}

@Composable
private fun CountCards(
    access: LibraryAccess,
    counts: MediaCounts,
    onAllowImages: () -> Unit,
    onAllowVideos: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints {
        if (maxWidth >= 640.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.label_photos),
                    state = access.images,
                    count = counts.images,
                    allowLabel = stringResource(R.string.allow_photos),
                    onAllow = onAllowImages,
                    onOpenSettings = onOpenSettings,
                )
                CountCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.label_videos),
                    state = access.videos,
                    count = counts.videos,
                    allowLabel = stringResource(R.string.allow_videos),
                    onAllow = onAllowVideos,
                    onOpenSettings = onOpenSettings,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CountCard(
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.label_photos),
                    state = access.images,
                    count = counts.images,
                    allowLabel = stringResource(R.string.allow_photos),
                    onAllow = onAllowImages,
                    onOpenSettings = onOpenSettings,
                )
                CountCard(
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.label_videos),
                    state = access.videos,
                    count = counts.videos,
                    allowLabel = stringResource(R.string.allow_videos),
                    onAllow = onAllowVideos,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun CountCard(
    label: String,
    state: PermissionState,
    count: Int?,
    allowLabel: String,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (state.canRead && count != null) {
                Text(text = NumberFormat.getIntegerInstance().format(count), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        count == 0 && state is PermissionState.Partial -> stringResource(R.string.empty_partial)
                        count == 0 -> stringResource(R.string.empty_full)
                        state is PermissionState.Partial -> stringResource(R.string.caption_partial)
                        else -> stringResource(R.string.caption_full)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state is PermissionState.PermanentlyDenied) {
                Text(text = stringResource(R.string.caption_blocked), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
            } else {
                Text(text = stringResource(R.string.caption_denied), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onAllow) { Text(allowLabel) }
            }
        }
    }
}

@Composable
private fun PartialBanner(onSelectMore: () -> Unit, onAllowAll: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.partial_banner),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSelectMore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.select_more))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAllowAll, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.allow_all))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.allow_all_help),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun AudioCard(
    access: LibraryAccess,
    counts: MediaCounts,
    audioScanningEnabled: Boolean,
    sdkInt: Int,
    onTurnOnAudio: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.label_audio),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val music = counts.music
            val other = counts.otherAudio
            if (audioScanningEnabled && access.audio.canRead && music != null && other != null) {
                Text(
                    text = stringResource(R.string.audio_split, music, other),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.caption_full),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (access.audio is PermissionState.PermanentlyDenied) {
                Text(text = stringResource(R.string.audio_blocked), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
            } else {
                Text(
                    text = stringResource(R.string.audio_help),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when (audioScanRequest(sdkInt, access)) {
                    AudioScanRequest.NeedPhotoAccessFirst -> {
                        Text(
                            text = stringResource(R.string.audio_need_photos),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    else -> {
                        Button(onClick = onTurnOnAudio, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.audio_prompt))
                        }
                    }
                }
            }
        }
    }
}
