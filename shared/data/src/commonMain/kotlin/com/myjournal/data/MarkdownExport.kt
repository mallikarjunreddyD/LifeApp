package com.myjournal.data

import com.myjournal.domain.JournalSnapshot

object MarkdownExport {
    private fun text(value: String) = value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ").replace("#", "\\#").replace("*", "\\*")
    fun encode(snapshot: JournalSnapshot) = buildString {
        snapshot.validate()
        appendLine("# LifeApp text and metadata export")
        appendLine("Handwriting is not converted to text. This Markdown file contains titles and metadata only; it is not a backup. Export PDF for appearance or native vectors/full backup for editable ink.")
        appendLine()
        snapshot.journals.sortedBy { it.date }.forEach { entry ->
            appendLine("## Daily Journal ${entry.date}")
            entry.pages.sortedBy { it.order }.forEach { appendLine("- Page ${it.order + 1}: ${it.metadata.id} · ${snapshot.strokes.count { s -> s.pageId == it.metadata.id }} strokes") }
        }
        snapshot.planners.sortedBy { it.month }.forEach { appendLine("## Monthly Planner ${it.month}"); appendLine("Surface ${it.metadata.id} · layout ${it.layout.version}") }
        snapshot.habits.sortedBy { it.month }.forEach { ledger ->
            appendLine("## Habits ${ledger.month}")
            ledger.habits.sortedBy { it.order }.forEach { appendLine("- ${text(it.name)}${if (it.archived) " (archived)" else ""}: ${ledger.count(it.metadata.id)} / ${ledger.month.days} days; ID ${it.metadata.id}; category ${text(it.category ?: "")}; target ${it.target ?: "none"}") }
        }
        snapshot.notebooks.forEach { book ->
            appendLine("## Notebook ${text(book.name)}${if (book.archived) " (archived)" else ""}")
            appendLine(text(book.description ?: ""))
            snapshot.notebookPages.filter { it.notebookId == book.metadata.id }.sortedBy { it.order }.forEach {
                appendLine("- ${it.order + 1}: ${text(it.title ?: "Untitled")} · ${it.paper} · ${it.metadata.id}${if (it.archived) " (archived)" else ""}")
            }
        }
        appendLine("## Record metadata")
        snapshot.records.forEach { appendLine("- ${it.id} · schema ${it.schemaVersion} · revision ${it.revision} · created ${it.createdAt} · updated ${it.updatedAt}") }
    }
}
