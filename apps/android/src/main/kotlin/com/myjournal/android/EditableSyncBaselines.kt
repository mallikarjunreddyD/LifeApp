package com.myjournal.android

import android.util.AtomicFile
import com.myjournal.data.BackupArchive
import com.myjournal.data.SnapshotJson
import com.myjournal.domain.EditableImportKey
import com.myjournal.domain.JournalSnapshot
import java.io.File
import java.io.FileNotFoundException
import kotlin.uuid.Uuid

/** Last confirmed common versions, separated by HTTPS origin, dataset and recovery epoch. */
internal class EditableSyncBaselines(private val directory: File) {
    data class Baseline(val version: Uuid, val fingerprint: String)

    fun load(target: EditableImportQueue.Target): Map<EditableImportKey, Baseline> {
        val input = try { file(target).openRead() } catch (_: FileNotFoundException) { return emptyMap() }
        return input.bufferedReader().useLines { lines ->
            lines.associate { line ->
                val parts = line.split('\t')
                require(parts.size == 4 && parts[3].matches(Regex("[0-9a-f]{64}")))
                EditableImportKey(parts[0], parts[1]) to Baseline(Uuid.parse(parts[2]), parts[3])
            }
        }
    }

    fun record(target: EditableImportQueue.Target, updates: Map<EditableImportKey, Baseline>) {
        if (updates.isEmpty()) return
        val merged = load(target) + updates
        check(directory.isDirectory || directory.mkdirs())
        val file = file(target)
        val stream = file.startWrite()
        try {
            for ((key, baseline) in merged) {
                stream.write("${key.kind}\t${key.key}\t${baseline.version}\t${baseline.fingerprint}\n".encodeToByteArray())
            }
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    fun clear() {
        check(!directory.exists() || directory.deleteRecursively()) { "Could not reset editable record baselines." }
    }

    private fun file(target: EditableImportQueue.Target) = AtomicFile(directory.resolve(
        BackupArchive.sha256("${target.origin}\n${target.scope.dataset}\n${target.scope.epoch}".encodeToByteArray()),
    ))

    companion object {
        fun fingerprint(snapshot: JournalSnapshot) = BackupArchive.sha256(SnapshotJson.encode(snapshot).encodeToByteArray())
    }
}
