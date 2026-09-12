package com.imogen.android.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.imogen.android.MainActivity
import com.imogen.android.R

/**
 * Everything the backup says from the notification shade.
 *
 * Two channels, deliberately. The progress one stays `IMPORTANCE_LOW` and deferred: a copy
 * that runs for an hour must not interrupt anything, and it is the whole reason the pass
 * is easy to overlook. What answers that is the other channel — a pass that ended has one
 * moment worth noticing, it is over in a glance, and somebody who disagrees can turn that
 * channel off without also losing the progress bar.
 */
object BackupNotifications {

    /** Where a tap goes. Handled in-process, so it is not in any intent filter. */
    const val DEEP_LINK = "imogen://backup"

    const val PROGRESS_CHANNEL = "backup"
    const val RESULT_CHANNEL = "backup-result"
    const val PROGRESS_ID = 4201
    const val RESULT_ID = 4202

    fun foreground(context: Context, destinations: List<DestinationProgress>): ForegroundInfo {
        val notification = progress(context, destinations)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    fun progress(context: Context, destinations: List<DestinationProgress>): Notification {
        createChannels(context)
        val owed = destinations.filter { it.total > 0 }
        return builder(context, PROGRESS_CHANNEL)
            .setContentTitle(context.getString(R.string.backup_running))
            .setContentText(progressText(destinations))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(owed.sumOf { it.total }, owed.sumOf { it.completed }, false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .withDetail(progressDetail(destinations))
            .build()
    }

    fun result(context: Context, notice: PassNotice): Notification {
        createChannels(context)
        return builder(context, RESULT_CHANNEL)
            .setContentTitle(noticeTitle(notice))
            .setContentText(noticeText(notice))
            .setSmallIcon(iconFor(notice))
            .setAutoCancel(true)
            // A pass that cannot run keeps failing every six hours, and the same verdict
            // arriving with a sound four times a day is the nuisance this whole change is
            // trying not to become. Said once, then updated in place.
            .setOnlyAlertOnce(true)
            .withDetail(noticeDetail(notice))
            .build()
    }

    /**
     * What the shade is left holding when a pass ends: this verdict, or nothing.
     *
     * One call rather than a cancel and a post, because a pass that keeps failing every
     * six hours posts the same verdict every six hours — and cancelling first would make
     * each one a new notification, which is exactly what `setOnlyAlertOnce` is there to
     * stop. Updating the record in place is what keeps it silent after the first.
     *
     * The progress notification goes either way. That covers the case WorkManager does
     * not: a pass whose foreground promotion was refused owns 4201 itself.
     */
    fun settle(context: Context, notice: PassNotice?) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(PROGRESS_ID)
        if (notice == null) {
            manager.cancel(RESULT_ID)
            return
        }
        // POST_NOTIFICATIONS is optional and the upload runs without it. Denied, this is
        // simply nothing — never a reason to fail a pass that has already done its work.
        // The version guard is not ceremony: before 33 the permission is unknown to the
        // platform and asking about it answers "denied" on a phone that would have shown
        // the notification quite happily.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(RESULT_ID, result(context, notice))
        } catch (_: SecurityException) {
            // Revoked between the check and here, which is rare and is still not worth a
            // crash at the end of a pass that worked.
        }
    }

    private fun builder(context: Context, channel: String) =
        NotificationCompat.Builder(context, channel)
            .setContentIntent(contentIntent(context))

    private fun NotificationCompat.Builder.withDetail(detail: String?) =
        if (detail == null) this else setStyle(NotificationCompat.BigTextStyle().bigText(detail))

    private fun iconFor(notice: PassNotice) = when (notice) {
        is PassNotice.Finished -> android.R.drawable.stat_sys_upload_done
        is PassNotice.Failed -> android.R.drawable.stat_notify_error
    }

    /**
     * The activity is named rather than left to an intent filter: the backup screen is
     * ours to open, not something any web page should be able to aim the app at.
     * `FLAG_IMMUTABLE` because nothing outside this process has any business rewriting it.
     */
    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(DEEP_LINK))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL,
                context.getString(R.string.backup_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL,
                context.getString(R.string.backup_result_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}
