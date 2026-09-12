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
    fun `a file recorded under both keys survives being adopted`() = runTest {
        // What a phone looks like if it signed out and back in before the fix: rows under
        // the old local id, and rows a later pass wrote under the key. The primary key
        // collides, and losing the row to it would re-upload the file.
        val account = account("local-2")
        uploads.put(uploaded("local-1", "asset-1"))
        uploads.put(uploaded(account.backupKey, "asset-1"))

        adoptStableKeys(listOf(account.copy(id = "local-1"))) { from, to ->
            uploads.reassign(from, to)
        }

        assertEquals(listOf("asset-1"), uploads.doneFor(account.backupKey))
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
