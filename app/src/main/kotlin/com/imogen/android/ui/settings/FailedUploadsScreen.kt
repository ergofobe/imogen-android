package com.imogen.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imogen.android.backup.FailedUpload
import com.imogen.android.backup.FailureState
import com.imogen.android.backup.summarise
import com.imogen.android.data.serverLabelOf
import com.imogen.android.data.serverUrlOfBackupKey

/**
 * The photographs that did not make it, and a way to ask again.
 *
 * The retry matters more than the list. A file that spends its attempts is dropped from
 * every future pass, so when the reason was a bug in this app rather than anything about
 * the file, the only route back into the backup is from here.
 */
@Composable
fun FailedUploadsScreen(
    failures: List<FailedUpload>,
    serverLabels: Map<String, String>,
    onRetry: (FailedUpload) -> Unit,
    onRetryAll: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (failures.isEmpty()) {
        Column(modifier.fillMaxSize().padding(contentPadding).padding(20.dp)) {
            Text("Nothing has failed", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Every photograph the app has tried to back up has either arrived or is " +
                    "still waiting its turn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }

    val summary = summarise(failures)

    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    if (summary.givenUp > 0) {
                        "${summary.givenUp} given up on, ${summary.willRetry} still to be tried"
                    } else {
                        "${summary.willRetry} still to be tried"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (summary.givenUp > 0) {
                    Text(
                        "Anything given up on is skipped by every future backup until you " +
                            "ask for it again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onRetryAll, Modifier.padding(top = 8.dp)) {
                        Text("Try all of them again")
                    }
                }
            }
            HorizontalDivider()
        }

        items(failures, key = { "${it.backupKey}/${it.deviceAssetId}" }) { failure ->
            FailureRow(
                failure = failure,
                serverLabel = serverLabels[failure.backupKey],
                onRetry = { onRetry(failure) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FailureRow(failure: FailedUpload, serverLabel: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            failure.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                // An account that has been signed out owns no label any more, and the
                // key it left behind still names the server it was talking to.
                append(serverLabel ?: serverLabelOf(serverUrlOfBackupKey(failure.backupKey)))
                append(" · ")
                append(
                    when (failure.state) {
                        FailureState.GivenUp -> "given up after ${failure.attempts} tries"
                        FailureState.WillRetry -> "tried ${failure.attempts}, will try again"
                    },
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        failure.lastError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (failure.state == FailureState.GivenUp) {
            TextButton(onRetry, Modifier.padding(top = 4.dp)) { Text("Try again") }
        }
    }
}
