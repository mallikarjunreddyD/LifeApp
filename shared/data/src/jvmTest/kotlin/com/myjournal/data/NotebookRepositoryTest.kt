package com.myjournal.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class NotebookRepositoryTest {
    @Test fun realNotebookTransactionsReopenAndPreserveTemplatesOrderingArchivesAndStrokeIdentity() = runTest {
        val directory=Files.createTempDirectory("lifeapp-notebooks").toFile();val file=File(directory,"app.db")
        fun open()=Room.databaseBuilder<FoundationDatabase>(file.path).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        var db=open()
        try {
            val fixture=backupFixture();val book=fixture.notebooks.first { !it.archived }
            val pages=fixture.notebookPages.filter { it.notebookId==book.metadata.id }.sortedBy { it.order }
            val strokes=fixture.strokes.filter { s -> pages.any { it.metadata.id==s.pageId } }
            var repo=RoomNotebookRepository(db);repo.apply(NotebookEdit(book,pages,strokes))
            val renamed=book.copy(name="Renamed",metadata=book.metadata.revised(book.metadata.updatedAt))
            val reordered=pages.reversed().mapIndexed { i,p->p.copy(order=i,paper=PaperTemplate.GRID,archived=i==0,metadata=p.metadata.revised(p.metadata.updatedAt)) }
            repo.apply(NotebookEdit(renamed,reordered))
            db.close();db=open();repo=RoomNotebookRepository(db)
            assertEquals(listOf(renamed),repo.all());assertEquals(reordered,repo.pages(book.metadata.id))
            for(page in reordered) assertEquals(strokes.filter{it.pageId==page.metadata.id}.sortedBy{it.order},repo.loadStrokes(page.metadata.id))
            assertFailsWith<IllegalArgumentException> { repo.apply(NotebookEdit(renamed,reordered.drop(1))) }
            val other=book.copy(metadata=newRecordMetadata(),name="Other")
            assertFailsWith<IllegalArgumentException> { repo.apply(NotebookEdit(other,reordered.map{it.copy(notebookId=other.metadata.id)})) }
            assertEquals(listOf(renamed),repo.all())
        } finally {db.close();directory.deleteRecursively()}
    }
}
