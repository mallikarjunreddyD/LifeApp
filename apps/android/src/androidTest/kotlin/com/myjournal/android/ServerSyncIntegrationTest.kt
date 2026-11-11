package com.myjournal.android

import android.util.Base64
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.myjournal.data.*
import com.myjournal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.uuid.Uuid

class ServerSyncIntegrationTest {
    @Test fun existingAndroidBackupAndOutboxExchangeWithBrowserOverHttps() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("lifeappServerOrigin")
        assumeTrue("Requires the isolated HTTPS server and browser harness", !origin.isNullOrBlank())
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(requireNotNull(arguments.getString("lifeappServerCertificate")), Base64.DEFAULT).inputStream(),
        )
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("disposable-test-ca", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val api = ServerApi(ServerEndpoint.parse(requireNotNull(origin))) {
            (it.openConnection() as HttpsURLConnection).apply { sslSocketFactory = tls.socketFactory }
        }
        val credential = api.login("android-bridge-owner", "disposable browser test password")
        val transport = api.transport(credential.session, credential.token)
        val directory = requireNotNull(app.cacheDir.resolve("sync-test-${Uuid.random()}").takeIf { it.mkdir() })
        val file = File(directory, "db")
        fun open() = Room.databaseBuilder<FoundationDatabase>(app, file.path)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        var database = open()
        try {
            api.ready()
            val journalId = newRecordMetadata()
            val journal = JournalEntry(journalId, LocalDate(2026, 11, 9),
                listOf(JournalPage(newRecordMetadata(), journalId.id, 0)))
            val originalBook = Notebook(newRecordMetadata(), "Existing Android notebook")
            val page = NotebookPage(newRecordMetadata(), originalBook.metadata.id, 0, paper = PaperTemplate.DOTTED)
            val stroke = InkStroke(newRecordMetadata(), page.metadata.id, 0, BrushTool.PEN, 0xFF000000, 3f,
                listOf(InkPoint(120f, 200f, 0, 0.4f), InkPoint(180f, 220f, 10, 0.8f)))
            val original = JournalSnapshot(journals = listOf(journal), notebooks = listOf(originalBook), notebookPages = listOf(page), strokes = listOf(stroke))
            val snapshots = RoomSnapshotRepository(database)
            snapshots.replace(original)
            val repository = RoomTodoRepository(database)
            val taskId = repository.add("Android offline import", LocalDate(2030, 11, 12))
            val backup = File(directory, "before-sync.zip")
            BackupArchive.write(backup, snapshots.capture())
            val saved = BackupArchive.read(backup).snapshot
            val journalEntries = api.journalEntries(credential.session, credential.token)
            assertTrue(journalEntries.isEmpty())
            val inventory = JournalImportInventory.compare(saved, journalEntries)
            assertEquals(1, inventory.localOnlyEntries)
            assertEquals(1, inventory.localPages)
            assertEquals(0, inventory.localStrokes)
            try {
                val stale = credential.session.copy(scope = credential.session.scope.copy(epoch = Uuid.random()))
                api.journalEntries(stale, credential.token)
                fail("Unexpected recovery epoch accepted for journal inventory")
            } catch (failure: ServerHttpFailure) {
                assertEquals(409, failure.status)
            }
            val firstPage = transport.pull(SyncCheckpoint(credential.session.scope))
            assertTrue(firstPage.entries.isEmpty())
            repository.bindEndpoint(credential.session.scope, api.endpoint, saved.todos, remoteHasHistory = false)

            // Commit remotely and deliberately lose the acknowledgment before reopening Room.
            val originalOp = repository.pending().single()
            val receipt = transport.submit(originalOp)
            database.close()
            database = open()
            val reopened = RoomTodoRepository(database)
            assertEquals(originalOp, reopened.pending().single())
            assertEquals(api.endpoint.origin, reopened.boundOrigin())
            val coordinator = TodoSyncCoordinator(reopened, transport)
            coordinator.synchronize()
            assertTrue(reopened.pending().isEmpty())
            assertEquals(receipt, transport.submit(originalOp))
            assertTrue(taskId in api.visibleTaskIds(credential.token))

            val browserId = withTimeout(90_000) {
                var found: Uuid? = null
                while (found == null) {
                    coordinator.synchronize()
                    found = reopened.localTasks().singleOrNull { it.title == "Browser created task" }?.metadata?.id
                    if (found == null) delay(400)
                }
                found
            }
            val overlap = JournalImportInventory.compare(saved, api.journalEntries(credential.session, credential.token))
            assertEquals(1, overlap.serverEntries)
            assertEquals(listOf(journal.date), overlap.overlappingDates)
            assertEquals(0, overlap.localOnlyEntries)

            reopened.edit(browserId, "Android edited browser task", LocalDate(2030, 11, 12))
            coordinator.synchronize()
            assertTrue(browserId in api.visibleTaskIds(credential.token))

            reopened.edit(taskId, "Android offline competing edit", LocalDate(2030, 11, 13))
            val deletion = originalOp.copy(id = Uuid.random(), deviceId = Uuid.random(), sequence = 1,
                parents = setOf(originalOp.id), record = originalOp.record.copy(deleted = true))
            transport.submit(deletion)
            coordinator.synchronize()
            assertEquals(setOf(false, true), reopened.conflicts(taskId).map { it.record.deleted }.toSet())
            assertFalse(taskId in api.visibleTaskIds(credential.token))
            val restored = RoomSnapshotRepository(database).capture()
            assertEquals(original.journals, restored.journals)
            assertEquals(original.notebooks, restored.notebooks)
            assertEquals(original.notebookPages, restored.notebookPages)
            assertEquals(stroke.metadata.id, restored.strokes.single().metadata.id)
            assertArrayEquals(StrokePointCodec.encode(stroke.points), StrokePointCodec.encode(restored.strokes.single().points))
            val wrong = transport.scope.copy(epoch = Uuid.random())
            try {
                transport.pull(SyncCheckpoint(wrong))
                fail("Unexpected recovery epoch accepted")
            } catch (failure: ServerHttpFailure) {
                assertEquals(409, failure.status)
            }
        } finally {
            database.close()
            directory.deleteRecursively()
            api.logout(credential.token)
        }
    }
}
