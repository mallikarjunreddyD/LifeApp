package com.myjournal.android

import android.system.Os
import android.system.OsConstants
import com.myjournal.data.BackupArchive
import com.myjournal.domain.EditableImportKey
import com.myjournal.domain.EditableImportRecord
import com.myjournal.domain.ServerEndpoint
import com.myjournal.domain.SyncScope
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid

/** Private, backup-excluded copies of user-approved uploads. No server credentials are stored here. */
internal class EditableImportQueue(private val root: File) {
    data class Target(val origin: String, val scope: SyncScope)
    data class Batch(val target: Target, val files: List<File>, val recordsBaselines: Boolean)

    private val pending = root.resolve("pending")
    private val preparing = root.resolve("preparing")
    private val targetFile = "target"
    private val baselineInvalidatedFile = "baseline-invalidated"

    fun load(): Batch? {
        if (!pending.exists()) return null
        val directory = pending.listFiles() ?: throw java.io.IOException("Could not read the pending record uploads.")
        val fields = pending.resolve(targetFile).readLines()
        require(fields.size == 3)
        val target = Target(ServerEndpoint.parse(fields[0]).origin, SyncScope(Uuid.parse(fields[1]), Uuid.parse(fields[2])))
        val files = directory.filter { it.name != targetFile && it.name != baselineInvalidatedFile }.sortedBy { it.name }
        require(files.all { it.isFile && it.extension == "zip" && it.nameWithoutExtension.split('_').let { ids ->
            ids.size in 1..2 && ids.all { id -> Uuid.parse(id).toString() == id }
        } })
        return Batch(target, files, !pending.resolve(baselineInvalidatedFile).exists())
    }

    fun enqueue(target: Target, records: List<EditableImportRecord>, parents: Map<EditableImportKey, Uuid> = emptyMap()) {
        require(records.isNotEmpty())
        check(!pending.exists()) { "Resume the previous record upload first." }
        if (!root.isDirectory) {
            check(root.mkdirs()) { "Could not create the upload directory." }
            syncDirectory(requireNotNull(root.parentFile))
        }
        check(!preparing.exists() || preparing.deleteRecursively())
        check(preparing.mkdir())
        try {
            for (record in records) {
                val parent = parents[record.key]?.let { "_$it" }.orEmpty()
                val file = preparing.resolve("${Uuid.random()}$parent.zip")
                BackupArchive.write(file, record.snapshot)
                require(file.length() <= MAX_ARCHIVE_BYTES) {
                    "The ${record.key.kind} record ${record.key.key} exceeds the server's 16 MiB limit."
                }
            }
            FileOutputStream(preparing.resolve(targetFile)).use {
                it.write("${target.origin}\n${target.scope.dataset}\n${target.scope.epoch}\n".encodeToByteArray())
                it.fd.sync()
            }
            syncDirectory(preparing)
            check(preparing.renameTo(pending)) { "Could not save the upload queue." }
            syncDirectory(root)
        } finally {
            preparing.deleteRecursively()
        }
    }

    fun invalidateBaselines() {
        if (!pending.isDirectory) return
        FileOutputStream(pending.resolve(baselineInvalidatedFile)).use { it.fd.sync() }
        syncDirectory(pending)
    }

    fun acknowledge(file: File) {
        check(file.parentFile == pending && file.delete()) { "Could not clear a committed upload." }
        syncDirectory(pending)
    }

    fun finishIfEmpty() {
        if (load()?.files?.isEmpty() != true) return
        discard()
    }

    fun discard() {
        if (!pending.exists()) return
        // Rename first so a crash cannot leave a pending batch with its target missing.
        check(!preparing.exists() || preparing.deleteRecursively())
        check(pending.renameTo(preparing))
        syncDirectory(root)
        preparing.deleteRecursively()
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) }
        finally { Os.close(descriptor) }
    }

    companion object { const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024 }
}
