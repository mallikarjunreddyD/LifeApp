package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class JournalArchiveRepositoryTest {
    @Test fun archivalIsTransactionalIdempotentAndRecoverableAfterReopenAndBackupRestore() = runTest {
        val directory=Files.createTempDirectory("lifeapp-page-trash").toFile();val path=File(directory,"app.db")
        fun open(file: File=path)=Room.databaseBuilder<FoundationDatabase>(file.path).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).build()
        var db=open()
        val date=LocalDate(2026,11,5);val entry=newRecordMetadata()
        val a=JournalPage(newRecordMetadata(),entry.id,0);val b=JournalPage(newRecordMetadata(),entry.id,1)
        val stroke=InkStroke(newRecordMetadata(),a.metadata.id,0,BrushTool.HIGHLIGHTER,0x552859A8,18f,listOf(InkPoint(200f,300f,0,0.4f),InkPoint(250f,350f,20,null)))
        try {
            var repo=RoomJournalRepository(db)
            repo.apply(PageEdit(date,a,listOf(stroke)));repo.apply(PageEdit(date,b))
            val before=repo.find(date)!!.pages.first()
            repo.apply(PageEdit(date,a,setArchived=true));val revision=repo.find(date)!!.metadata.revision
            repo.apply(PageEdit(date,a,setArchived=true));assertEquals(revision,repo.find(date)!!.metadata.revision)
            assertFailsWith<IllegalArgumentException>{repo.apply(PageEdit(date,b,setArchived=true))}
            // Stale page descriptor from a late gesture must not unarchive or redirect ink.
            val late=stroke.copy(metadata=newRecordMetadata(),order=1)
            repo.apply(PageEdit(date,a,put=listOf(late)))
            db.close();db=open();repo=RoomJournalRepository(db)
            val loaded=repo.find(date)!!;assertEquals(listOf(b.metadata.id),loaded.activePages.map{it.metadata.id})
            val archived=loaded.archivedPages.single();assertEquals(a.metadata.id,archived.metadata.id);assertEquals(0,archived.order)
            assertEquals(before.metadata.createdAt,archived.metadata.createdAt)
            assertEquals(listOf(stroke,late),repo.loadStrokes(a.metadata.id))
            val snapshot=RoomSnapshotRepository(db).capture()
            val archive=File(directory,"backup.zip");BackupArchive.write(archive,snapshot)
            val restored=open(File(directory,"restored.db"))
            try {
                RoomSnapshotRepository(restored).replace(BackupArchive.read(archive).snapshot)
                assertEquals(SnapshotJson.encode(snapshot),SnapshotJson.encode(RoomSnapshotRepository(restored).capture()))
                val restoredRepo=RoomJournalRepository(restored)
                assertTrue(restoredRepo.find(date)!!.archivedPages.single().archived)
                val pdf=snapshot.journalPdf(date,date);assertEquals(2,pdf.size);assertTrue(pdf.first().title.contains("archived"))
                assertEquals(listOf(stroke,late),pdf.first().strokes)
                restoredRepo.apply(PageEdit(date,a,setArchived=false))
                assertEquals(2,restoredRepo.find(date)!!.activePages.size)
                assertEquals(listOf(stroke,late),restoredRepo.loadStrokes(a.metadata.id))
            } finally {restored.close()}
            val c=JournalPage(newRecordMetadata(),entry.id,2);repo.apply(PageEdit(date,c))
            assertEquals(listOf(0,1,2),repo.find(date)!!.pages.map{it.order})
        } finally {db.close();directory.deleteRecursively()}
    }
    @Test fun originalPhaseThreeGoldenArchivesAndJsonRemainReadableWithAllPagesActive() = runTest {
        val root=File("src/jvmTest/resources/phase3")
        val old=BackupArchive.read(File(root,"schema4-backup.zip"))
        val json=SnapshotJson.decode(File(root,"schema4-records.json").readText())
        assertEquals(json,old.snapshot)
        assertTrue(old.snapshot.journals.flatMap{it.pages}.all{!it.archived})
        val db=Room.inMemoryDatabaseBuilder<FoundationDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            RoomSnapshotRepository(db).replace(old.snapshot)
            assertEquals(SnapshotJson.encode(old.snapshot),SnapshotJson.encode(RoomSnapshotRepository(db).capture()))
            val zip=File.createTempFile("lifeapp-upgraded", ".zip")
            try{BackupArchive.write(zip,RoomSnapshotRepository(db).capture());assertEquals(old.snapshot,BackupArchive.read(zip).snapshot)}finally{zip.delete()}
        } finally {db.close()}
    }
}
