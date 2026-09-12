package com.imogen.android.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imogen.android.data.Account
import com.imogen.android.data.TokenSet
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ledger against real SQLite, because the whole bug was about which column value a
 * query matches on and nothing that stubs the database can be wrong about that.
 */
@RunWith(AndroidJUnit4::class)
class BackupLedgerTest {

    private lateinit var database: BackupLedger
    private lateinit var uploads: UploadDao

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

    private fun account(id: String) = Account(
        id = id,
        serverUrl = "https://photos.example.com",
        userId = "user-7",
        email = "someone@example.com",
        name = "Someone",
        clientId = "client",
        tokens = TokenSet("at", "rt", 0, 3600, ""),
    )

    private fun uploaded(key: String, asset: String) = UploadRecord(
        backupKey = key,
        deviceAssetId = asset,
        assetId = "server-$asset",
        uploadedAt = 1,
    )

    @Test
    fun `signing out and back in resumes, because the key outlives the local id`() = runTest {
        val before = account("local-1")
        uploads.put(uploaded(before.backupKey, "asset-1"))
        uploads.put(uploaded(before.backupKey, "asset-2"))

        // Signing out drops the account row; signing back in mints a brand new local id.
        val after = account("local-2")

        assertEquals(listOf("asset-1", "asset-2"), uploads.doneFor(after.backupKey).sorted())
    }

    @Test
    fun `a different user on the same server does not inherit the backup`() = runTest {
        uploads.put(uploaded(account("local-1").backupKey, "asset-1"))

        val somebodyElse = account("local-2").copy(userId = "user-8")

        assertEquals(emptyList<String>(), somebodyElse.let { uploads.doneFor(it.backupKey) })
    }

    @Test
    fun `records an earlier version wrote under the local id are adopted`() = runTest {
        val account = account("local-1")
        uploads.put(uploaded(account.id, "asset-1"))
        uploads.put(uploaded(account.id, "asset-2"))

        adoptStableKeys(listOf(account)) { from, to -> uploads.reassign(from, to) }

        assertEquals(listOf("asset-1", "asset-2"), uploads.doneFor(account.backupKey).sorted())
        assertEquals(emptyList<String>(), uploads.doneFor(account.id))
    }

    @Test
    fun `adopting twice is the same as adopting once`() = runTest {
        val account = account("local-1")
        uploads.put(uploaded(account.id, "asset-1"))

        repeat(2) {
            adoptStableKeys(listOf(account)) { from, to -> uploads.reassign(from, to) }
        }

        assertEquals(listOf("asset-1"), uploads.doneFor(account.backupKey))
    }

    @Test
    fun `a key collision keeps the row being moved, not the one it lands on`() = runTest {
        // `update or replace` reads as though the destination wins. It does not: SQLite
        // deletes the destination row and keeps the one being moved. Pinned here because
        // the whole of `reassign`'s safety is that the destination is empty when it runs,
        // and a future change that let it be non-empty would be discarding live rows —
        // here, settling a photograph that had actually arrived.
        val key = account("local-2").backupKey
        uploads.put(
            UploadRecord(
                backupKey = "local-1",
                deviceAssetId = "asset-1",
                assetId = null,
                uploadedAt = 1,
                attempts = MAX_UPLOAD_ATTEMPTS,
                lastError = "rejected",
            ),
        )
        uploads.put(uploaded(key, "asset-1"))

        uploads.reassign("local-1", key)

        assertEquals(emptyList<String>(), uploads.doneFor(key))
        assertEquals(listOf("asset-1"), uploads.failuresFor(key).map { it.deviceAssetId })
    }

    @Test
    fun `forgetting an account takes its rows and leaves everyone else's`() = runTest {
        val mine = account("local-1")
        val yours = account("local-2").copy(userId = "user-8")
        uploads.put(uploaded(mine.backupKey, "asset-1"))
        uploads.put(uploaded(yours.backupKey, "asset-1"))

        uploads.clearFor(mine.backupKey)

        assertEquals(emptyList<String>(), uploads.doneFor(mine.backupKey))
        assertEquals(listOf("asset-1"), uploads.doneFor(yours.backupKey))
    }
}
