package com.myjournal.android

import android.util.Base64
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.myjournal.data.*
import com.myjournal.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
            val target = EditableImportQueue.Target(api.endpoint.origin, credential.session.scope)
            val queueDirectory = directory.resolve("editable")
            val approved = saved.editableImportRecords().filter { it.key.kind == "notebook" }
            EditableImportQueue(queueDirectory).enqueue(target, approved)
            val reopenedQueue = EditableImportQueue(queueDirectory)
            val pending = requireNotNull(reopenedQueue.load())
            assertEquals(target, pending.target)
            val recordFile = pending.files.single()
            val version = Uuid.parse(recordFile.nameWithoutExtension)
            val bytes = recordFile.readBytes()
            val sha = BackupArchive.sha256(bytes)
            api.uploadEditableImport(credential.session, credential.token, version, recordFile, sha)
            // Reopen after losing the acknowledgment and resend the exact original archive.
            val retry = EditableImportQueue(queueDirectory).load()!!.files.single()
            api.uploadEditableImport(credential.session, credential.token, version, retry, sha)
            reopenedQueue.acknowledge(recordFile)
            reopenedQueue.finishIfEmpty()
            assertNull(reopenedQueue.load())
            val remoteVersion = api.editableVersions(credential.session, credential.token).single()
            assertEquals(approved.single().key, remoteVersion.key)
            val fetched = directory.resolve("downloaded.zip")
            api.downloadEditableVersion(credential.session, credential.token, remoteVersion, fetched)
            assertArrayEquals(bytes, fetched.readBytes())
            val receiver = Room.databaseBuilder<FoundationDatabase>(app, File(directory, "receiver.db").path)
                .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val replay = RoomSnapshotRepository(receiver)
                replay.addEditableRecords(BackupArchive.read(fetched).snapshot.editableImportRecords())
                assertEquals(approved.single().snapshot.canonical(), replay.capture().canonical())
            } finally {
                receiver.close()
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

            val expenses = RoomExpenseRepository(database)
            expenses.seed()
            val ledger = RoomSnapshotRepository(database).capture().expenseLedger
            val expenseScope = credential.session.scope
            val expense = Expense(newRecordMetadata(), LocalDate(2026, 11, 9), "Android offline expense", 103,
                ledger.labels.first { it.kind == ExpenseLabelKind.CATEGORY }.metadata.id,
                ledger.labels.first { it.kind == ExpenseLabelKind.PAYMENT }.metadata.id)
            expenses.bind(expenseScope, api.endpoint.origin, ledger)
            expenses.saveExpense(expense, null)
            suspend fun syncExpenses() {
                val state = requireNotNull(expenses.syncState())
                state.pending.filterNot { it.id in state.acknowledged }.forEach { operation ->
                    expenses.acknowledge(expenseScope, api.pushExpense(credential.session, credential.token, operation))
                }
                var cursor = state.cursor
                do {
                    val (entries, more) = api.pullExpenses(credential.session, credential.token, cursor)
                    expenses.replay(expenseScope, cursor, entries)
                    cursor = requireNotNull(expenses.syncState()).cursor
                } while (more)
            }
            syncExpenses()
            assertTrue(requireNotNull(expenses.syncState()).pending.isEmpty())

            val browserId = withTimeout(90_000) {
                var found: Uuid? = null
                while (found == null) {
                    coordinator.synchronize()
                    found = reopened.localTasks().singleOrNull { it.title == "Browser created task" }?.metadata?.id
                    if (found == null) delay(400)
                }
                found
            }
            withTimeout(45_000) {
                while (true) {
                    syncExpenses()
                    val updated = requireNotNull(expenses.syncState()).project().expenses.single()
                    if (updated.amountMinor == 205L) {
                        assertEquals(expense.metadata.id, updated.metadata.id)
                        break
                    }
                    delay(400)
                }
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
            val baseline = approved.single()
            val newBook = originalBook.copy(name = "Reviewed Android notebook",
                metadata = originalBook.metadata.revised(originalBook.metadata.updatedAt))
            val updated = baseline.copy(snapshot = baseline.snapshot.copy(notebooks = listOf(newBook)))
            reopenedQueue.enqueue(target, listOf(updated), mapOf(updated.key to version))
            val updateFile = EditableImportQueue(queueDirectory).load()!!.files.single()
            val (updateId, parent) = updateFile.nameWithoutExtension.split('_').map(Uuid::parse)
            assertEquals(version, parent)
            api.uploadEditableImport(credential.session, credential.token, updateId, updateFile,
                BackupArchive.sha256(updateFile.readBytes()), parent)
            val currentVersion = api.editableVersions(credential.session, credential.token).single()
            assertEquals(updateId, currentVersion.id)
            api.downloadEditableVersion(credential.session, credential.token, currentVersion, fetched)
            val received = BackupArchive.read(fetched).snapshot.editableImportRecords()
            RoomSnapshotRepository(database).applyEditableRecords(received, mapOf(baseline.key to baseline.snapshot))
            assertEquals(updated.snapshot.canonical(),
                RoomSnapshotRepository(database).capture().editableImportRecords().single { it.key == updated.key }.snapshot.canonical())

            val baselines = EditableSyncBaselines(directory.resolve("baselines"))
            val recorded = EditableSyncBaselines.Baseline(updateId, EditableSyncBaselines.fingerprint(updated.snapshot))
            baselines.record(target, mapOf(updated.key to recorded))
            assertEquals(recorded, baselines.load(target)[updated.key])
            assertTrue(baselines.load(target.copy(scope = target.scope.copy(epoch = Uuid.random()))).isEmpty())
            reopenedQueue.invalidateBaselines()
            assertFalse(EditableImportQueue(queueDirectory).load()!!.recordsBaselines)

            verifyAutomaticTransfer(app, database, directory, api, credential, updated, updateId, journal.date, taskId)

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

    private suspend fun verifyAutomaticTransfer(
        app: android.content.Context, database: FoundationDatabase, directory: File, api: ServerApi,
        credential: ServerCredential, updated: EditableImportRecord, updateId: Uuid, originalDate: LocalDate, taskId: Uuid,
    ) {
        val target = EditableImportQueue.Target(api.endpoint.origin, credential.session.scope)
        val fetched = directory.resolve("automatic-download.zip")
        val newBook = updated.snapshot.notebooks.single()
        val page = updated.snapshot.notebookPages.single()
        val recorded = EditableSyncBaselines.Baseline(updateId, EditableSyncBaselines.fingerprint(updated.snapshot))
        val reopened = RoomTodoRepository(database)
        val credentials = ServerCredentialStore(app)
        val automaticScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val editableDirectory = directory.resolve("automatic")
        val automaticBaselines = EditableSyncBaselines(editableDirectory.resolve("editable-baselines"))
        val snapshotRepository = RoomSnapshotRepository(database)
        try {
            credentials.save(credential)
            credentials.setEditableEnabled(target)
            automaticBaselines.record(target, mapOf(updated.key to recorded))
            val automatic = AndroidServerSync(app, reopened, automaticScope, { endpoint ->
                assertEquals(api.endpoint.origin, endpoint.origin)
                api
            }, editableDirectory)
            val localBook = newBook.copy(name = "Automatically uploaded",
                metadata = newBook.metadata.revised(newBook.metadata.updatedAt))
            RoomNotebookRepository(database).apply(NotebookEdit(localBook, listOf(page)))
            automatic.syncTrackedRecords(snapshotRepository::capture)
            val uploaded = api.editableVersions(credential.session, credential.token).single()
            assertNotEquals(updateId, uploaded.id)
            api.downloadEditableVersion(credential.session, credential.token, uploaded, fetched)
            val uploadedSnapshot = BackupArchive.read(fetched).snapshot
            assertEquals(localBook.name, uploadedSnapshot.notebooks.single().name)
            val remoteBook = localBook.copy(name = "Automatically applied",
                metadata = localBook.metadata.revised(localBook.metadata.updatedAt))
            val incoming = uploadedSnapshot.copy(notebooks = listOf(remoteBook))
            BackupArchive.write(fetched, incoming)
            api.uploadEditableImport(credential.session, credential.token, Uuid.random(), fetched,
                BackupArchive.sha256(fetched.readBytes()), uploaded.id)
            automatic.syncTrackedRecords(snapshotRepository::capture, snapshotRepository::applyEditableRecords)
            assertEquals(remoteBook.name, snapshotRepository.capture().notebooks.single().name)
            val habitMonth = JournalMonth(2027, 1)
            val ledger = MonthlyHabitLedger(newRecordMetadata(), habitMonth)
            val reading = HabitDefinition(newRecordMetadata(), ledger.metadata.id, "Read", 0)
            val habitRepository = RoomHabitRepository(database)
            habitRepository.apply(HabitLedgerEdit(ledger.copy(habits = listOf(reading)), definitions = listOf(reading)))
            val habitBase = snapshotRepository.capture().editableImportRecords().single { it.key.kind == "habits" }
            BackupArchive.write(fetched, habitBase.snapshot)
            val habitParent = Uuid.random()
            api.uploadEditableImport(credential.session, credential.token, habitParent, fetched,
                BackupArchive.sha256(fetched.readBytes()))
            automaticBaselines.record(target, mapOf(habitBase.key to EditableSyncBaselines.Baseline(
                habitParent, EditableSyncBaselines.fingerprint(habitBase.snapshot),
            )))
            val check = HabitCompletion(newRecordMetadata(), reading.metadata.id, habitMonth.date(5), true)
            habitRepository.apply(HabitLedgerEdit(habitBase.snapshot.habits.single(), completions = listOf(check)))
            val savedCheck = requireNotNull(habitRepository.find(habitMonth)).completions.single()
            automatic.syncTrackedRecords(snapshotRepository::capture, snapshotRepository::applyEditableRecords)
            val remoteHabit = api.editableVersions(credential.session, credential.token).single { it.key == habitBase.key }
            assertNotEquals(habitParent, remoteHabit.id)
            api.downloadEditableVersion(credential.session, credential.token, remoteHabit, fetched)
            assertEquals(savedCheck, BackupArchive.read(fetched).snapshot.habits.single().completions.single())
            assertEquals(savedCheck, requireNotNull(habitRepository.find(habitMonth)).completions.single())
            val originalKey = EditableImportKey("journal", originalDate.toString())
            automaticBaselines.excludeExisting(target, setOf(originalKey))
            val extraDate = LocalDate(2027, 1, 8)
            val extraEntry = newRecordMetadata()
            val extraPage = JournalPage(newRecordMetadata(), extraEntry.id, 0)
            val extraInk = InkStroke(newRecordMetadata(), extraPage.metadata.id, 0, BrushTool.PEN, 0xff000000, 3f,
                listOf(InkPoint(-0.0f, 25f, 0)))
            RoomJournalRepository(database).apply(PageEdit(extraDate, extraPage, put = listOf(extraInk)))
            val planner = MonthlyPlanner(newRecordMetadata(), CalendarLayout(habitMonth))
            val cell = planner.layout.cell(8)
            val plannerInk = InkStroke(newRecordMetadata(), planner.metadata.id, 0, BrushTool.PEN, 0xff000000, 3f,
                listOf(InkPoint(cell.left + 15, cell.top + 15, 0)))
            RoomPlannerRepository(database).apply(PlannerEdit(planner, put = listOf(plannerInk)))
            automatic.syncTrackedRecords(snapshotRepository::capture, snapshotRepository::applyEditableRecords)
            val remoteRecords = api.editableVersions(credential.session, credential.token)
            assertFalse(remoteRecords.any { it.key == originalKey })
            for ((key, ink) in listOf(EditableImportKey("journal", extraDate.toString()) to extraInk,
                EditableImportKey("planner", habitMonth.toString()) to plannerInk)) {
                val remote = remoteRecords.single { it.key == key }
                api.downloadEditableVersion(credential.session, credential.token, remote, fetched)
                val downloadedInk = BackupArchive.read(fetched).snapshot.strokes.single()
                assertEquals(ink.metadata.id, downloadedInk.metadata.id)
                assertArrayEquals(StrokePointCodec.encode(ink.points), StrokePointCodec.encode(downloadedInk.points))
            }
            assertTrue(reopened.conflicts(taskId).isNotEmpty())
        } finally {
            automaticScope.cancel()
            credentials.setEditableEnabled(null)
            credentials.clear()
        }
    }

}
