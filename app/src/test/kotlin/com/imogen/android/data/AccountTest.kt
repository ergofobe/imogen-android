package com.imogen.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenSetTest {

    private fun tokens(obtainedAt: Long, expiresIn: Long) =
        TokenSet("at", "rt", obtainedAt, expiresIn, "library:read")

    @Test
    fun `a fresh token is not expired`() {
        assertFalse(tokens(obtainedAt = 1_000_000, expiresIn = 3600).isExpired(1_000_000))
    }

    @Test
    fun `a token is refreshed a minute early, not a second late`() {
        val issued = 1_000_000L
        val set = tokens(obtainedAt = issued, expiresIn = 3600)

        // Fifty-nine minutes in: inside the skew, so it counts as expired already.
        assertTrue(set.isExpired(issued + 3_541_000))
        assertFalse(set.isExpired(issued + 3_539_000))
    }

    @Test
    fun `a token shorter than the skew is expired from the moment it arrives`() {
        val set = tokens(obtainedAt = 0, expiresIn = 30)

        assertTrue(set.isExpired(0))
    }
}

class AccountBookTest {

    private fun account(id: String, backup: Boolean = false) = Account(
        id = id,
        serverUrl = "https://$id.example.com",
        userId = "user-$id",
        email = "$id@example.com",
        name = id,
        clientId = "client-$id",
        tokens = TokenSet("at", "rt", 0, 3600, ""),
        backupEnabled = backup,
    )

    @Test
    fun `the active account is the one chosen`() {
        val book = AccountBook(listOf(account("a"), account("b")), activeAccountId = "b")

        assertEquals("b", book.active?.id)
    }

    @Test
    fun `an account that has been removed falls back to whatever is left`() {
        val book = AccountBook(listOf(account("a")), activeAccountId = "gone")

        assertEquals("a", book.active?.id)
    }

    @Test
    fun `no accounts means no active account`() {
        assertNull(AccountBook().active)
    }

    @Test
    fun `backup goes to every account that asked for it`() {
        val book = AccountBook(
            listOf(account("a", backup = true), account("b"), account("c", backup = true)),
        )

        assertEquals(listOf("a", "c"), book.backingUpTo.map { it.id })
    }

    @Test
    fun `the server label is the host, which is what distinguishes two accounts`() {
        assertEquals("a.example.com", account("a").serverLabel)
    }
}

class ServerUrlTest {

    @Test
    fun `a bare hostname becomes https`() {
        assertEquals("https://photos.example.com", normalizeServerUrl("photos.example.com"))
    }

    @Test
    fun `an explicit scheme is left alone`() {
        assertEquals("http://box.local:3000", normalizeServerUrl("http://box.local:3000"))
    }

    @Test
    fun `a trailing slash is removed, because every path is appended to this`() {
        assertEquals("https://photos.example.com", normalizeServerUrl(" photos.example.com/ "))
    }

    @Test
    fun `loopback is allowed over plain http, since that is where a test server is`() {
        assertEquals("http://localhost:3000", normalizeServerUrl("localhost:3000"))
        assertEquals("http://127.0.0.1:3000", normalizeServerUrl("127.0.0.1:3000"))
    }
}
