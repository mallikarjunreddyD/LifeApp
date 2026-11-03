package com.myjournal.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS notebooks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT, defaultTemplate TEXT NOT NULL, archived INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL)""")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS notebook_pages (id TEXT NOT NULL PRIMARY KEY, notebookId TEXT NOT NULL, pageOrder INTEGER NOT NULL, title TEXT, paper TEXT NOT NULL, width REAL NOT NULL, height REAL NOT NULL, archived INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, FOREIGN KEY(notebookId) REFERENCES notebooks(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        connection.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS index_notebook_pages_notebookId_pageOrder ON notebook_pages(notebookId,pageOrder)""")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS notebook_ink_strokes (id TEXT NOT NULL PRIMARY KEY, pageId TEXT NOT NULL, strokeOrder INTEGER NOT NULL, tool TEXT NOT NULL, colorArgb INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, formatVersion INTEGER NOT NULL, FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        connection.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS index_notebook_ink_strokes_pageId_strokeOrder ON notebook_ink_strokes(pageId,strokeOrder)""")
    }
}
