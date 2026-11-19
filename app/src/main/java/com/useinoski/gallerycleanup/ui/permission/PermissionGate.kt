package com.useinoski.gallerycleanup.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.useinoski.gallerycleanup.R
import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.PermissionState

/**
 * Explains photo and video access and waits for a tap.
 * The system dialog is launched by [onAllowPhotos], not by showing this screen.
 */
@Composable
fun PermissionGate(
    access: LibraryAccess,
    onAllowPhotos: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val canAsk = access.images is PermissionState.Denied || access.videos is PermissionState.Denied
    val blocked = access.images is PermissionState.PermanentlyDenied ||
        access.videos is PermissionState.PermanentlyDenied

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(
                if (blocked && !canAsk) R.string.permission_blocked else R.string.permission_body,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        if (canAsk) {
            Button(onClick = onAllowPhotos, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permission_allow))
            }
        }
        if (blocked) {
            if (canAsk) Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_settings))
            }
        }
    }
}
