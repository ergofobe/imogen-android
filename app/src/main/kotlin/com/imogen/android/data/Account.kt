package com.imogen.android.data

import kotlinx.serialization.Serializable

/**
 * One signed-in account on one server.
 *
 * The app is built around there being several of these, because a photo library on
 * hardware you control is usually not the only one you are in: a family server and a
 * personal one, or your own and the one a club runs. Switching between them should be a
 * tap, not a sign-out.
 *
 * The identity is local and random rather than the server's user id: the same person on
 * two servers is two accounts here, and the same user id can legitimately appear twice.
 */
@Serializable
data class Account(
    val id: String,
    /** Absolute, with no trailing slash. Everything the client does is built from it. */
    val serverUrl: String,
    val userId: String,
    val email: String,
    val name: String,
    /** Registered for this device through RFC 7591. Needed again on every refresh. */
    val clientId: String,
    val tokens: TokenSet,
    /** Whether photographs taken on this device are copied here. */
    val backupEnabled: Boolean = false,
) {
    /** What to call the server when there is no better name than its address. */
    val serverLabel: String
        get() = serverLabelOf(serverUrl)

    /**
     * What the backup records key on: the identity the server and this device agree on,
     * rather than [id].
     *
     * [id] is minted fresh for every account added and dies with the account row, so a
     * sign-out followed by signing back in to the same account used to mint a second one
     * — and with it a ledger that knew about none of the photographs already sent.
     * `(serverUrl, userId)` is the pair `AccountStore.add` already treats as the real
     * identity, and it is the same pair either side of a sign-out.
     */
    val backupKey: String
        get() = backupKeyOf(serverUrl, userId)
}

/**
 * The separator is unambiguous because a `userId` is a server-issued UUID and cannot
 * contain one: [serverUrlOfBackupKey] takes everything before the *last* separator, so it
 * recovers the address however odd the address itself is.
 */
private const val BACKUP_KEY_SEPARATOR = '|'

fun backupKeyOf(serverUrl: String, userId: String): String =
    "$serverUrl$BACKUP_KEY_SEPARATOR$userId"

/** The address half of a backup key, or the whole of it when it is not one. */
fun serverUrlOfBackupKey(backupKey: String): String =
    backupKey.substringBeforeLast(BACKUP_KEY_SEPARATOR)

/** What to call a server when there is no better name than its address. */
fun serverLabelOf(serverUrl: String): String =
    serverUrl.substringAfter("://").substringBefore('/')

@Serializable
data class TokenSet(
    val accessToken: String,
    val refreshToken: String?,
    /** Unix milliseconds. Expiry is computable without keeping the clock that read it. */
    val obtainedAt: Long,
    val expiresIn: Long,
    val scope: String,
) {
    /**
     * A minute of slack. A token that expires while a request is in flight costs a round
     * trip and a retry; refreshing one a minute early costs nothing.
     */
    fun isExpired(nowMillis: Long, skewSeconds: Long = 60): Boolean =
        nowMillis >= obtainedAt + (expiresIn - skewSeconds).coerceAtLeast(0) * 1000
}

/**
 * The later-issued of two token sets, keeping the one in hand when neither is newer.
 *
 * A [Session] is not the only writer of its own account: a copy read from the store before
 * a refresh can arrive long after it, and taking that copy would put a refresh token the
 * server has already rotated back in play. The server reads a second use of a rotated token
 * as theft and revokes the whole family, which no retry recovers from — only signing in
 * again does. Deciding on `obtainedAt` rather than simply refusing every incoming set keeps
 * a genuine re-sign-in working, because its tokens really are newer.
 */
fun newerOf(held: TokenSet, offered: TokenSet): TokenSet =
    if (offered.obtainedAt > held.obtainedAt) offered else held

/** Everything the app persists, in one document, because it is written as one. */
@Serializable
data class AccountBook(
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
) {
    val active: Account?
        get() = accounts.firstOrNull { it.id == activeAccountId } ?: accounts.firstOrNull()

    val backingUpTo: List<Account>
        get() = accounts.filter { it.backupEnabled }
}
