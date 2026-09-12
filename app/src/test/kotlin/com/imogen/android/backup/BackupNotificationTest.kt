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

        BackupNotifications.post(context, finished)

        val shown = shadowOf(manager).activeNotifications.map { it.id }
        assertEquals(listOf(BackupNotifications.RESULT_ID), shown)
    }

    @Test
    fun `a pass starting clears the last one's result`() {
        BackupNotifications.post(context, finished)

        BackupNotifications.clearResult(context)

        assertEquals(emptyList<Int>(), shadowOf(manager).activeNotifications.map { it.id })
    }

    /** `POST_NOTIFICATIONS` is optional, and a pass that cannot say so still ran. */
    @Test
    fun `a phone that refused notifications gets none, and no exception`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        BackupNotifications.post(context, finished)

        assertEquals(emptyList<Int>(), shadowOf(manager).activeNotifications.map { it.id })
    }
}
