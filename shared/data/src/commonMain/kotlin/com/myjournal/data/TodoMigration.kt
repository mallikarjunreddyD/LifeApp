package com.myjournal.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS todos (
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, deadline TEXT NOT NULL,
                completedAt TEXT, deleted INTEGER NOT NULL, createdAt TEXT NOT NULL,
                updatedAt TEXT NOT NULL, revision INTEGER NOT NULL, schemaVersion INTEGER NOT NULL
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_todos_deleted_completedAt_deadline ON todos(deleted, completedAt, deadline)")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS todo_operations (
                id TEXT NOT NULL PRIMARY KEY, deviceId TEXT NOT NULL, sequence INTEGER NOT NULL,
                recordId TEXT NOT NULL, parents TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_todo_operations_recordId ON todo_operations(recordId)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_todo_operations_deviceId_sequence ON todo_operations(deviceId, sequence)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_todo_operations_pending ON todo_operations(pending)")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS todo_sync_device (
                slot INTEGER NOT NULL PRIMARY KEY, id TEXT NOT NULL, sequence INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
