package com.imogen.android.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore("accounts")

/**
 * Where the accounts live.
 *
 * One sealed blob rather than a row per field: the whole book is read and written as a
 * unit anyway, and encrypting it whole means no key in the store is a plaintext hint
 * about what the others contain.
 */
class AccountStore(private val context: Context) {

    private val key = stringPreferencesKey("book")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val book: Flow<AccountBook> = context.accountDataStore.data.map { read(it) }

    suspend fun current(): AccountBook = book.first()

    /** Adds an account and makes it the one in front. Adding one is a deliberate act. */
    suspend fun add(account: Account): Account = mutate { existing ->
        // Signing in again to a server this device already knows replaces that account
        // rather than making a second one, which would then both hold live grants.
        val others = existing.accounts.filterNot {
            it.serverUrl == account.serverUrl && it.userId == account.userId
        }
        val kept = existing.accounts.firstOrNull {
            it.serverUrl == account.serverUrl && it.userId == account.userId
        }
        val merged = account.copy(
            id = kept?.id ?: account.id,
            backupEnabled = kept?.backupEnabled ?: account.backupEnabled,
        )
        existing.copy(accounts = others + merged, activeAccountId = merged.id)
    }.let { book -> book.accounts.last() }

    suspend fun update(accountId: String, change: (Account) -> Account) {
        mutate { existing ->
            existing.copy(
                accounts = existing.accounts.map { if (it.id == accountId) change(it) else it },
            )
        }
    }

    suspend fun remove(accountId: String) {
        mutate { existing ->
            val remaining = existing.accounts.filterNot { it.id == accountId }
            existing.copy(
                accounts = remaining,
                activeAccountId = existing.activeAccountId
                    ?.takeIf { it != accountId }
                    ?: remaining.firstOrNull()?.id,
            )
        }
    }

    suspend fun setActive(accountId: String) {
        mutate { it.copy(activeAccountId = accountId) }
    }

    suspend fun setBackupEnabled(accountId: String, enabled: Boolean) {
        update(accountId) { it.copy(backupEnabled = enabled) }
    }

    private suspend fun mutate(change: (AccountBook) -> AccountBook): AccountBook {
        var result = AccountBook()
        context.accountDataStore.edit { preferences ->
            result = change(read(preferences))
            preferences[key] = seal(result)
        }
        return result
    }

    private fun read(preferences: Preferences): AccountBook {
        val stored = preferences[key] ?: return AccountBook()
        val opened = SecretBox.open(Base64.decode(stored, Base64.NO_WRAP))
            ?: return AccountBook()
        return runCatching { json.decodeFromString<AccountBook>(String(opened)) }
            .getOrElse { AccountBook() }
    }

    private fun seal(book: AccountBook): String =
        Base64.encodeToString(SecretBox.seal(json.encodeToString(book).toByteArray()), Base64.NO_WRAP)

    companion object {
        fun newAccountId(): String = UUID.randomUUID().toString()
    }
}
