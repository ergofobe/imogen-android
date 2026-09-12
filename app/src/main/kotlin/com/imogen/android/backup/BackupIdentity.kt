package com.imogen.android.backup

import com.imogen.android.data.Account

/**
 * Bringing records written under a device-local account id onto the account's stable key.
 *
 * Without this the fix would cause the bug it fixes: every record on every phone already
 * out there is keyed by a random local id, and simply starting to ask about backup keys
 * would find none of them and re-upload the whole camera roll once.
 *
 * The mapping only exists while the account does, which is why this runs from the backup
 * pass rather than once at some later upgrade — an account still signed in still carries
 * both halves of it. Idempotent: after the first run nothing matches the old id.
 */
suspend fun adoptStableKeys(
    accounts: List<Account>,
    move: suspend (from: String, to: String) -> Unit,
) {
    accounts.forEach { account ->
        if (account.id != account.backupKey) move(account.id, account.backupKey)
    }
}
