package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FoundationDatabaseTest {
    @Test
    fun metadataUpsertEmitsCommittedRevision() = runTest {
        val database = Room.inMemoryDatabaseBuilder<FoundationDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val row = MetadataRow(
                "550e8400-e29b-41d4-a716-446655440000",
                "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", 1, 1,
            )
            assertEquals(emptyList(), database.metadata().observeAll().first())
            database.metadata().save(row)
            assertEquals(listOf(row), database.metadata().observeAll().first())
            val revised = row.copy(updatedAt = "2026-11-03T01:00:00Z", revision = 2)
            database.metadata().save(revised)
            assertEquals(listOf(revised), database.metadata().observeAll().first())
        } finally {
            database.close()
        }
    }
}
