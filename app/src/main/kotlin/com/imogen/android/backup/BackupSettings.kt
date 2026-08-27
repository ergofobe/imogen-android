package com.imogen.android.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore("backup")

/**
 * How backup behaves, as opposed to where it goes — which account it copies to is a
 * property of the account, because it is chosen per account and there may be several.
 */
data class BackupPreferences(
    /** Off until asked for. Uploading somebody's camera roll uninvited is not a default. */
    val enabled: Boolean = false,
    val unmeteredOnly: Boolean = true,
    val whileChargingOnly: Boolean = false,
    val includeVideos: Boolean = true,
    /**
     * Photographs this device took, rather than every image on it. Screenshots, saved
     * memes and WhatsApp thumbnails are not what anybody means by "back up my photos".
     */
    val cameraOnly: Boolean = true,
)

class BackupSettings(private val context: Context) {

    private val enabled = booleanPreferencesKey("enabled")
    private val unmetered = booleanPreferencesKey("unmeteredOnly")
    private val charging = booleanPreferencesKey("whileChargingOnly")
    private val videos = booleanPreferencesKey("includeVideos")
    private val cameraOnly = booleanPreferencesKey("cameraOnly")

    val preferences: Flow<BackupPreferences> = context.backupDataStore.data.map { stored ->
        val defaults = BackupPreferences()
        BackupPreferences(
            enabled = stored[enabled] ?: defaults.enabled,
            unmeteredOnly = stored[unmetered] ?: defaults.unmeteredOnly,
            whileChargingOnly = stored[charging] ?: defaults.whileChargingOnly,
            includeVideos = stored[videos] ?: defaults.includeVideos,
            cameraOnly = stored[cameraOnly] ?: defaults.cameraOnly,
        )
    }

    suspend fun current(): BackupPreferences = preferences.first()

    suspend fun update(change: (BackupPreferences) -> BackupPreferences) {
        val next = change(current())
        context.backupDataStore.edit {
            it[enabled] = next.enabled
            it[unmetered] = next.unmeteredOnly
            it[charging] = next.whileChargingOnly
            it[videos] = next.includeVideos
            it[cameraOnly] = next.cameraOnly
        }
    }
}
