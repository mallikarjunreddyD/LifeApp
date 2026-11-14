package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SchemaThreeMigrationTest {
    @Test fun committedSchemaThreeWithAllPriorModulesMigratesWithoutChangingAnyRecordOrVectorByte() = runTest {
        val directory=Files.createTempDirectory("lifeapp-schema3").toFile()
        val sourceFile=File(directory,"source.db");val legacyFile=File(directory,"legacy.db")
        fun open(file: File)=Room.databaseBuilder<FoundationDatabase>(file.path).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).build()
        val source=open(sourceFile)
        val fixture=backupFixture().let { snapshot -> snapshot.copy(notebooks=emptyList(),notebookPages=emptyList(),
            strokes=snapshot.strokes.filter { ink->snapshot.notebookPages.none{it.metadata.id==ink.pageId} }) }
        try { RoomSnapshotRepository(source).replace(fixture) } finally { source.close() }
        val schemaFile=File("schemas/com.myjournal.data.FoundationDatabase/3.json")
        val schema=Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val driver=BundledSQLiteDriver()
        driver.open(sourceFile.path).use { old -> driver.open(legacyFile.path).use { legacy ->
            legacy.execSQL("PRAGMA foreign_keys=OFF")
            legacy.execSQL("BEGIN")
            schema.getValue("entities").jsonArray.forEach { entity ->
                val value=entity.jsonObject;val table=value.getValue("tableName").jsonPrimitive.content
                legacy.execSQL(value.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}",table))
                value["indices"]?.jsonArray?.forEach { index -> legacy.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}",table)) }
                val fields=value.getValue("fields").jsonArray.map { it.jsonObject }
                val columns=fields.map { it.getValue("columnName").jsonPrimitive.content }
                old.prepare("SELECT ${columns.joinToString { "`$it`" }} FROM `$table`").use { read ->
                    while(read.step()) legacy.prepare("INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})").use { insert ->
                        fields.forEachIndexed { i,field ->
                            if(read.isNull(i)) insert.bindNull(i+1)
                            else when(field.getValue("affinity").jsonPrimitive.content) {
                                "TEXT"->insert.bindText(i+1,read.getText(i));"INTEGER"->insert.bindLong(i+1,read.getLong(i))
                                "REAL"->insert.bindDouble(i+1,read.getDouble(i));"BLOB"->insert.bindBlob(i+1,read.getBlob(i))
                                else->error("Unsupported fixture type")
                            }
                        };insert.step()
                    }
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { legacy.execSQL(it.jsonPrimitive.content) }
            legacy.execSQL("PRAGMA user_version=3")
            legacy.execSQL("COMMIT")
        } }
        val migrated=open(legacyFile)
        try {
            assertEquals(SnapshotJson.encode(fixture),SnapshotJson.encode(RoomSnapshotRepository(migrated).capture()))
            val notebook=Notebook(newRecordMetadata(),"After upgrade")
            val page=NotebookPage(newRecordMetadata(),notebook.metadata.id,0,paper=PaperTemplate.DOTTED)
            RoomNotebookRepository(migrated).apply(NotebookEdit(notebook,listOf(page)))
            assertEquals(notebook,RoomNotebookRepository(migrated).all().single())
            assertEquals(fixture.strokes,RoomSnapshotRepository(migrated).capture().strokes)
        } finally { migrated.close();directory.deleteRecursively() }
    }
}
