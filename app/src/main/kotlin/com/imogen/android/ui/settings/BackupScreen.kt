package com.imogen.android.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imogen.android.backup.BackupProgress
import com.imogen.android.backup.MediaAccess
import com.imogen.android.ui.RootViewModel

/**
 * What gets copied, and where to.
 *
 * The account list is the interesting part: choosing three means three copies, which is
 * the answer to "my family server and my own", and the screen says so rather than leaving
 * somebody to guess whether the toggles are exclusive.
 */
@Composable
fun BackupScreen(
    model: RootViewModel,
    preferences: com.imogen.android.backup.BackupPreferences,
    progress: BackupProgress?,
    mediaAccess: MediaAccess,
    onPreferencesChanged: (com.imogen.android.backup.BackupPreferences) -> Unit,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onRunNow: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val book by model.book.collectAsStateWithLifecycle()
    val accounts = book?.accounts.orEmpty()

    // Android 13 wants asking before it will show a progress notification, and the upload
    // runs as a foreground service that needs one. Asked here, where the switch is, rather
    // than at first launch where it would have no context.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SwitchRow(
            title = "Back up my photos",
            subtitle = "New photographs and videos are copied to the accounts below.",
            checked = preferences.enabled,
            onChange = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // The camera roll itself, which is the permission backup cannot work
                // without — asked for here rather than at first launch, where it would
                // have no context to justify itself with.
                if (enabled) onRequestAccess()
                onPreferencesChanged(preferences.copy(enabled = enabled))
            },
        )

        if (preferences.enabled && mediaAccess != MediaAccess.Granted) {
            MediaAccessRow(
                access = mediaAccess,
                onRequestAccess = onRequestAccess,
                onOpenSettings = onOpenSettings,
            )
        }

        progress?.let {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                LinearProgressIndicator(
                    progress = { it.completed.toFloat() / it.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${it.completed} of ${it.total}" + (it.filename?.let { name -> " · $name" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        HorizontalDivider()
        SectionHeading("Copy to")
        Text(
            "Every account you choose gets its own copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        accounts.forEach { account ->
            SwitchRow(
                title = account.serverLabel,
                subtitle = "${account.name} · ${account.email}",
                checked = account.backupEnabled,
                enabled = preferences.enabled,
                onChange = { model.setBackupEnabled(account.id, it) },
            )
        }

        HorizontalDivider()
        SectionHeading("When")

        SwitchRow(
            title = "Wi-Fi only",
            subtitle = "Leave this on unless your data plan is generous.",
            checked = preferences.unmeteredOnly,
            enabled = preferences.enabled,
            onChange = { onPreferencesChanged(preferences.copy(unmeteredOnly = it)) },
        )
        SwitchRow(
            title = "While charging only",
            subtitle = null,
            checked = preferences.whileChargingOnly,
            enabled = preferences.enabled,
            onChange = { onPreferencesChanged(preferences.copy(whileChargingOnly = it)) },
        )

        HorizontalDivider()
        SectionHeading("What")

        SwitchRow(
            title = "Include videos",
            subtitle = "Videos are large. The app resumes an interrupted one rather than restarting it.",
            checked = preferences.includeVideos,
            enabled = preferences.enabled,
            onChange = { onPreferencesChanged(preferences.copy(includeVideos = it)) },
        )
        SwitchRow(
            title = "Camera only",
            subtitle = "Photographs this device took, rather than every image on it.",
            checked = preferences.cameraOnly,
            enabled = preferences.enabled,
            onChange = { onPreferencesChanged(preferences.copy(cameraOnly = it)) },
        )

        Button(
            onClick = onRunNow,
            enabled = preferences.enabled && accounts.any { it.backupEnabled },
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) { Text("Back up now") }
    }
}

/**
 * Said out loud, because the alternative is silence: without the permission MediaStore
 * hands back an empty cursor rather than an error, so a backup that cannot read anything
 * is indistinguishable from a backup with nothing left to do.
 */
@Composable
private fun MediaAccessRow(
    access: MediaAccess,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val denied = access == MediaAccess.Denied
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = if (denied) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column {
            Text(
                if (denied) {
                    "imogen cannot read your photographs"
                } else {
                    "imogen can only read some of your photographs"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (denied) {
                    "Nothing will be backed up until you allow access to your photographs."
                } else {
                    "Only what you have allowed will be backed up."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row {
                TextButton(onRequestAccess) {
                    Text(if (denied) "Allow access" else "Choose more")
                }
                // Both routes, because there is no reliable way to ask Android whether it
                // will still show the dialog: once it has stopped, the request returns a
                // refusal instantly and the button looks broken. Settings always works.
                if (denied) {
                    TextButton(onOpenSettings) { Text("Open settings") }
                }
            }
        }
    }
}
