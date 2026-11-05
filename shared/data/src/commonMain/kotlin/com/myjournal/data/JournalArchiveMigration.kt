package com.myjournal.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Preserve IDs/order/metadata/point bytes; every existing page remains active. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE journal_pages ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
    }
}
