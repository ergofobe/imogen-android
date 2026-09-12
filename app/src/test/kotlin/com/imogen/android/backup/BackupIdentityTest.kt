package com.imogen.android.backup

import com.imogen.android.data.Account
import com.imogen.android.data.TokenSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupIdentityTest {

    private fun account(id: String, server: String = "https://photos.example.com", user: String = "user-7") =
        Account(
            id = id,
            serverUrl = server,
            userId = user,
            email = "someone@example.com",
            name = "Someone",
            clientId = "client",
            tokens = TokenSet("at", "rt", 0, 3600, ""),
        )

    @Test
    fun `records written under the local id are moved onto the stable key`() = runTest {
        val moves = mutableListOf<Pair<String, String>>()

        adoptStableKeys(listOf(account("local-1"))) { from, to -> moves += from to to }

        assertEquals(
            listOf("local-1" to "https://photos.example.com|user-7"),
            moves,
        )
    }

    @Test
    fun `every account is brought across, not just the first`() = runTest {
        val moves = mutableListOf<Pair<String, String>>()

        adoptStableKeys(
            listOf(
                account("local-1", server = "https://home.example.com"),
                account("local-2", server = "https://club.example.com"),
            ),
        ) { from, to -> moves += from to to }

        assertEquals(listOf("local-1", "local-2"), moves.map { it.first })
    }

    @Test
    fun `a second pass moves nothing, because the id no longer names any record`() = runTest {
        // What the ledger looks like once the first pass has run: the rows are under the
        // key, and the only thing that could match the old id is nothing at all.
        val ledger = mutableMapOf("https://photos.example.com|user-7" to "row")
        val account = account("local-1")

        repeat(2) {
            adoptStableKeys(listOf(account)) { from, to ->
                ledger.remove(from)?.let { row -> ledger[to] = row }
            }
        }

        assertEquals(mapOf("https://photos.example.com|user-7" to "row"), ledger)
    }

    @Test
    fun `an account already keyed by its own key is left alone`() = runTest {
        val key = account("local-1").backupKey
        val moves = mutableListOf<Pair<String, String>>()

        adoptStableKeys(listOf(account(key))) { from, to -> moves += from to to }

        assertTrue(moves.isEmpty())
    }
}
