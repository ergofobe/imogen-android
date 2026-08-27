package com.imogen.android.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One item on the camera roll, with enough about it to upload it and to recognise it
 * again next time.
 */
data class LocalMedia(
    /**
     * Stable across passes, and what the server stores as `deviceAssetId`. The volume is
     * part of it because MediaStore ids are unique per volume, not per device — an SD
     * card can hand out the same number as internal storage.
     */
    val deviceAssetId: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Milliseconds. When the camera took it, falling back to when the file appeared. */
    val takenAtMillis: Long,
    val isVideo: Boolean,
    /** The path MediaStore reports, when it reports one and it can actually be read. */
    val path: String?,
)

/**
 * Reading the camera roll.
 *
 * MediaStore rather than walking directories: it is the only thing that knows about every
 * volume, it is the only thing scoped storage still lets an app read, and it already
 * holds the capture date that would otherwise mean opening every file to find the EXIF.
 */
class MediaScanner(private val context: Context) {

    fun scan(includeVideos: Boolean, cameraOnly: Boolean): List<LocalMedia> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val columns = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.DATE_ADDED)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Files.FileColumns.VOLUME_NAME)
                add(MediaStore.Files.FileColumns.DATE_TAKEN)
            }
        }.toTypedArray()

        val types = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            if (includeVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
        }
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} in (${types.joinToString(",") { "?" }})"

        val found = mutableListOf<LocalMedia>()
        context.contentResolver.query(
            collection,
            columns,
            selection,
            types.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_ADDED} desc",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val bucket = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val data = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val volume = cursor.getColumnIndex(MediaStore.Files.FileColumns.VOLUME_NAME)
            val taken = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)

            while (cursor.moveToNext()) {
                val bucketName = if (bucket >= 0) cursor.getString(bucket) else null
                if (cameraOnly && !isCameraBucket(bucketName)) continue

                val bytes = cursor.getLong(size)
                // A row with no bytes is a placeholder for something still being written.
                if (bytes <= 0) continue

                val rowId = cursor.getLong(id)
                val volumeName = if (volume >= 0) cursor.getString(volume) else "external"
                val isVideo =
                    cursor.getInt(mediaType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                // DATE_TAKEN is milliseconds and the DATE_* columns are seconds, which is
                // a factor of a thousand and about fifty years of wrong capture dates.
                val takenMillis = when {
                    taken >= 0 && !cursor.isNull(taken) && cursor.getLong(taken) > 0 ->
                        cursor.getLong(taken)
                    cursor.getLong(added) > 0 -> cursor.getLong(added) * 1000
                    else -> cursor.getLong(modified) * 1000
                }

                val reported = if (data >= 0) cursor.getString(data) else null

                found += LocalMedia(
                    deviceAssetId = "android:$volumeName:$rowId",
                    uri = ContentUris.withAppendedId(collection, rowId),
                    displayName = cursor.getString(name) ?: "IMG_$rowId",
                    mimeType = cursor.getString(mime)
                        ?: if (isVideo) "video/mp4" else "image/jpeg",
                    sizeBytes = bytes,
                    takenAtMillis = takenMillis,
                    isVideo = isVideo,
                    path = reported?.takeIf { File(it).canRead() },
                )
            }
        }
        return found
    }

    /**
     * Whether this looks like something the camera produced.
     *
     * Bucket names, because there is no flag for it: every Android camera app writes into
     * DCIM, and the ones that make their own folder still put it there. Anything cleverer
     * would be guessing at EXIF on several thousand files to answer a question the
     * directory already answers.
     */
    private fun isCameraBucket(bucket: String?): Boolean {
        val name = bucket?.lowercase(Locale.ROOT) ?: return false
        return name in CAMERA_BUCKETS
    }

    private companion object {
        val CAMERA_BUCKETS = setOf("camera", "dcim", "100andro", "opencamera")
    }
}

/** ISO-8601 in UTC, which is the only format the contract accepts. */
fun isoInstant(millis: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(millis))
}
