package com.imogen.android.backup

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission names are compile-time string constants, so they inline into this test
 * and none of the android.jar stubs are ever called. The API level is a parameter for the
 * same reason: `Build.VERSION.SDK_INT` reads zero off the stub.
 */
class MediaPermissionTest {

    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val video = Manifest.permission.READ_MEDIA_VIDEO
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val storage = Manifest.permission.READ_EXTERNAL_STORAGE

    @Test
    fun `before Android 13 there is one permission for everything`() {
        assertEquals(listOf(storage), MediaPermission.required(26, includeVideos = true))
        assertEquals(listOf(storage), MediaPermission.required(32, includeVideos = false))
    }

    @Test
    fun `Android 13 splits the roll by media type`() {
        assertEquals(listOf(images, video), MediaPermission.required(33, includeVideos = true))
        assertEquals(listOf(images), MediaPermission.required(33, includeVideos = false))
    }

    @Test
    fun `Android 14 asks for partial access alongside the full grant`() {
        assertEquals(
            listOf(images, video, selected),
            MediaPermission.required(34, includeVideos = true),
        )
        assertEquals(
            listOf(images, selected),
            MediaPermission.required(34, includeVideos = false),
        )
    }

    @Test
    fun `videos are not asked for when videos are not being backed up`() {
        // Asking for more than the feature needs is how a permission dialog gets refused.
        assertEquals(false, MediaPermission.required(34, includeVideos = false).contains(video))
    }

    @Test
    fun `the old storage permission grants everything or nothing`() {
        assertEquals(
            MediaAccess.Granted,
            MediaPermission.accessFrom(30, includeVideos = true, granted = mapOf(storage to true)),
        )
        assertEquals(
            MediaAccess.Denied,
            MediaPermission.accessFrom(30, includeVideos = true, granted = mapOf(storage to false)),
        )
    }

    @Test
    fun `a full grant reads as granted`() {
        assertEquals(
            MediaAccess.Granted,
            MediaPermission.accessFrom(
                34,
                includeVideos = true,
                // "Allow all" grants the partial permission too. It must not be mistaken
                // for the partial case just because it is present.
                granted = mapOf(images to true, video to true, selected to true),
            ),
        )
    }

    @Test
    fun `choosing a handful of photographs reads as partial, not denied`() {
        assertEquals(
            MediaAccess.Partial,
            MediaPermission.accessFrom(
                34,
                includeVideos = true,
                granted = mapOf(images to false, video to false, selected to true),
            ),
        )
    }

    @Test
    fun `partial access does not exist before Android 14`() {
        // The permission cannot be held on 33, and treating a stray true as partial access
        // would have the screen offer to widen a selection that was never made.
        assertEquals(
            MediaAccess.Denied,
            MediaPermission.accessFrom(
                33,
                includeVideos = false,
                granted = mapOf(images to false, selected to true),
            ),
        )
    }

    @Test
    fun `a refusal reads as denied`() {
        assertEquals(
            MediaAccess.Denied,
            MediaPermission.accessFrom(
                34,
                includeVideos = false,
                granted = mapOf(images to false, selected to false),
            ),
        )
    }

    @Test
    fun `images without video is not a full grant while videos are being backed up`() {
        // Half the roll is not the whole roll, and the screen should say so rather than
        // leaving somebody to notice their videos never arrived. Partial rather than
        // denied because the photographs really are readable.
        assertEquals(
            MediaAccess.Partial,
            MediaPermission.accessFrom(
                34,
                includeVideos = true,
                granted = mapOf(images to true, video to false, selected to false),
            ),
        )
    }

    @Test
    fun `images without video is a full grant when videos are not wanted`() {
        assertEquals(
            MediaAccess.Granted,
            MediaPermission.accessFrom(
                34,
                includeVideos = false,
                granted = mapOf(images to true, video to false, selected to false),
            ),
        )
    }

    @Test
    fun `an absent key counts as ungranted`() {
        assertEquals(
            MediaAccess.Denied,
            MediaPermission.accessFrom(34, includeVideos = true, granted = emptyMap<String, Boolean>()),
        )
    }
}
