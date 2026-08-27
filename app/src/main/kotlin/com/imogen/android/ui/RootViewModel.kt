package com.imogen.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.imogen.android.ImogenApplication
import com.imogen.android.backup.BackupScheduler
import com.imogen.android.data.Account
import com.imogen.android.data.AccountBook
import com.imogen.android.data.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What is happening while an account is being added, so a screen can say so. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Working : LinkState
    data class Failed(val message: String) : LinkState
    data class Linked(val account: Account) : LinkState
}

/**
 * The state every screen needs: which accounts exist, which one is in front, and the
 * session that goes with it.
 *
 * Held above the navigation graph rather than per screen, because switching account has
 * to change what every screen is looking at at once — a timeline still showing the last
 * server's photographs after a switch is the bug this arrangement exists to prevent.
 */
class RootViewModel(private val app: ImogenApplication) : ViewModel() {

    val book: StateFlow<AccountBook?> = app.accountStore.book
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    /** The live session for whichever account is in front, or null before there is one. */
    fun sessionFor(account: Account): Session = app.sessions.sessionFor(account)

    fun switchTo(accountId: String) {
        viewModelScope.launch { app.accountStore.setActive(accountId) }
    }

    fun signOut(accountId: String) {
        viewModelScope.launch {
            // The grant is revoked server-side first: an account removed from the phone
            // but left live on the server is a token nobody can see and nobody can stop.
            val account = book.value?.accounts?.firstOrNull { it.id == accountId }
            if (account != null) {
                runCatching {
                    com.imogen.sdk.OAuthClient(account.serverUrl).use {
                        it.revoke(account.tokens.accessToken)
                        account.tokens.refreshToken?.let { token -> it.revoke(token) }
                    }
                }
            }
            app.accountStore.remove(accountId)
            app.sessions.forget(accountId)
        }
    }

    fun setBackupEnabled(accountId: String, enabled: Boolean) {
        viewModelScope.launch {
            app.accountStore.setBackupEnabled(accountId, enabled)
            val preferences = app.backupSettings.current()
            BackupScheduler.sync(app, preferences)
            if (enabled) BackupScheduler.runNow(app, preferences)
        }
    }

    // --- adding an account ---

    /**
     * Handles anything the operating system delivered to the app: a pairing code from a
     * QR reader or a tapped link, or the OAuth redirect coming back from the browser.
     *
     * Returns false for a URL that is neither, so a share sheet sending something odd
     * this way does not turn into an error the user has to dismiss.
     */
    fun consumeDeepLink(url: String): Boolean {
        val invitation = app.linker.invitationFrom(url)
        if (invitation != null) {
            pair(url)
            return true
        }
        if (url.startsWith("imogen://oauth")) {
            completeBrowserSignIn(url)
            return true
        }
        return false
    }

    fun pair(scanned: String) {
        val invitation = app.linker.invitationFrom(scanned) ?: run {
            _link.value = LinkState.Failed("That is not an imogen pairing code.")
            return
        }
        _link.value = LinkState.Working
        viewModelScope.launch {
            _link.value = runCatching { app.linker.pair(invitation) }
                .fold({ LinkState.Linked(it) }, { LinkState.Failed(describe(it)) })
        }
    }

    fun beginBrowserSignIn(serverInput: String, open: (String) -> Unit) {
        _link.value = LinkState.Working
        viewModelScope.launch {
            runCatching { app.linker.beginBrowserSignIn(serverInput) }
                .onSuccess {
                    // Back to idle: the browser is in front now, and leaving a spinner
                    // behind it would still be spinning if somebody backed out.
                    _link.value = LinkState.Idle
                    open(it)
                }
                .onFailure { _link.value = LinkState.Failed(describe(it)) }
        }
    }

    private fun completeBrowserSignIn(callbackUrl: String) {
        _link.value = LinkState.Working
        viewModelScope.launch {
            _link.value = runCatching { app.linker.completeBrowserSignIn(callbackUrl) }
                .fold({ LinkState.Linked(it) }, { LinkState.Failed(describe(it)) })
        }
    }

    fun clearLinkState() {
        _link.value = LinkState.Idle
    }

    /**
     * A message worth putting in front of somebody.
     *
     * The SDK's exceptions already carry what the server said; everything else usually
     * means the address was wrong or the server was not there, and saying so is more
     * use than the class name of a socket failure.
     */
    private fun describe(error: Throwable): String = when (error) {
        is com.imogen.sdk.ImogenException -> error.message
        is com.imogen.sdk.OAuthException -> error.message ?: "That did not work."
        else -> "Could not reach that server. Check the address and try again."
    }

    companion object {
        fun factory(app: ImogenApplication) = viewModelFactory {
            initializer { RootViewModel(app) }
        }
    }
}
