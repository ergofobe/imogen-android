package com.imogen.android.data

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.imogen.sdk.ClientOptions
import com.imogen.sdk.ImogenClient
import com.imogen.sdk.OAuthClient
import com.imogen.sdk.PairingInvitation
import com.imogen.sdk.parsePairingUri
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the operating system sends the browser back to. Registered in the manifest. */
const val OAUTH_REDIRECT = "imogen://oauth"

/** What the server records against the grant, so it is recognisable in a list of them. */
private val CLIENT_NAME = "imogen for Android (${Build.MANUFACTURER} ${Build.MODEL})"

private val Context.pendingDataStore: DataStore<Preferences> by preferencesDataStore("pending-auth")

/**
 * Adding an account, by either of the two routes.
 *
 * Pairing is the one people should use: the browser they are already signed in to knows
 * the address and hands it over with the grant. The manual route exists because somebody
 * will want to install the app before they have a browser session anywhere, and because a
 * feature that only works one way is a feature that strands people.
 */
class AccountLinker(
    private val context: Context,
    private val store: AccountStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("pending")

    /** Reads a scanned string or a tapped link. Null when it is not one of ours. */
    fun invitationFrom(scanned: String): PairingInvitation? = parsePairingUri(scanned)

    /**
     * The whole pairing sequence. Registers a client for this device, spends the code,
     * and comes back with an account ready to use.
     */
    suspend fun pair(invitation: PairingInvitation): Account {
        val paired = OAuthClient(invitation.serverUrl).use { oauth ->
            oauth.pair(
                pairingCode = invitation.code,
                clientName = CLIENT_NAME,
                redirectUri = OAUTH_REDIRECT,
                deviceName = deviceName(),
                scopes = MOBILE_SCOPES,
            )
        }

        return finish(
            serverUrl = invitation.serverUrl,
            clientId = paired.clientId,
            tokens = TokenSet(
                accessToken = paired.tokens.tokens.accessToken,
                refreshToken = paired.tokens.tokens.refreshToken,
                obtainedAt = paired.tokens.obtainedAt,
                expiresIn = paired.tokens.tokens.expiresIn,
                scope = paired.scope,
            ),
        )
    }

    /**
     * Begins the browser flow for a server the user typed in. Returns the URL to open;
     * everything needed to complete the exchange is held until the redirect comes back.
     *
     * Held on disk rather than in memory: the browser is another app, and this one can be
     * killed while it is in front. A verifier lost that way turns into "nothing happened",
     * which is the least debuggable failure there is.
     */
    suspend fun beginBrowserSignIn(serverInput: String): String {
        val serverUrl = normalizeServerUrl(serverInput)
        val oauth = OAuthClient(serverUrl)
        return oauth.use {
            val registered = it.register(CLIENT_NAME, listOf(OAUTH_REDIRECT), MOBILE_SCOPES)
            val pending = it.beginAuthorization(registered.clientId, OAUTH_REDIRECT, MOBILE_SCOPES)
            remember(
                Pending(
                    serverUrl = serverUrl,
                    clientId = pending.clientId,
                    codeVerifier = pending.codeVerifier,
                    state = pending.state,
                    redirectUri = pending.redirectUri,
                ),
            )
            pending.authorizationUrl
        }
    }

    /** Completes the browser flow from the callback the operating system delivered. */
    suspend fun completeBrowserSignIn(callbackUrl: String): Account {
        val pending = recall() ?: error("There is no sign-in waiting for this callback")
        val stored = OAuthClient(pending.serverUrl).use { oauth ->
            oauth.completeAuthorization(
                com.imogen.sdk.PendingAuthorization(
                    authorizationUrl = "",
                    codeVerifier = pending.codeVerifier,
                    state = pending.state,
                    redirectUri = pending.redirectUri,
                    clientId = pending.clientId,
                ),
                callbackUrl,
            )
        }
        forget()

        return finish(
            serverUrl = pending.serverUrl,
            clientId = pending.clientId,
            tokens = TokenSet(
                accessToken = stored.tokens.accessToken,
                refreshToken = stored.tokens.refreshToken,
                obtainedAt = stored.obtainedAt,
                expiresIn = stored.tokens.expiresIn,
                scope = stored.tokens.scope,
            ),
        )
    }

    private suspend fun finish(serverUrl: String, clientId: String, tokens: TokenSet): Account {
        // Who this is has to come from the server: the token says nothing about it, and an
        // account list showing "Account 2" would be useless the moment there are two.
        val user = ImogenClient(serverUrl, tokens.accessToken).use { it.auth.me() }

        return store.add(
            Account(
                id = AccountStore.newAccountId(),
                serverUrl = serverUrl,
                userId = user.id,
                email = user.email,
                name = user.name,
                clientId = clientId,
                tokens = tokens,
            ),
        )
    }

    private suspend fun remember(pending: Pending) {
        val sealed = SecretBox.seal(json.encodeToString(pending).toByteArray())
        context.pendingDataStore.edit { it[key] = Base64.encodeToString(sealed, Base64.NO_WRAP) }
    }

    private suspend fun recall(): Pending? {
        val stored = context.pendingDataStore.data.first()[key] ?: return null
        val opened = SecretBox.open(Base64.decode(stored, Base64.NO_WRAP)) ?: return null
        return runCatching { json.decodeFromString<Pending>(String(opened)) }.getOrNull()
    }

    private suspend fun forget() {
        context.pendingDataStore.edit { it.remove(key) }
    }

    @Serializable
    private data class Pending(
        val serverUrl: String,
        val clientId: String,
        val codeVerifier: String,
        val state: String,
        val redirectUri: String,
    )

    companion object {
        /**
         * What the app asks for. Not `profile` alone and not everything: an administrator's
         * tools stay behind a browser session, deliberately, so a lost phone is not a lost
         * server.
         */
        val MOBILE_SCOPES = listOf(
            "library:read",
            "library:write",
            "albums:read",
            "albums:write",
            "profile",
        )

        fun deviceName(): String = "${Build.MANUFACTURER.replaceFirstChar(Char::titlecase)} ${Build.MODEL}"
    }
}

/**
 * Turns what somebody typed into a URL.
 *
 * People type `photos.example.com`, and a scheme is not something they should have to
 * think about. https, because a photo library reached over plain http is one whose
 * password crosses the network in the clear — except on a loopback address, which is
 * where somebody testing against a server on the same machine will be.
 */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val loopback = trimmed.startsWith("localhost") ||
        trimmed.startsWith("127.0.0.1") ||
        trimmed.startsWith("10.0.2.2")
    return if (loopback) "http://$trimmed" else "https://$trimmed"
}
