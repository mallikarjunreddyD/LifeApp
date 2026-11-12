package com.myjournal.android

import android.net.Uri
import com.myjournal.data.*
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class RestorePreview(val file: File, val backup: BackupArchive.Validated, val replacesData: Boolean, val sourceUri: Uri?)
data class JournalInventoryPreview(val origin: String, val scope: SyncScope, val inventory: JournalImportInventory)

data class OperationState(val busy: Boolean = false, val message: String? = null, val error: Boolean = false,
    val preview: RestorePreview? = null, val pdfSnapshot: JournalSnapshot? = null, val driveVersions: List<CloudBackupVersion>? = null,
    val journalInventory: JournalInventoryPreview? = null, val localBackupVerifiedAt: String? = null)

internal data class EditableImportReview(
    val target: EditableImportQueue.Target,
    val eligible: List<EditableImportRecord>,
    val overlaps: List<EditableImportKey>,
    val serverCount: Int,
    val downloadable: List<EditableRemoteVersion>,
    val conflicted: List<EditableImportKey>,
    val uploadParents: Map<EditableImportKey, Uuid>,
    val replaceExpected: Map<EditableImportKey, JournalSnapshot>,
    val habitChecks: Map<EditableImportKey, List<HabitCompletion>>,
)

/** Application-owned operations survive Activity recreation. The UI is locked while busy. */
class DataOperations(private val app: JournalApplication, private val repository: RoomSnapshotRepository) {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val protection = app.getSharedPreferences("backup-verification", android.content.Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(OperationState(
        localBackupVerifiedAt = protection.getString("localVerifiedAt", null),
    ))
    val state = mutable.asStateFlow()
    private val importReviewMutable = MutableStateFlow<EditableImportReview?>(null)
    internal val importReview = importReviewMutable.asStateFlow()

    private fun start(label: String, action: suspend ()->String) {
        if (mutable.value.busy) return
        mutable.value=mutable.value.copy(busy=true,message=label,error=false)
        scope.launch {
            try { val result=action(); mutable.value=mutable.value.copy(busy=false,message=result,error=false) }
            catch (failure: Exception) {
                mutable.value=mutable.value.copy(busy=false,message="Not completed. ${failure.message ?: "File or storage operation failed"}",error=true)
            }
        }
    }
    private suspend fun snapshot(): JournalSnapshot {
        awaitCommittedWrites {
            val tasks = app.todoActions.state.value
            SaveStatus(app.session.pending + if (tasks.saving) 1 else 0, app.session.error ?: tasks.error)
        }
        return repository.capture()
    }
    private fun temp(suffix: String) = File.createTempFile("lifeapp-",suffix,app.cacheDir)
    fun export(uri: Uri, format: String) = start("Waiting for saves and preparing $format…") {
        val data=snapshot()
        val file=temp(if(format=="backup" || format=="vectors") ".zip" else ".$format")
        try {
            withContext(Dispatchers.IO) {
                when(format) {
                    "backup", "vectors" -> BackupArchive.write(file,data,format)
                    "json" -> file.writeText(SnapshotJson.encode(data))
                    "md" -> file.writeText(MarkdownExport.encode(data))
                    else -> error("Unsupported export")
                }
                require(file.length()<=BackupArchive.MAX_ARCHIVE_BYTES) { "Export exceeds 64 MiB" }
                writeVerified(file,uri)
            }
            if (format == "backup") {
                val verifiedAt = Clock.System.now().toString()
                protection.edit().putString("localVerifiedAt", verifiedAt).apply()
                mutable.value = mutable.value.copy(localBackupVerifiedAt = verifiedAt)
                "Local backup created and verified."
            } else "Export created and verified."
        } finally { file.delete() }
    }
    internal fun bindServer(uri: Uri, sync: AndroidServerSync, preview: BindingPreview) =
        start("Creating a verified local backup before server binding…") {
            val data = snapshot()
            val file = temp(".zip")
            try {
                withContext(Dispatchers.IO) {
                    BackupArchive.write(file, data)
                    writeVerified(file, uri)
                }
                sync.bindFromVerifiedBackup(preview, data.todos)
                "Backup verified. To-do is bound and first sync is queued."
            } finally {
                file.delete()
            }
        }

    internal fun reviewJournal(uri: Uri, sync: AndroidServerSync, origin: String, scope: SyncScope) =
        start("Creating a verified backup before reviewing journal records…") {
            mutable.value = mutable.value.copy(journalInventory = null)
            val data = snapshot()
            val file = temp(".zip")
            try {
                withContext(Dispatchers.IO) {
                    BackupArchive.write(file, data)
                    writeVerified(file, uri)
                }
            } finally {
                file.delete()
            }
            val inventory = sync.inspectJournal(origin, scope, data)
            mutable.value = mutable.value.copy(journalInventory = JournalInventoryPreview(origin, scope, inventory))
            "Backup verified. Journal inventory ready to review."
        }

    fun dismissJournalInventory() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(journalInventory = null)
    }

    internal fun reviewEditableImport(uri: Uri, sync: AndroidServerSync, target: EditableImportQueue.Target) =
        start("Creating a verified backup before reviewing record uploads…") {
            importReviewMutable.value = null
            val data = snapshot()
            val file = temp(".zip")
            try {
                withContext(Dispatchers.IO) {
                    BackupArchive.write(file, data)
                    writeVerified(file, uri)
                }
            } finally {
                file.delete()
            }
            importReviewMutable.value = sync.reviewEditableImport(target, data)
            "Backup verified. Review the records before approving upload."
        }

    internal fun approveEditableImport(sync: AndroidServerSync, review: EditableImportReview) =
        start("Saving the approved upload queue…") {
            check(importReviewMutable.value === review)
            sync.queueEditableImport(review)
            importReviewMutable.value = null
            sync.resumeEditableImport(::snapshot)
            "Approved uploads queued."
        }

    internal fun applyEditableDownload(sync: AndroidServerSync, review: EditableImportReview) =
        start("Downloading and validating reviewed records…") {
            check(importReviewMutable.value === review && review.downloadable.isNotEmpty())
            val count = sync.applyReviewedDownloads(review, ::snapshot) { applyRecordBatch(it, review.replaceExpected) }
            importReviewMutable.value = null
            "Applied $count reviewed server records."
        }

    internal fun resumeEditableUploads(sync: AndroidServerSync) = sync.resumeEditableImport(::snapshot)

    internal fun setAutomaticEditable(sync: AndroidServerSync, enabled: Boolean) {
        sync.setAutomaticEditable(enabled, ::snapshot)
    }

    internal fun syncTrackedOnForeground(sync: AndroidServerSync) {
        scope.launch {
            val enabled = try {
                sync.automaticEditableEnabled()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The server foreground refresh reports credential storage failures.
                return@launch
            }
            if (!enabled || importReviewMutable.value != null || mutable.value.preview != null) return@launch
            start("Checking tracked server records…") {
                sync.syncTrackedRecords(::snapshot, ::applyRecordBatch)
                "Finished checking tracked server records."
            }
        }
    }

    private suspend fun applyRecordBatch(records: List<EditableImportRecord>, expected: Map<EditableImportKey, JournalSnapshot>) {
        snapshot()
        app.session.cancel()
        try {
            repository.applyEditableRecords(records, expected)
        } finally {
            app.rebuildEditors()
        }
    }

    internal fun dismissEditableImport() {
        if (!mutable.value.busy) importReviewMutable.value = null
    }

    fun clearDriveVersions() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(driveVersions = null)
    }

    fun listDriveBackups(storage: CloudBackupStorage) = start("Listing Drive backups…") {
        val versions = storage.list()
        mutable.value = mutable.value.copy(driveVersions = versions)
        "${versions.size} Drive backup versions found."
    }

    fun backupToDrive(storage: CloudBackupStorage, passphrase: CharArray) {
        if (mutable.value.busy) { passphrase.fill('\u0000'); return }
        start("Preparing encrypted Drive backup…") {
            try {
                val committed = snapshot()
                val file = temp(".zip")
                val encrypted = try {
                    withContext(Dispatchers.IO) {
                        BackupArchive.write(file, committed)
                        val archive = file.inputStream().use {
                            BackupArchive.boundedRead(it, CloudBackupEnvelope.MAX_ARCHIVE_BYTES.toLong())
                        }
                        try { CloudBackupEnvelope.seal(archive, passphrase) }
                        finally { archive.fill(0) }
                    }
                } finally { file.delete() }
                storage.create(encrypted)
                mutable.value = mutable.value.copy(driveVersions = null)
                "Encrypted Drive backup created and verified."
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun inspectDriveBackup(storage: CloudBackupStorage, version: CloudBackupVersion, passphrase: CharArray) {
        if (mutable.value.busy) { passphrase.fill('\u0000'); return }
        start("Downloading and validating encrypted backup…") {
            try {
                mutable.value.preview?.file?.delete()
                mutable.value = mutable.value.copy(preview = null, pdfSnapshot = null)
                snapshot()
                val envelope = storage.download(version.id)
                val file = temp(".zip")
                try {
                    val backup = withContext(Dispatchers.IO) {
                        val archive = try { CloudBackupEnvelope.open(envelope, passphrase) }
                        catch (_: javax.crypto.AEADBadTagException) { error("Wrong passphrase or damaged encrypted backup.") }
                        try { file.writeBytes(archive) } finally { archive.fill(0) }
                        BackupArchive.read(file)
                    }
                    mutable.value = mutable.value.copy(
                        preview = RestorePreview(file, backup, repository.capture().populated, null),
                    )
                    "Drive backup validated. Review its contents before confirming restore."
                } catch (failure: Throwable) {
                    file.delete()
                    throw failure
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun preparePdf() = start("Loading saved export selections…") {
        mutable.value=mutable.value.copy(pdfSnapshot=snapshot()); "Choose the saved pages to export."
    }
    fun closePdf() { if(!mutable.value.busy) mutable.value=mutable.value.copy(pdfSnapshot=null) }
    fun exportPdf(uri: Uri, select: (JournalSnapshot)->List<ExportPage>) = start("Preparing PDF…") {
        val data=snapshot(); val file=temp(".pdf")
        try {
            PdfExport.write(file,select(data))
            withContext(Dispatchers.IO) { require(file.length()<=128L*1024*1024);writeVerified(file,uri) }
            mutable.value=mutable.value.copy(pdfSnapshot=null)
            "PDF exported and verified. Original editable handwriting is unchanged."
        } finally { file.delete() }
    }
    fun exportWorkoutPdf(uri: Uri, month: JournalMonth, compact: Boolean) = start("Preparing workout PDF…") {
        val file = temp(".pdf")
        try {
            WorkoutPdfExport.write(file, snapshot().workoutLog, month, compact)
            withContext(Dispatchers.IO) {
                require(file.length() <= 128L * 1024 * 1024)
                writeVerified(file, uri)
            }
            "Workout PDF exported and verified."
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error("Workout PDF export failed. ${failure.message ?: "File or storage operation failed"}")
        } finally {
            file.delete()
        }
    }

    fun inspectRestore(uri: Uri) = start("Validating backup before any database change…") {
        mutable.value.preview?.file?.delete()
        mutable.value=mutable.value.copy(preview=null,pdfSnapshot=null)
        snapshot() // Do not hide outstanding save failures during preview.
        val file=temp(".zip")
        try {
            val backup=withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source); FileOutputStream(file).use { target -> copyBounded(source,target,BackupArchive.MAX_ARCHIVE_BYTES) }
                }
                BackupArchive.read(file)
            }
            val existing=repository.capture()
            mutable.value.preview?.file?.delete()
            mutable.value=mutable.value.copy(preview=RestorePreview(file,backup,existing.populated,uri))
            "Backup validated. Review its contents before confirming restore."
        } catch(failure: Throwable) { file.delete();throw failure }
    }
    fun dismissRestore() {
        if(mutable.value.busy) return
        mutable.value.preview?.file?.delete();mutable.value=mutable.value.copy(preview=null)
    }
    /** Confirmation happens in Settings; populated replacement additionally requires a chosen recovery file. */
    fun restore(recoveryUri: Uri?) = start("Validating and restoring backup…") {
        val preview=requireNotNull(mutable.value.preview) { "Select and validate a backup first" }
        val existing=snapshot()
        val incoming=withContext(Dispatchers.IO) { BackupArchive.read(preview.file).snapshot }
        if(existing.populated) {
            requireNotNull(recoveryUri) { "Choose a pre-restore recovery backup destination before replacement" }
            require(recoveryUri.normalizeScheme() != preview.sourceUri?.normalizeScheme()) { "Recovery destination must differ from the input backup" }
            val recovery=temp(".zip")
            try { withContext(Dispatchers.IO) { BackupArchive.write(recovery,existing);writeVerified(recovery,recoveryUri) } }
            finally { recovery.delete() }
        }
        // No UI editor can write while busy, and all old queues have drained. Cancel
        // old readers/caches only after validation and verified recovery are complete.
        app.session.cancel()
        try {
            app.serverSync.resetEditableBaselines()
            repository.replace(incoming)
        }
        finally { app.rebuildEditors() }
        preview.file.delete();mutable.value=mutable.value.copy(preview=null,pdfSnapshot=null)
        "Restore committed. All editor caches were reinitialized.${if(existing.populated) " Verified recovery backup saved to your chosen file." else ""}"
    }
    private fun writeVerified(file: File, uri: Uri) {
        val resolver=app.contentResolver
        resolver.openOutputStream(uri,"wt").use { target -> requireNotNull(target); file.inputStream().use { copyBounded(it,target,128L*1024*1024) } }
        fun digest(input: InputStream): Pair<Long,String> {
            val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(32768);var total=0L
            while(true) { val count=input.read(buffer);if(count<0) break;total+=count;require(total<=128L*1024*1024);hash.update(buffer,0,count) }
            return total to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val source=file.inputStream().use(::digest)
        val written=resolver.openInputStream(uri).use { digest(requireNotNull(it) { "Destination cannot be verified" }) }
        check(source==written) { "Destination verification failed; it may contain a partial file. Current data is unchanged." }
    }
    private fun copyBounded(source: InputStream, target: OutputStream, limit: Long) {
        val buffer=ByteArray(32768);var total=0L
        while(true) { val count=source.read(buffer);if(count<0) break;total+=count;require(total<=limit) { "File exceeds size limit" };target.write(buffer,0,count) }
        target.flush()
    }
}

fun exportName(prefix: String, extension: String): String {
    val safe=prefix.replace(Regex("[^A-Za-z0-9._-]+"),"-").trim('-','.').take(100).ifBlank { "LifeApp" }
    return "$safe-${Clock.System.now().toEpochMilliseconds()}-${kotlin.uuid.Uuid.random().toString().take(8)}.$extension"
}
