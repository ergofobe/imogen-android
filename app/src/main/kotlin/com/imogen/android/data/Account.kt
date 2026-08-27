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
        get() = serverUrl.substringAfter("://").substringBefore('/')
}

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
