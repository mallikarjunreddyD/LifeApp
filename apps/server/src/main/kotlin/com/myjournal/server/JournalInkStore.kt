package com.myjournal.server

import com.myjournal.data.JournalInkJson
import com.myjournal.domain.SyncScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

internal class JournalInkStore(private val database: Database) {
    data class Download(val sha256: String, val bytes: ByteArray)

    fun commit(scope: SyncScope, entryJson: JsonObject, strokeJson: JsonObject, upload: Uuid, digest: String) {
        val entry = JournalInkJson.entry(entryJson)
        // This endpoint imports one initial active page. Additional page operations need their own conflict rules.
        val page = entry.pages.singleOrNull()
        require(page != null && !page.archived && entry.date.year in 1..9999)
        val strokeId = Uuid.parse(strokeJson.getValue("metadata").jsonObject.getValue("id").jsonPrimitive.content)
        require(setOf(strokeId, entry.metadata.id, page.metadata.id).size == 3)
        database.transaction {
            lockEpoch(scope, write = true)
            val dataset = scope.dataset.sql()
            val epoch = scope.epoch.sql()
            val priorEntry = query(
                """SELECT id, payload = CAST(? AS jsonb) FROM journal_ink_entries
                    WHERE dataset_id = ? AND (id = ? OR local_date = CAST(? AS date) OR page_id = ?)""",
                entryJson.toString(), dataset, entry.metadata.id.sql(), entry.date.toString(), page.metadata.id.sql(),
            ) { it.uuid(1) to it.getBoolean(2) }
            if (priorEntry.isNotEmpty() && priorEntry != listOf(entry.metadata.id to true)) {
                conflict("CONFLICT_CHANGED", "A journal entry, date or page already has different content.")
            }
            val prior = query(
                """SELECT upload_id, sha256, payload = CAST(? AS jsonb) FROM journal_ink_strokes
                    WHERE dataset_id = ? AND id = ?""",
                strokeJson.toString(), dataset, strokeId.sql(),
            ) { Triple(it.uuid(1), it.getString(2), it.getBoolean(3)) }.singleOrNull()
            if (prior != null) {
                if (prior != Triple(upload, digest, true)) conflict("IDEMPOTENCY_MISMATCH", "The stroke ID already has different content.")
                return@transaction
            }
            val identityCollision = query(
                """SELECT 1 FROM journal_ink_entries WHERE dataset_id = ?
                    AND (id IN (?, ?) OR page_id IN (?, ?))
                    UNION ALL
                    SELECT 1 FROM journal_ink_strokes WHERE dataset_id = ? AND id IN (?, ?)""",
                dataset, page.metadata.id.sql(), strokeId.sql(), entry.metadata.id.sql(), strokeId.sql(),
                dataset, entry.metadata.id.sql(), page.metadata.id.sql(),
            ) { true }.isNotEmpty()
            if (identityCollision) conflict("CONFLICT_CHANGED", "A journal identity already belongs to another record.")
            val staged = query(
                """SELECT bytes FROM staged_ink WHERE dataset_id = ? AND epoch_id = ? AND id = ?
                    AND sha256 = ? AND expires_at > now()""",
                dataset, epoch, upload.sql(), digest,
            ) { it.getBytes(1) }.singleOrNull()
                ?: conflict("MISSING_DEPENDENCY", "The matching stroke upload is missing or expired.")
            val stroke = JournalInkJson.stroke(strokeJson, staged)
            require(stroke.pageId == page.metadata.id)
            require(stroke.points.all { it.x in 0f..page.width && it.y in 0f..page.height })
            if (query(
                    """SELECT 1 FROM journal_ink_strokes WHERE dataset_id = ?
                        AND (upload_id = ? OR (page_id = ? AND stroke_order = ?))""",
                    dataset, upload.sql(), page.metadata.id.sql(), stroke.order,
                ) { true }.isNotEmpty()) conflict("CONFLICT_CHANGED", "A stroke already uses this upload or page position.")
            if (priorEntry.isEmpty()) execute(
                """INSERT INTO journal_ink_entries (dataset_id, id, local_date, page_id, payload)
                    VALUES (?, ?, CAST(? AS date), ?, CAST(? AS jsonb))""",
                dataset, entry.metadata.id.sql(), entry.date.toString(), page.metadata.id.sql(), entryJson.toString(),
            )
            execute(
                """INSERT INTO journal_ink_strokes (dataset_id, id, page_id, stroke_order, upload_id, sha256, payload, bytes)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)""",
                dataset, strokeId.sql(), page.metadata.id.sql(), stroke.order, upload.sql(), digest, strokeJson.toString(), staged,
            )
            execute("DELETE FROM staged_ink WHERE dataset_id = ? AND epoch_id = ? AND id = ?", dataset, epoch, upload.sql())
        }
    }

    fun download(scope: SyncScope, id: Uuid): Download = database.transaction {
        lockEpoch(scope)
        query("SELECT sha256, bytes FROM journal_ink_strokes WHERE dataset_id = ? AND id = ?", scope.dataset.sql(), id.sql()) {
            Download(it.getString(1), it.getBytes(2))
        }.singleOrNull() ?: throw ApiFailure(404, "NOT_FOUND", "Journal stroke not found.")
    }
}
