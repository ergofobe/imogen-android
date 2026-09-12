package com.imogen.android.data

import kotlinx.serialization.encodeToString
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

class PendingTest {

    /**
     * A sign-in begun by the previous build wrote no `resource`, and the browser can come
     * back to this one mid-flow. Decoding has to survive that: the alternative is a
     * redirect that reports no sign-in is waiting, which looks like nothing happened.
     */
    @Test
    fun `a record written before the resource indicator still decodes`() {
        val legacy = """
            {"serverUrl":"https://photos.example.com","clientId":"client-1",
             "codeVerifier":"verifier","state":"state","redirectUri":"imogen://oauth"}
        """.trimIndent()

        val pending = pendingJson.decodeFromString<Pending>(legacy)

        assertNull(pending.resource)
        assertEquals("client-1", pending.clientId)
    }

    @Test
    fun `a resource survives the round trip through storage`() {
        val pending = Pending(
            serverUrl = "https://photos.example.com",
            clientId = "client-1",
            codeVerifier = "verifier",
            state = "state",
            redirectUri = "imogen://oauth",
            resource = "https://photos.example.com/",
        )

        val decoded = pendingJson.decodeFromString<Pending>(pendingJson.encodeToString(pending))

        assertEquals(pending, decoded)
    }
}

class TokenPrecedenceTest {

    private fun tokens(accessToken: String, obtainedAt: Long) =
        TokenSet(accessToken, "rt-$accessToken", obtainedAt, 3600, "library:read")

    /**
     * The regression that cost a grant: a backup pass reads the account once and hands
     * that same copy to `sessionFor` for every file, so the copy goes stale the moment the
     * pass refreshes. Taking it would put the retired refresh token back in play.
     */
    @Test
    fun `an older token set does not displace the one in hand`() {
        val held = tokens("new", obtainedAt = 2_000)
        val stale = tokens("old", obtainedAt = 1_000)

        assertEquals(held, newerOf(held, stale))
    }

    /** A fresh sign-in must still win, or the account would be stuck on dead tokens. */
    @Test
    fun `a newer token set is taken`() {
        val held = tokens("old", obtainedAt = 1_000)
        val signedInAgain = tokens("new", obtainedAt = 2_000)

        assertEquals(signedInAgain, newerOf(held, signedInAgain))
    }

    /** Same instant, so neither is demonstrably newer: keep what is already in use. */
    @Test
    fun `a token set of the same age keeps the one in hand`() {
        val held = tokens("held", obtainedAt = 1_000)
        val offered = tokens("offered", obtainedAt = 1_000)

        assertEquals(held, newerOf(held, offered))
    }
}
