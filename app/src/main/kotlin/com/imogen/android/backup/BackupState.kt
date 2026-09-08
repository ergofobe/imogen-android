package com.imogen.android.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.backupStateStore: DataStore<Preferences> by preferencesDataStore("backup-state")

/**
 * When each destination was last brought up to date.
 *
 * Not in the ledger, which is a row per file and would need a schema migration to gain a
 * second table — and the one thing that must not happen to the upload ledger is losing it,
 * because the phone would then re-read and re-post the entire camera roll.
 */
class BackupState(private val context: Context) {

    val lastCompleted: Flow<Map<String, Long>> = context.backupStateStore.data.map { stored ->
        stored.asMap()
            .mapNotNull { (key, value) ->
                val id = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) }
                val at = value as? Long
                if (id != null && at != null) id to at else null
            }
            .toMap()
    }

    /** Stamped when a pass finishes with nothing left owed to these accounts. */
    suspend fun recordCompleted(accountIds: Collection<String>, at: Long) {
        if (accountIds.isEmpty()) return
        context.backupStateStore.edit { stored ->
            accountIds.forEach { stored[longPreferencesKey("$PREFIX$it")] = at }
        }
    }

    suspend fun forget(accountId: String) {
        context.backupStateStore.edit { it.remove(longPreferencesKey("$PREFIX$accountId")) }
    }

    private companion object {
        const val PREFIX = "lastCompleted."
    }
}
