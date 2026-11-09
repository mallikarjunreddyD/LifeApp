package com.myjournal.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS todo_sync_checkpoint (
                slot INTEGER NOT NULL PRIMARY KEY,
                dataset TEXT NOT NULL,
                epoch TEXT NOT NULL,
                sequence INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE todo_sync_checkpoint ADD COLUMN origin TEXT")
        connection.execSQL("ALTER TABLE todo_operations ADD COLUMN rejected INTEGER NOT NULL DEFAULT 0")
    }
}
