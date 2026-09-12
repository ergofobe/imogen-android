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
import android.text.format.DateUtils
import com.imogen.android.backup.AccountProgress
import com.imogen.android.backup.BackupStatus
import com.imogen.android.backup.FailureReason
import com.imogen.android.backup.FailureSummary
import com.imogen.android.backup.MediaAccess
import com.imogen.android.backup.PassState
import com.imogen.android.backup.WaitingReason
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
    status: BackupStatus,
    mediaAccess: MediaAccess,
    failures: FailureSummary,
    onPreferencesChanged: (com.imogen.android.backup.BackupPreferences) -> Unit,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFailures: () -> Unit,
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

        PassRow(status.pass)

        if (failures.total > 0) {
            FailuresRow(failures, onOpenFailures)
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
            // Under the account it belongs to, rather than one bar over all of them:
            // choosing three servers means three copies, and three separate answers to
            // how far along it is.
            if (account.backupEnabled) {
                AccountStatusRow(status.accounts.firstOrNull { it.backupKey == account.backupKey })
            }
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

        val busy = status.pass is PassState.Running || status.pass is PassState.Scanning
        Button(
            onClick = onRunNow,
            // Not while a pass is going: the one-shot is enqueued with KEEP, so pressing
            // it would be a no-op, and a button that does nothing is what started all this.
            enabled = preferences.enabled && accounts.any { it.backupEnabled } && !busy,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) { Text(if (busy) "Backing up…" else "Back up now") }
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

/**
 * What the pass as a whole is doing, when that is not simply "running" — the per-account
 * rows say that better than a sentence could.
 */
@Composable
private fun PassRow(pass: PassState) {
    val message = when (pass) {
        PassState.Scanning -> "Reading your camera roll…"
        is PassState.Waiting -> when (pass.reason) {
            WaitingReason.Network -> "Waiting for a network"
            WaitingReason.Wifi -> "Waiting for Wi-Fi"
            WaitingReason.Charging -> "Waiting to charge"
            WaitingReason.Soon -> "Starting shortly"
        }
        // The missing-access row above says this one far better than a line here could.
        is PassState.Failed -> when (pass.reason) {
            FailureReason.MediaAccess -> null
            // Named rather than folded into the line below, because this is the one
            // failure that will not fix itself however many times the backup tries.
            FailureReason.SignedOut ->
                "This account is signed out. Sign in again to carry on backing up."
            FailureReason.Unknown -> "The last backup could not finish. It will try again."
        }
        PassState.Running, PassState.Idle -> null
    } ?: return

    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/**
 * One destination's answer to "is it working, and how far has it got".
 *
 * There is always something to say here. A row that goes blank between passes is what
 * made a finished backup and a stalled one look identical.
 */
@Composable
private fun AccountStatusRow(progress: AccountProgress?) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${progress.completed} of ${progress.total}" +
                    (progress.filename?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@Column
        }

        val backedUp = progress?.backedUp ?: 0
        val when_ = progress?.lastCompletedAt?.let {
            DateUtils.getRelativeTimeSpanString(
                it,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        }
        Text(
            when {
                backedUp == 0 && when_ == null -> "Nothing backed up yet"
                when_ == null -> "$backedUp backed up"
                else -> "$backedUp backed up · $when_"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A count, and a way to reach the files behind it.
 *
 * Given-up files are named first because they are the ones nothing else will ever mention
 * again: a pass that skips them reports success, and the resting count simply omits them.
 */
@Composable
private fun FailuresRow(summary: FailureSummary, onOpen: () -> Unit) {
    val message = when {
        summary.givenUp > 0 && summary.willRetry > 0 ->
            "${summary.givenUp} couldn't be backed up, ${summary.willRetry} still to try"
        summary.givenUp > 0 -> "${summary.givenUp} couldn't be backed up"
        else -> "${summary.willRetry} still to try"
    }
    TextButton(onOpen, Modifier.padding(horizontal = 12.dp)) { Text(message) }
}
