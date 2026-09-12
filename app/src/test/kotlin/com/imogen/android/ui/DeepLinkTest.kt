package com.imogen.android.ui

import com.imogen.android.backup.BackupNotifications
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one place the notification and the navigation have to agree. Either half changing
 * its mind about the URL leaves a tap that opens the app and then sits on the timeline,
 * which is the behaviour being fixed.
 */
class DeepLinkTest {

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
}
