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

data class RestorePreview(val file: File, val backup: BackupArchive.Validated, val replacesData: Boolean, val sourceUri: Uri)
data class OperationState(val busy: Boolean = false, val message: String? = null, val error: Boolean = false,
    val preview: RestorePreview? = null, val pdfSnapshot: JournalSnapshot? = null)

/** Application-owned operations survive Activity recreation. The UI is locked while busy. */
class DataOperations(private val app: JournalApplication, private val repository: SnapshotRepository) {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(OperationState())
    val state = mutable.asStateFlow()
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
        awaitCommittedWrites { app.session.let { SaveStatus(it.pending,it.error) } }
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
            if(format=="backup") "Backup created and verified. All committed records included." else "Export created and verified."
        } finally { file.delete() }
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
            require(recoveryUri.normalizeScheme() != preview.sourceUri.normalizeScheme()) { "Recovery destination must differ from the input backup" }
            val recovery=temp(".zip")
            try { withContext(Dispatchers.IO) { BackupArchive.write(recovery,existing);writeVerified(recovery,recoveryUri) } }
            finally { recovery.delete() }
        }
        // No UI editor can write while busy, and all old queues have drained. Cancel
        // old readers/caches only after validation and verified recovery are complete.
        app.session.cancel()
        try { repository.replace(incoming) }
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
