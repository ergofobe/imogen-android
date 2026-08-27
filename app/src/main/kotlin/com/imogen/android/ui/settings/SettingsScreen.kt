package com.imogen.android.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imogen.android.data.Account
import com.imogen.android.ui.RootViewModel

/**
 * Accounts and backup, in that order, because they are the same decision seen twice: which
 * servers this phone talks to, and which of them get a copy of what it photographs.
 */
@Composable
fun SettingsScreen(
    model: RootViewModel,
    activeAccountId: String,
    onAddAccount: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val book by model.book.collectAsStateWithLifecycle()
    val accounts = book?.accounts.orEmpty()
    var signingOut by remember { mutableStateOf<Account?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SectionHeading("Accounts")

        accounts.forEach { account ->
            AccountRow(
                account = account,
                active = account.id == activeAccountId,
                onSelect = { model.switchTo(account.id) },
                onSignOut = { signingOut = account },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddAccount)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.Add, null)
            Text("Add an account", style = MaterialTheme.typography.bodyLarge)
        }

        HorizontalDivider()
        SectionHeading("Backup")

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenBackup)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.CloudUpload, null)
            Column {
                Text("Photo backup", style = MaterialTheme.typography.bodyLarge)
                Text(
                    backupSummary(accounts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()
        SectionHeading("About")
        Text(
            "imogen for Android is a client for your own imogen server. Administration — " +
                "accounts, the processing queue, what is shared publicly — stays in the web " +
                "interface, where a signed-in browser session is what unlocks it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            "AGPL-3.0-or-later · github.com/ergofobe/imogen-android",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }

    signingOut?.let { account ->
        AlertDialog(
            onDismissRequest = { signingOut = null },
            title = { Text("Sign out of ${account.serverLabel}?") },
            text = {
                Text(
                    "The photographs stay on the server. Anything waiting to be backed up " +
                        "to this account will not be sent.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.signOut(account.id)
                        signingOut = null
                    },
                ) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { signingOut = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountRow(
    account: Account,
    active: Boolean,
    onSelect: () -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = active, onClick = onSelect)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                account.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${account.email} · ${account.serverLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun backupSummary(accounts: List<Account>): String {
    val chosen = accounts.filter { it.backupEnabled }
    return when (chosen.size) {
        0 -> "Not backing up"
        1 -> "Backing up to ${chosen.first().serverLabel}"
        else -> "Backing up to ${chosen.size} accounts"
    }
}
