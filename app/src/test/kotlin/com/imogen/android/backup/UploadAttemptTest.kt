package com.imogen.android.backup

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imogen.android.data.Account
import com.imogen.android.data.TokenSet
import com.imogen.sdk.ImogenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What one attempt leaves in the ledger, against real SQLite: the whole point of these is
 * which rows exist afterwards and what the attempt count on them reads, and nothing that
 * stubs the database can be wrong about that.
 */
@RunWith(AndroidJUnit4::class)
class UploadAttemptTest {

    private lateinit var database: BackupLedger
    private lateinit var uploads: UploadDao

    private val account = Account(
        id = "local-1",
        serverUrl = "https://photos.example.com",
        userId = "user-7",
        email = "someone@example.com",
        name = "Someone",
        clientId = "client",
        tokens = TokenSet("at", "rt", 0, 3600, ""),
    )

    private val media = LocalMedia(
        deviceAssetId = "android:external_primary:125",
        uri = Uri.parse("content://media/external/images/media/125"),
        displayName = "PXL_1.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        takenAtMillis = 1_700_000_000_000,
        isVideo = false,
        path = "/storage/emulated/0/DCIM/Camera/PXL_1.jpg",
    )

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BackupLedger::class.java,
        ).build()
        uploads = database.uploads()
    }

    @After
    fun close() = database.close()

    private suspend fun attemptsFor(assetId: String = media.deviceAssetId): Int? =
        uploads.failuresFor(account.backupKey).firstOrNull { it.deviceAssetId == assetId }?.attempts

    private suspend fun alreadyFailed(attempts: Int) = uploads.put(
        UploadRecord(
            backupKey = account.backupKey,
            deviceAssetId = media.deviceAssetId,
            assetId = null,
            uploadedAt = 1,
            attempts = attempts,
            lastError = "the server was down",
            displayName = media.displayName,
        ),
    )

    @Test
    fun `an upload that arrives settles the file`() = runTest {
        val outcome = recordUpload(uploads, account, media) { "server-1" }

        assertEquals(UploadOutcome.Uploaded, outcome)
        assertEquals(listOf(media.deviceAssetId), uploads.doneFor(account.backupKey))
    }

    /**
     * The bug this is here for. `CancellationException` extends `Exception` in Kotlin, so
     * the broad catch caught WorkManager stopping the pass and wrote it against the
     * photograph. Doze and a lost network are routine, and three of them mid-upload walked
     * a file to its limit — after which every future pass skips it silently.
     */
    @Test
    fun `being stopped mid-upload does not spend the photograph's attempts`() = runTest {
        alreadyFailed(attempts = 2)

        var rethrown = false
        try {
            recordUpload(uploads, account, media) { throw CancellationException("stopped") }
        } catch (_: CancellationException) {
            rethrown = true
        }

        // Rethrown, because the pass has to know it was stopped rather than read it as a
        // verdict on the file.
        assertTrue(rethrown)
        assertEquals(2, attemptsFor())
    }

    @Test
    fun `being stopped before anything failed writes no row at all`() = runTest {
        try {
            recordUpload(uploads, account, media) { throw CancellationException("stopped") }
        } catch (_: CancellationException) {
            // The assertion is what is not in the ledger.
        }

        assertEquals(emptyList<UploadRecord>(), uploads.failuresFor(account.backupKey))
        assertEquals(emptyList<String>(), uploads.doneFor(account.backupKey))
    }

    @Test
    fun `a dead grant is nobody's file's fault`() = runTest {
        val outcome = recordUpload(uploads, account, media) {
            throw ImogenException(401, "unauthorized", "token revoked")
        }

        assertEquals(UploadOutcome.Unauthorized, outcome)
        assertEquals(emptyList<UploadRecord>(), uploads.failuresFor(account.backupKey))
    }

    @Test
    fun `a server having a bad day does not spend the file's attempts either`() = runTest {
        alreadyFailed(attempts = 1)

        val outcome = recordUpload(uploads, account, media) {
            throw ImogenException(503, "unavailable", "try later")
        }

        assertEquals(UploadOutcome.Unavailable, outcome)
        assertEquals(1, attemptsFor())
    }

    @Test
    fun `a rejection counts up from what the file had already spent`() = runTest {
        alreadyFailed(attempts = 1)

        recordUpload(uploads, account, media) {
            throw ImogenException(415, "unsupported_media_type", "not a picture")
        }

        assertEquals(2, attemptsFor())
    }

    /**
     * The other door into the same hole. The SDK cannot replay a multipart body, so it
     * rethrows a dropped connection untouched — it arrives here as a plain IOException,
     * not as an ImogenException, and used to be filed against the photograph. Three flaky
     * passes and the file was settled out of the backup for good.
     */
    @Test
    fun `a dropped connection does not spend the photograph's attempts`() = runTest {
        alreadyFailed(attempts = 2)

        val outcome = recordUpload(uploads, account, media) {
            throw java.io.IOException("connection reset")
        }

        assertEquals(UploadOutcome.Unavailable, outcome)
        assertEquals(2, attemptsFor())
    }

    @Test
    fun `a dropped connection on a file never tried writes no row at all`() = runTest {
        recordUpload(uploads, account, media) { throw java.io.IOException("connection reset") }

        assertEquals(emptyList<UploadRecord>(), uploads.failuresFor(account.backupKey))
    }

    @Test
    fun `a file that cannot be read is not going to become readable on a timer`() = runTest {
        val unreadable = media.copy(path = null)

        val outcome = recordUpload(uploads, account, unreadable) {
            throw java.io.IOException("could not open")
        }

        assertEquals(UploadOutcome.Rejected, outcome)
        assertEquals(1, attemptsFor())
    }
}
