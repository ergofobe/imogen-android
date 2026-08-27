package com.imogen.android.ui

import com.imogen.android.ui.common.Selection
import com.imogen.android.ui.common.resolvedCount
import com.imogen.android.ui.common.restoredMessage
import com.imogen.android.ui.common.toFilter
import com.imogen.android.ui.common.trashedMessage
import com.imogen.sdk.AssetFilter
import com.imogen.sdk.AssetQuery
import com.imogen.sdk.AssetType
import com.imogen.sdk.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a selection resolves to before anything destructive happens to it.
 *
 * This is the arithmetic behind the number in the trash confirmation and behind the body
 * of the request that follows it, so it is tested rather than eyeballed.
 */
class SelectionTest {

    @Test
    fun `ticking and unticking ids`() {
        val empty = Selection.Ids()
        assertTrue(empty.isEmpty)

        val one = empty.toggled("a")
        assertFalse(one.isEmpty)
        assertTrue(one.holds("a"))
        assertFalse(one.holds("b"))

        assertTrue(one.toggled("a").isEmpty)
    }

    @Test
    fun `a by-query selection holds everything it has not been told to leave out`() {
        val all = Selection.Matching(AssetFilter(favorite = true))
        assertTrue(all.holds("a"))

        val fewer = all.toggled("a")
        assertFalse(fewer.holds("a"))
        assertTrue(fewer.holds("b"))

        assertTrue(fewer.toggled("a").holds("a"))
    }

    @Test
    fun `ids go over as a list and never as a query`() {
        val selection = Selection.Ids(setOf("a", "b")).asAssetSelection()
        assertEquals(listOf("a", "b"), selection.assetIds?.sorted())
        assertNull(selection.query)
        // The SDK refuses a selection that is both, so this must never carry `except`.
        assertNull(selection.except)
        selection.validate()
    }

    @Test
    fun `a query goes over as a filter with the unticked ones excepted`() {
        val filter = AssetFilter(personId = "p1")
        val selection = Selection.Matching(filter, setOf("a")).asAssetSelection()

        assertEquals(filter, selection.query)
        assertEquals(listOf("a"), selection.except)
        assertNull(selection.assetIds)
        selection.validate()
    }

    @Test
    fun `an untouched query carries no except at all`() {
        val selection = Selection.Matching(AssetFilter()).asAssetSelection()
        assertNull(selection.except)
        selection.validate()
    }

    @Test
    fun `a count is the ids, or the total less what was unticked`() {
        assertEquals(2L, Selection.Ids(setOf("a", "b")).resolvedCount(null))

        val matching = Selection.Matching(AssetFilter(), setOf("a", "b"))
        assertEquals(10L, matching.resolvedCount(12L))
        // Nothing counted yet: the interface says so rather than guessing.
        assertNull(matching.resolvedCount(null))
    }

    @Test
    fun `a count never goes below nought`() {
        // The library can shrink between the count and the unticking; a negative number in
        // a confirmation is worse than a stale one.
        val matching = Selection.Matching(AssetFilter(), setOf("a", "b", "c"))
        assertEquals(0L, matching.resolvedCount(1L))
    }

    @Test
    fun `a query keeps every constraint it was browsing under`() {
        val query = AssetQuery(
            cursor = "c",
            limit = 120,
            q = "beach",
            type = AssetType.VIDEO,
            albumId = "al",
            personId = "p",
            favorite = true,
            archived = false,
            trashed = true,
            takenAfter = "2019-01-01T00:00:00Z",
            takenBefore = "2019-12-31T23:59:59Z",
            bbox = "1,2,3,4",
            order = SortOrder.DESC,
        )

        assertEquals(
            AssetFilter(
                q = "beach",
                type = AssetType.VIDEO,
                albumId = "al",
                personId = "p",
                favorite = true,
                archived = false,
                trashed = true,
                takenAfter = "2019-01-01T00:00:00Z",
                takenBefore = "2019-12-31T23:59:59Z",
                bbox = "1,2,3,4",
            ),
            query.toFilter(),
        )
    }

    @Test
    fun `paging and ordering are not constraints and do not survive`() {
        // A filter that carried the cursor would act on one page and call it the library.
        assertEquals(AssetFilter(), AssetQuery(cursor = "c", limit = 500).toFilter())
    }

    @Test
    fun `an outcome is reported in the singular, the plural, or not at all`() {
        assertEquals("Nothing to move", trashedMessage(0))
        assertEquals("1 photo moved to the trash", trashedMessage(1))
        assertTrue(trashedMessage(12431).startsWith("12"))
        assertTrue(trashedMessage(12431).endsWith(" photos moved to the trash"))

        assertEquals("Nothing to put back", restoredMessage(0))
        assertEquals("1 photo put back", restoredMessage(1))
    }
}
