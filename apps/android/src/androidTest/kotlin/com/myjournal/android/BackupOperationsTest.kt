package com.myjournal.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.myjournal.data.*
import com.myjournal.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BackupOperationsTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val app get()=rule.activity.application as JournalApplication
    private fun complete(operations: DataOperations) {
        runBlocking { withTimeout(30_000) { while(operations.state.value.busy) delay(20) } }
        assertFalse(operations.state.value.message,operations.state.value.error)
    }
    @Test fun settingsCreateDocumentContractProducesVerifiedFullBackup() {
        val target=File.createTempFile("lifeapp-picked", ".zip",app.cacheDir)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val monitor=instrumentation.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/zip") },Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(target))),true)
        try {
            rule.onNodeWithContentDescription("Open Settings").performClick()
            rule.onNodeWithText("Create full local backup").performClick()
            rule.waitUntil(30_000){monitor.hits>0 && !app.operations.state.value.busy && app.operations.state.value.message?.startsWith("Backup created")==true}
            assertFalse(app.operations.state.value.error)
            assertTrue(BackupArchive.read(target).counts.isNotEmpty())
        } finally { instrumentation.removeMonitor(monitor);target.delete() }
    }
    @Test fun selectedFileValidationCleanRestoreRecoveryAndCacheRebuildUseVerifiedRecords() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder<FoundationDatabase>(app).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        val backup=File.createTempFile("lifeapp-input", ".zip",app.cacheDir)
        val recovery=File.createTempFile("lifeapp-recovery", ".zip",app.cacheDir)
        val broken=File.createTempFile("lifeapp-broken", ".zip",app.cacheDir)
        try {
            val book=Notebook(newRecordMetadata(),"Restored Research")
            val page=NotebookPage(newRecordMetadata(),book.metadata.id,0,paper=PaperTemplate.DOTTED)
            val stroke=InkStroke(newRecordMetadata(),page.metadata.id,0,BrushTool.PEN,0xFF000000,3f,listOf(InkPoint(120f,200f,0,0.4f),InkPoint(180f,220f,10,0.8f)))
            val incoming=JournalSnapshot(notebooks=listOf(book),notebookPages=listOf(page),strokes=listOf(stroke))
            BackupArchive.write(backup,incoming)
            val repo=RoomSnapshotRepository(db);val operations=DataOperations(app,repo)
            rule.runOnIdle{operations.inspectRestore(Uri.fromFile(backup))};complete(operations)
            assertFalse(operations.state.value.preview!!.replacesData)
            val before=app.generation.value
            rule.runOnIdle{operations.restore(null)};complete(operations)
            assertTrue(app.generation.value>before)
            assertEquals(SnapshotJson.encode(incoming),SnapshotJson.encode(repo.capture()))
            val other=Notebook(newRecordMetadata(),"Existing original")
            val original=JournalSnapshot(notebooks=listOf(other));repo.replace(original)
            rule.runOnIdle{operations.inspectRestore(Uri.fromFile(backup))};complete(operations)
            assertTrue(operations.state.value.preview!!.replacesData)
            rule.runOnIdle{operations.restore(null)}
            withTimeout(30_000){while(operations.state.value.busy)delay(20)}
            assertTrue(operations.state.value.error)
            assertEquals(SnapshotJson.encode(original),SnapshotJson.encode(repo.capture()))
            rule.runOnIdle{operations.restore(Uri.fromFile(recovery))};complete(operations)
            assertEquals(SnapshotJson.encode(original),SnapshotJson.encode(BackupArchive.read(recovery).snapshot))
            assertEquals(SnapshotJson.encode(incoming),SnapshotJson.encode(repo.capture()))
            broken.writeText("corrupt archive")
            rule.runOnIdle{operations.inspectRestore(Uri.fromFile(broken))}
            withTimeout(30_000){while(operations.state.value.busy)delay(20)}
            assertTrue(operations.state.value.error)
            assertEquals(SnapshotJson.encode(incoming),SnapshotJson.encode(repo.capture()))
        } finally {db.close();backup.delete();recovery.delete();broken.delete()}
    }
}
