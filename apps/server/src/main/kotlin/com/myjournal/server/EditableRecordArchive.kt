package com.myjournal.server

import com.myjournal.data.BackupArchive
import com.myjournal.data.JournalInkJson
import com.myjournal.data.StrokePointCodec
import com.myjournal.core.RecordMetadata
import com.myjournal.domain.JournalSnapshot
import com.myjournal.domain.inkSurface
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.uuid.Uuid

internal data class EditableRecordArchive(
    val kind: String,
    val key: String,
    val root: Uuid,
    val identities: JsonArray,
) {
    companion object {
        private fun decode(bytes: ByteArray): JournalSnapshot {
            val file = Files.createTempFile("lifeapp-editable-", ".zip")
            return try {
                Files.write(file, bytes)
                try {
                    BackupArchive.read(file.toFile()).snapshot
                } catch (_: IOException) {
                    throw IllegalArgumentException("Invalid editable record archive.")
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }

        fun preview(bytes: ByteArray): JsonObject {
            val snapshot = decode(bytes)
            val strokesByPage = snapshot.strokes.groupBy { it.pageId }
            val size = snapshot.strokes.sumOf { 12L + 20L * it.points.size }
            if (size > 4 * 1024 * 1024) {
                throw ApiFailure(413, "LIMIT_EXCEEDED", "The record exceeds the browser preview limit.")
            }
            val planner = snapshot.planners.singleOrNull()
            val journal = snapshot.journals.singleOrNull()
            val notebook = snapshot.notebooks.singleOrNull()
            val ledger = snapshot.habits.singleOrNull()
            return buildJsonObject {
                put("title", planner?.month?.toString() ?: journal?.date?.toString() ?: notebook?.name ?: ledger?.month.toString())
                put("pages", buildJsonArray {
                    fun page(surface: com.myjournal.domain.InkSurface, label: String, archived: Boolean = false) {
                        add(buildJsonObject {
                            put("title", label)
                            put("width", surface.width)
                            put("height", surface.height)
                            put("archived", archived)
                            val layout = surface.calendar
                            put("cells", buildJsonArray {
                                if (layout != null) for (day in 1..layout.month.days) {
                                    val cell = layout.cell(day)
                                    add(buildJsonArray {
                                        add(day); add(cell.left); add(cell.top); add(cell.right); add(cell.bottom)
                                    })
                                }
                            })
                            put("strokes", buildJsonArray {
                                for (stroke in strokesByPage[surface.id].orEmpty().sortedBy { it.order }) {
                                    val source = StrokePointCodec.encode(stroke.points)
                                    add(buildJsonObject {
                                        put("color", stroke.colorArgb)
                                        put("width", stroke.width)
                                        put("points", Base64.encode(source))
                                        val first = stroke.points.first()
                                        put("day", layout?.dateAt(first.x, first.y)?.day?.let(::JsonPrimitive) ?: JsonNull)
                                    })
                                }
                            })
                        })
                    }
                    planner?.let { page(it.surface, it.month.toString()) }
                    journal?.pages?.sortedBy { it.order }?.forEach { page(it.inkSurface(), "Page ${it.order + 1L}", it.archived) }
                    snapshot.notebookPages.sortedBy { it.order }.forEach { page(it.surface, it.title ?: "Page ${it.order + 1L}", it.archived) }
                })
                put("habits", buildJsonArray {
                    ledger?.habits?.sortedBy { it.order }?.forEach { habit ->
                        add(buildJsonObject {
                            put("name", habit.name)
                            put("archived", habit.archived)
                            put("completedDays", JsonArray(ledger.completions.filter { it.habitId == habit.metadata.id && it.checked }
                                .map { it.date.day }.sorted().map(::JsonPrimitive)))
                        })
                    }
                })
            }
        }

        fun read(bytes: ByteArray): EditableRecordArchive {
            val snapshot = decode(bytes)
            require(snapshot.metadata.isEmpty() && snapshot.todos.isEmpty() && snapshot.workoutLog.records.isEmpty())
            require(snapshot.journals.size + snapshot.planners.size + snapshot.habits.size + snapshot.notebooks.size == 1)
            val journal = snapshot.journals.singleOrNull()
            val planner = snapshot.planners.singleOrNull()
            val habits = snapshot.habits.singleOrNull()
            val notebook = snapshot.notebooks.singleOrNull()
            val (kind, key, root) = when {
                journal != null -> {
                    require(journal.date.year in 1..9999)
                    Triple("journal", journal.date.toString(), journal.metadata)
                }
                planner != null -> Triple("planner", planner.month.toString(), planner.metadata)
                habits != null -> Triple("habits", habits.month.toString(), habits.metadata)
                else -> requireNotNull(notebook).let { Triple("notebook", it.metadata.id.toString(), it.metadata) }
            }
            val identities = buildJsonArray {
                fun identity(metadata: RecordMetadata, role: String, parent: Uuid? = null, digest: String? = null) {
                    add(buildJsonObject {
                        put("id", metadata.id.toString())
                        put("role", role)
                        put("parent_id", parent?.toString())
                        put("created_at", metadata.createdAt.toString())
                        put("immutable_digest", digest)
                    })
                }
                identity(root, "root")
                journal?.pages?.forEach { identity(it.metadata, "page", it.entryId) }
                snapshot.notebookPages.forEach { identity(it.metadata, "page", it.notebookId) }
                habits?.habits?.forEach { identity(it.metadata, "habit", it.ledgerId) }
                habits?.completions?.forEach { identity(it.metadata, "completion/${it.date}", it.habitId) }
                for (stroke in snapshot.strokes) {
                    val digest = MessageDigest.getInstance("SHA-256").apply {
                        update(JournalInkJson.strokeHeader(stroke).toString().encodeToByteArray())
                    }.digest(StrokePointCodec.encode(stroke.points))
                    identity(stroke.metadata, "stroke", stroke.pageId, HexFormat.of().formatHex(digest))
                }
            }
            return EditableRecordArchive(kind, key, root.id, identities)
        }
    }
}
