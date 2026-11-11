package com.myjournal.domain

import kotlinx.datetime.LocalDate

/** Compares a backed-up local journal with the server's entry and page metadata. */
data class JournalImportInventory(
    val localEntries: Int,
    val localPages: Int,
    val localStrokes: Int,
    val serverEntries: Int,
    val localOnlyEntries: Int,
    val serverOnlyEntries: Int,
    val overlappingDates: List<LocalDate>,
    val reusedIdentityDates: List<LocalDate>,
) {
    companion object {
        fun compare(backup: JournalSnapshot, server: List<JournalEntry>): JournalImportInventory {
            fun JournalEntry.identities() = listOf(metadata.id) + pages.map { it.metadata.id }

            val local = backup.journals
            val localDates = local.map { it.date }.toSet()
            val serverDates = server.map { it.date }.toSet()
            val localIdentityDates = local.flatMap { entry -> entry.identities().map { it to entry.date } }.toMap()
            val serverIdentityDates = server.flatMap { entry -> entry.identities().map { it to entry.date } }.toMap()
            require(serverDates.size == server.size && serverIdentityDates.size == server.sumOf { it.pages.size + 1 }) {
                "The server journal inventory contains duplicate dates or identities."
            }

            val reused = localIdentityDates.keys.intersect(serverIdentityDates.keys)
            val moved = reused.filter { localIdentityDates.getValue(it) != serverIdentityDates.getValue(it) }
            val pages = local.flatMap { it.pages }.map { it.metadata.id }.toSet()
            return JournalImportInventory(
                localEntries = local.size,
                localPages = pages.size,
                localStrokes = backup.strokes.count { it.pageId in pages },
                serverEntries = server.size,
                localOnlyEntries = local.count { it.date !in serverDates && it.identities().none(serverIdentityDates::containsKey) },
                serverOnlyEntries = server.count { it.date !in localDates && it.identities().none(localIdentityDates::containsKey) },
                overlappingDates = localDates.intersect(serverDates).sorted(),
                reusedIdentityDates = moved.flatMap { listOf(localIdentityDates.getValue(it), serverIdentityDates.getValue(it)) }
                    .distinct().sorted(),
            )
        }
    }
}
