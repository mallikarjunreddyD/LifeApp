package com.myjournal.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS planner_pages (id TEXT NOT NULL, month TEXT NOT NULL, layoutVersion INTEGER NOT NULL, rows INTEGER NOT NULL, width REAL NOT NULL, headerHeight REAL NOT NULL, cellHeight REAL NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, PRIMARY KEY(id))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planner_pages_month ON planner_pages (month)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS planner_ink_strokes (id TEXT NOT NULL, surfaceId TEXT NOT NULL, cellDay INTEGER NOT NULL, strokeOrder INTEGER NOT NULL, tool TEXT NOT NULL, colorArgb INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, formatVersion INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(surfaceId) REFERENCES planner_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planner_ink_strokes_surfaceId_strokeOrder ON planner_ink_strokes (surfaceId, strokeOrder)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS habit_ledgers (id TEXT NOT NULL, month TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, PRIMARY KEY(id))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habit_ledgers_month ON habit_ledgers (month)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS habit_definitions (id TEXT NOT NULL, ledgerId TEXT NOT NULL, name TEXT NOT NULL, displayOrder INTEGER NOT NULL, category TEXT, colorArgb INTEGER, target INTEGER, archived INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(ledgerId) REFERENCES habit_ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habit_definitions_ledgerId_displayOrder ON habit_definitions (ledgerId, displayOrder)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS habit_completions (id TEXT NOT NULL, habitId TEXT NOT NULL, date TEXT NOT NULL, checked INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(habitId) REFERENCES habit_definitions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_habit_completions_habitId_date ON habit_completions (habitId, date)")
    }
}
