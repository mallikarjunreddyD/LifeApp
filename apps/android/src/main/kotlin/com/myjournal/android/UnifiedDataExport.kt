package com.myjournal.android

import com.myjournal.data.BackupArchive
import com.myjournal.data.ExpenseExport
import com.myjournal.data.MarkdownExport
import com.myjournal.domain.JournalMonth
import com.myjournal.domain.JournalSnapshot
import com.myjournal.domain.journalPdf
import com.myjournal.domain.notebookPdf
import com.myjournal.domain.plannerPdf
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.time.Clock

internal object UnifiedDataExport {
    private const val MAX_BYTES = 128L * 1024 * 1024

    suspend fun write(
        output: File, workspace: File, snapshot: JournalSnapshot, waiting: Int,
    ): List<String> {
        val recovery = workspace.resolve("recovery.zip")
        BackupArchive.write(recovery, snapshot)
        BackupArchive.read(recovery)
        val omitted = mutableListOf<String>()
        var size = 0L
        val included = linkedMapOf<String, Pair<Long, String>>()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun append(path: String, source: File, required: Boolean = false) {
                val length = source.length()
                if (length <= 0 || length > MAX_BYTES - size - 1024 * 1024) {
                    check(!required) { "Required export files exceed the export limit." }
                    omitted += "$path exceeds the export size limit."
                    return
                }
                val checksum = source.inputStream().use(::digest)
                zip.putNextEntry(ZipEntry(path))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                included[path] = length to checksum
                size += length
            }
            append("backup/lifeapp-recovery.zip", recovery, required = true)
            val scratch = workspace.resolve("representation")
            scratch.writeText(MarkdownExport.encode(snapshot))
            append("data/metadata.md", scratch)
            scratch.writeText(
                "LifeApp export from this Android device.\n" +
                    "The recovery ZIP contains committed local records and original editable ink.\n" +
                    "PDFs are readable copies. No handwriting OCR was performed.\n" +
                    "Other devices and unsynchronized server data may have additional changes.\n" +
                    "Store this unencrypted export privately.\n",
            )
            append("README.txt", scratch, required = true)

            for ((name, content) in ExpenseExport.json(snapshot.expenseLedger)) {
                scratch.writeText(content.toString())
                append("expenses/$name", scratch, required = true)
            }
            scratch.writeText(ExpenseExport.csv(snapshot.expenseLedger))
            append("expenses/expenses.csv", scratch, required = true)

            suspend fun pdf(name: String, pages: List<com.myjournal.domain.ExportPage>) {
                if (pages.isEmpty()) return
                if (pages.size > 500 || size >= MAX_BYTES - 1024 * 1024) {
                    omitted += "$name exceeds the PDF or export limit; editable records are in the recovery ZIP."
                    return
                }
                currentCoroutineContext().ensureActive()
                PdfExport.write(scratch, pages)
                append(name, scratch)
            }
            val dates = snapshot.journals.map { it.date }
            if (dates.isNotEmpty()) pdf("documents/journal.pdf", snapshot.journalPdf(dates.min(), dates.max()))
            pdf("documents/planner.pdf", snapshot.planners.sortedBy { it.month }.flatMap { snapshot.plannerPdf(it.month) })
            pdf("documents/notebooks.pdf", snapshot.notebooks.flatMap { snapshot.notebookPdf(it.metadata.id) })
            val months = snapshot.workoutLog.sessions.filter { !it.deleted && (it.logged || it.completed) }
                .map { JournalMonth.from(it.date) }.distinct().sorted()
            for (month in months) {
                if (size >= MAX_BYTES - 1024 * 1024) {
                    omitted += "Some workout PDFs exceed the export limit; recorded sessions are in the recovery ZIP."
                    break
                }
                currentCoroutineContext().ensureActive()
                try {
                    WorkoutPdfExport.write(scratch, snapshot.workoutLog, month, compact = true)
                    append("reports/workouts-$month.pdf", scratch)
                } catch (failure: IllegalArgumentException) {
                    if (failure.message != "Export at most 500 pages per PDF") throw failure
                    omitted += "Workout PDF for $month exceeds 500 pages; recorded sessions are in the recovery ZIP."
                }
            }
            val manifest = buildJsonObject {
                put("format", "LifeAppMyData")
                put("version", 1)
                put("createdAt", Clock.System.now().toString())
                put("source", "android-local")
                put("completeAcrossDevices", false)
                put("knownQueuedChanges", waiting + (snapshot.expenseSync?.pending?.size ?: 0))
                put("coverage", "Committed Android records, including queued local expenses. Changes on other devices may be missing.")
                put("recordCounts", buildJsonObject { snapshot.counts().forEach { (name, count) -> put(name, count) } })
                put("files", buildJsonArray {
                    for ((path, file) in included) add(buildJsonObject {
                        put("path", path)
                        put("bytes", file.first)
                        put("sha256", file.second)
                    })
                })
                put("unavailable", JsonArray(omitted.map(::JsonPrimitive)))
            }.toString().encodeToByteArray()
            check(manifest.size < 1024 * 1024)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            included["manifest.json"] = manifest.size.toLong() to BackupArchive.sha256(manifest)
        }
        check(output.length() <= MAX_BYTES) { "The generated export exceeds 128 MiB." }
        ZipFile(output).use { zip ->
            check(zip.size() == included.size)
            for ((name, expected) in included) {
                currentCoroutineContext().ensureActive()
                val entry = requireNotNull(zip.getEntry(name))
                check(entry.size == expected.first && zip.getInputStream(entry).use(::digest) == expected.second) {
                    "Export verification failed."
                }
            }
        }
        return omitted
    }

    private fun digest(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
