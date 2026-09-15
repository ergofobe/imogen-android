package com.imogen.android.backup

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imogen.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The notification itself, built for real.
 *
 * Nothing here looks at the system UI — what can be wrong is what the builder produced:
 * whether there is anywhere to go when it is tapped, whether the tap carries a mutable
 * intent out of the process, and whether the pass leaves anything behind when it ends.
 */
@RunWith(AndroidJUnit4::class)
class BackupNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val oneDestination = listOf(DestinationProgress("photos.example.com", 3, 40))

    private val finished =
        finishedNotice(listOf(DestinationProgress("photos.example.com", 484, 484)))!!

    private fun sent(label: String, uploaded: Int) = DestinationProgress(label, uploaded, 40)

    private fun signedOutOf(server: String) =
        PassNotice.Failed(FailureReason.SignedOut, listOf(server))

    private fun showing() = shadowOf(manager).activeNotifications
        .single { it.id == BackupNotifications.RESULT_ID }
        .notification

    @Before
    fun allowNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `tapping the progress notification opens the backup screen`() {
        val intent = BackupNotifications.progress(context, oneDestination).contentIntent

        assertNotNull(intent)
        val shadow = shadowOf(intent)
        assertEquals(Uri.parse(BackupNotifications.DEEP_LINK), shadow.savedIntent.data)
        assertEquals(MainActivity::class.java.name, shadow.savedIntent.component?.className)
    }

    @Test
    fun `the tap does not hand a mutable intent out of the process`() {
        val intent = BackupNotifications.progress(context, oneDestination).contentIntent

        assertNotEquals(0, shadowOf(intent).flags and PendingIntent.FLAG_IMMUTABLE)
    }

    @Test
    fun `the progress notification names the server and keeps its bar`() {
        val notification = BackupNotifications.progress(context, oneDestination)

        assertTrue(
            notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
                .contains("photos.example.com"),
        )
        assertEquals(3, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(40, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertNotEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    fun `the finished notification is dismissible, tappable, and says what happened`() {
        val notification = BackupNotifications.result(context, finished)

        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertNotEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertNotNull(notification.contentIntent)
        assertTrue(notification.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("484"))
    }

    @Test
    fun `the result is on a channel of its own, so it can be turned off separately`() {
        val notice = PassNotice.Failed(FailureReason.SignedOut, listOf("photos.example.com"))

        val notification = BackupNotifications.result(context, notice)

        assertNotEquals(BackupNotifications.PROGRESS_CHANNEL, notification.channelId)
        assertEquals(BackupNotifications.RESULT_CHANNEL, notification.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(BackupNotifications.PROGRESS_CHANNEL).importance,
        )
        // Deliberate, and the issue asked for the decision to be made rather than
        // inherited: the progress bar stays out of the way, the verdict does not.
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(BackupNotifications.RESULT_CHANNEL).importance,
        )
    }

    @Test
    fun `the result replaces the ongoing notification rather than adding to it`() {
        manager.notify(
            BackupNotifications.PROGRESS_ID,
            BackupNotifications.progress(context, oneDestination),
        )

        BackupNotifications.settle(context, finished)

        val shown = shadowOf(manager).activeNotifications.map { it.id }
        assertEquals(listOf(BackupNotifications.RESULT_ID), shown)
    }

    @Test
    fun `a pass with nothing to say takes the last one's result down`() {
        BackupNotifications.settle(context, finished)

        BackupNotifications.settle(context, null)

        assertEquals(emptyList<Int>(), shadowOf(manager).activeNotifications.map { it.id })
    }

    @Test
    fun `the same verdict over again does not alert twice`() {
        val stopped = PassNotice.Failed(FailureReason.MediaAccess, emptyList())
        BackupNotifications.settle(context, stopped)

        BackupNotifications.settle(context, stopped)

        assertNotEquals(0, showing().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun `a verdict that replaces a different one alerts`() {
        BackupNotifications.settle(context, finished)

        BackupNotifications.settle(
            context,
            PassNotice.Failed(FailureReason.SignedOut, listOf("family.example.org")),
        )

        // The whole point of the result channel: the verdict that needs acting on must
        // not arrive silently just because something else was already in the shade.
        assertEquals(0, showing().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun `the first verdict of all alerts`() {
        BackupNotifications.settle(context, finished)

        assertEquals(0, showing().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun `a failure with nothing to add can still be read in full`() {
        val stopped = PassNotice.Failed(FailureReason.SignedOut, listOf("family.example.org"))

        val notification = BackupNotifications.result(context, stopped)

        assertEquals(
            noticeText(stopped),
            notification.extras.getString(Notification.EXTRA_BIG_TEXT),
        )
    }

    // A retrying pass. It has no verdict of its own — it has not ended — so the only
    // thing it may do to the shade is take down what it has proved wrong.

    @Test
    fun `a retry takes down a sign-out it has disproved`() {
        BackupNotifications.settle(context, signedOutOf("photos.example.com"))

        BackupNotifications.retire(context, disprovedByRetry(listOf(sent("photos.example.com", 12)), signedOutNow = emptySet()))

        assertEquals(emptyList<Int>(), shadowOf(manager).activeNotifications.map { it.id })
    }

    @Test
    fun `a retry that disproves nothing leaves the verdict, and the repeat stays quiet`() {
        val stopped = signedOutOf("photos.example.com")
        BackupNotifications.settle(context, stopped)

        // Unreachable on the first file, so nothing got through and nothing is disproved.
        BackupNotifications.retire(context, disprovedByRetry(listOf(sent("photos.example.com", 0)), signedOutNow = emptySet()))
        BackupNotifications.settle(context, stopped)

        // The half that must not regress. #21's rule turns on what the shade is already
        // holding, so a retry that cleared it would have a flaky network alert somebody
        // about the same sign-out on every backoff cycle.
        assertNotEquals(0, showing().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun `a retry does not take down a sign-out this very pass found again`() {
        val stopped = signedOutOf("photos.example.com")
        BackupNotifications.settle(context, stopped)

        // The token expired part way through: the uploads got in before the 401, and the
        // pass is carrying fresh proof that the account is signed out after all.
        BackupNotifications.retire(
            context,
            disprovedByRetry(
                listOf(sent("photos.example.com", 5)),
                signedOutNow = setOf("photos.example.com"),
            ),
        )

        assertTrue(
            showing().extras.getString(Notification.EXTRA_TEXT).orEmpty()
                .contains("photos.example.com"),
        )
    }

    @Test
    fun `a retry leaves a verdict it has only half disproved`() {
        val stopped = PassNotice.Failed(
            FailureReason.SignedOut,
            listOf("photos.example.com", "family.example.org"),
        )
        BackupNotifications.settle(context, stopped)

        BackupNotifications.retire(context, disprovedByRetry(listOf(sent("photos.example.com", 12)), signedOutNow = emptySet()))

        // The other one is still signed out, and that is still the thing to act on.
        assertTrue(
            showing().extras.getString(Notification.EXTRA_TEXT).orEmpty()
                .contains("family.example.org"),
        )
    }

    @Test
    fun `a verdict a retry retired alerts when it comes back`() {
        val stopped = signedOutOf("photos.example.com")
        BackupNotifications.settle(context, stopped)
        BackupNotifications.retire(context, disprovedByRetry(listOf(sent("photos.example.com", 12)), signedOutNow = emptySet()))

        BackupNotifications.settle(context, stopped)

        // Signed in, then signed out again, is news rather than a repeat.
        assertEquals(0, showing().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun `a retry does not take down a finished verdict`() {
        BackupNotifications.settle(context, finished)

        BackupNotifications.retire(context, disprovedByRetry(listOf(sent("photos.example.com", 12)), signedOutNow = emptySet()))

        assertEquals(
            noticeTitle(finished),
            showing().extras.getString(Notification.EXTRA_TITLE),
        )
    }

    /** `POST_NOTIFICATIONS` is optional, and a pass that cannot say so still ran. */
    @Test
    fun `a phone that refused notifications gets none, and no exception`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        BackupNotifications.settle(context, finished)

        assertEquals(emptyList<Int>(), shadowOf(manager).activeNotifications.map { it.id })
    }
}
