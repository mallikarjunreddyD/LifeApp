package com.myjournal.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JournalImportInventoryTest {
    private fun entry(day: Int): JournalEntry {
        val metadata = newRecordMetadata()
        return JournalEntry(metadata, LocalDate(2026, 11, day), listOf(JournalPage(newRecordMetadata(), metadata.id, 0)))
    }

    @Test fun inventoriesSeparateNewEntriesFromDatesAndIdentitiesThatNeedReview() {
        val localOnly = entry(1)
        val sameDate = entry(2)
        val sameIdentity = entry(3)
        val changedDate = sameIdentity.copy(date = LocalDate(2026, 11, 4))
        val archivedPage = JournalPage(newRecordMetadata(), localOnly.metadata.id, 1, archived = true)
        val stroke = InkStroke(newRecordMetadata(), archivedPage.metadata.id, 0, BrushTool.PEN, 0xff000000,
            3f, listOf(InkPoint(1f, 2f, 0)))
        val backup = JournalSnapshot(journals = listOf(localOnly.copy(pages = localOnly.pages + archivedPage), sameDate, sameIdentity),
            strokes = listOf(stroke))
        val actual = JournalImportInventory.compare(backup, listOf(entry(2), changedDate, entry(5)))
        assertEquals(JournalImportInventory(3, 4, 1, 3, 1, 1, listOf(sameDate.date),
            listOf(sameIdentity.date, changedDate.date)), actual)
    }

    @Test fun duplicateServerDatesAndPageIdentitiesInvalidateReview() {
        val first = entry(1)
        val second = entry(2)
        val reusedPage = second.pages.single().copy(metadata = first.pages.single().metadata)
        assertFailsWith<IllegalArgumentException> {
            JournalImportInventory.compare(JournalSnapshot(), listOf(first, entry(1)))
        }
        assertFailsWith<IllegalArgumentException> {
            JournalImportInventory.compare(JournalSnapshot(), listOf(first, second.copy(pages = listOf(reusedPage))))
        }
    }
}
