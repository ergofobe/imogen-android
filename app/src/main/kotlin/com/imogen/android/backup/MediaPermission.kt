package com.imogen.android.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How much of the camera roll the app is allowed to read. */
enum class MediaAccess {
    /** Everything backup was asked to copy. */
    Granted,

    /**
     * Some of it. Either somebody chose a handful of photographs on Android 14, or the
     * images were allowed and the videos were not. Worth backing up either way — refusing
     * to copy what we can read would be worse than copying it.
     */
    Partial,

    /** Nothing. MediaStore will hand back an empty cursor and no error with it. */
    Denied,
}

/**
 * Which permissions reading the camera roll needs, and what a set of answers adds up to.
 *
 * Both decisions are here rather than in the screen because both are arithmetic over an
 * API level, and neither needs an emulator to be shown correct. The API level is a
 * parameter for the same reason: `Build.VERSION.SDK_INT` reads zero off the unit-test
 * stubs, so a function that consulted it directly could not be tested at all.
 */
object MediaPermission {

    /**
     * Only what the current settings actually need. Asking for video access from somebody
     * who turned videos off is how a permission dialog gets refused for the whole feature.
     */
    fun required(sdk: Int, includeVideos: Boolean): List<String> =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                if (includeVideos) add(Manifest.permission.READ_MEDIA_VIDEO)
                // Android 14 offers "select photos" in the same dialog, but only if it is
                // asked for alongside the full grant.
                if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun accessFrom(sdk: Int, includeVideos: Boolean, granted: Map<String, Boolean>): MediaAccess {
        val full = required(sdk, includeVideos)
            .filter { it != Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
        val held = full.count { granted[it] == true }

        return when {
            held == full.size -> MediaAccess.Granted
            held > 0 -> MediaAccess.Partial
            // "Allow all" grants this one too, which is why it is asked last: a full grant
            // must not be mistaken for a chosen handful.
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ->
                MediaAccess.Partial
            else -> MediaAccess.Denied
        }
    }

    /** What this device says right now. */
    fun check(context: Context, includeVideos: Boolean): MediaAccess = accessFrom(
        Build.VERSION.SDK_INT,
        includeVideos,
        required(Build.VERSION.SDK_INT, includeVideos).associateWith { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        },
    )
}
