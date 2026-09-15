package com.imogen.android.ui

import com.imogen.android.backup.BackupNotifications
import com.imogen.android.data.Account
import com.imogen.android.data.AccountBook
import com.imogen.android.data.TokenSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place the notification and the navigation have to agree. Either half changing
 * its mind about the URL leaves a tap that opens the app and then sits on the timeline,
 * which is the behaviour being fixed.
 */
class DeepLinkTest {

    private val account = Account(
        id = "a",
        serverUrl = "https://photos.example.com",
        userId = "user-a",
        email = "jim@example.org",
        name = "Jim",
        clientId = "client-a",
        tokens = TokenSet("at", "rt", 0, 3600, ""),
    )

    @Test
    fun `the backup notification's link opens the backup screen`() {
        assertEquals(LinkTarget.Backup, targetOf(BackupNotifications.DEEP_LINK))
    }

    @Test
    fun `a pairing link is still the account machinery's`() {
        assertEquals(LinkTarget.Accounts, targetOf("imogen://pair?code=abc"))
        assertEquals(LinkTarget.Accounts, targetOf("imogen://oauth?code=abc"))
    }

    @Test
    fun `a server whose name starts with backup is not the backup screen`() {
        assertEquals(LinkTarget.Accounts, targetOf("imogen://backupserver?code=abc"))
    }

    @Test
    fun `a first start acts on the link that started it`() {
        assertEquals(
            BackupNotifications.DEEP_LINK,
            launchLink(BackupNotifications.DEEP_LINK, restored = false),
        )
    }

    @Test
    fun `a re-created activity does not replay the link that started it`() {
        // The intent outlives the tap: it is handed back on every re-creation, so
        // resuming from recents — or rotating, or "Don't keep activities" — would open
        // the backup screen again from wherever the user actually was.
        assertNull(launchLink(BackupNotifications.DEEP_LINK, restored = true))
    }

    @Test
    fun `a backup link is let go of when there is no account left to back up`() {
        // Tapped when the last account has gone, it would otherwise wait through account
        // setup and land on the backup screen the moment a new account was linked.
        assertTrue(backupLinkAbandoned(AccountBook()))
    }

    @Test
    fun `a backup link waits while the account book is still loading`() {
        // A notification tapped from cold arrives before the book. Dropping it here would
        // lose every tap made while the app was not running.
        assertFalse(backupLinkAbandoned(null))
    }

    @Test
    fun `a backup link stands when there is an account`() {
        assertFalse(backupLinkAbandoned(AccountBook(listOf(account), activeAccountId = "a")))
    }
}
