package com.myjournal.domain

/** Logical keys match the server's uniqueness and conflict boundaries. */
data class EditableImportKey(val kind: String, val key: String)

data class EditableImportRecord(val key: EditableImportKey, val snapshot: JournalSnapshot)

/** Split a verified backup into one portable record per archive, preserving page and stroke ownership. */
fun JournalSnapshot.editableImportRecords(): List<EditableImportRecord> {
    val bySurface = strokes.groupBy { it.pageId }
    val pagesByBook = notebookPages.groupBy { it.notebookId }
    return buildList {
        for (entry in journals) {
            add(EditableImportRecord(EditableImportKey("journal", entry.date.toString()),
                JournalSnapshot(journals = listOf(entry), strokes = entry.pages.flatMap { bySurface[it.metadata.id].orEmpty() })))
        }
        for (planner in planners) {
            add(EditableImportRecord(EditableImportKey("planner", planner.month.toString()),
                JournalSnapshot(planners = listOf(planner), strokes = bySurface[planner.metadata.id].orEmpty())))
        }
        for (ledger in habits) {
            add(EditableImportRecord(EditableImportKey("habits", ledger.month.toString()), JournalSnapshot(habits = listOf(ledger))))
        }
        for (book in notebooks) {
            val pages = pagesByBook[book.metadata.id].orEmpty()
            add(EditableImportRecord(EditableImportKey("notebook", book.metadata.id.toString()),
                JournalSnapshot(notebooks = listOf(book), notebookPages = pages,
                    strokes = pages.flatMap { bySurface[it.metadata.id].orEmpty() })))
        }
    }
}
