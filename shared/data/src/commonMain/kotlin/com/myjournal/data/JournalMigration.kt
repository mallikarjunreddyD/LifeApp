package com.myjournal.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS journal_entries (id TEXT NOT NULL, date TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, PRIMARY KEY(id))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_entries_date ON journal_entries (date)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS journal_pages (id TEXT NOT NULL, entryId TEXT NOT NULL, pageOrder INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL, paper TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(entryId) REFERENCES journal_entries(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_pages_entryId_pageOrder ON journal_pages (entryId, pageOrder)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS ink_strokes (id TEXT NOT NULL, pageId TEXT NOT NULL, strokeOrder INTEGER NOT NULL, tool TEXT NOT NULL, colorArgb INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, formatVersion INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(pageId) REFERENCES journal_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ink_strokes_pageId_strokeOrder ON ink_strokes (pageId, strokeOrder)")
    }
}
