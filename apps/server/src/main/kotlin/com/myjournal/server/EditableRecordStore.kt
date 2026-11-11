package com.myjournal.server

import com.myjournal.core.RecordMetadata
import com.myjournal.data.BackupArchive
import com.myjournal.data.JournalInkJson
import com.myjournal.data.StrokePointCodec
import com.myjournal.domain.HabitCompletion
import com.myjournal.domain.JournalMonth
import com.myjournal.domain.JournalSnapshot
import com.myjournal.domain.SyncScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import java.util.HexFormat
import kotlin.uuid.Uuid

internal class EditableRecordStore(private val database: Database) {
    data class Version(
        val id: Uuid, val sequence: Long, val kind: String, val key: String, val root: Uuid,
        val sha256: String, val parents: List<String>, val current: Boolean,
    )
    data class Download(val sha256: String, val bytes: ByteArray)

    fun stage(scope: SyncScope, id: Uuid, sha256: String, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_ARCHIVE_BYTES)
        require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == sha256)
        val archive = EditableRecordArchive.read(bytes)
        database.transaction {
            lockEpoch(scope, write = true)
            val dataset = scope.dataset.sql()
            val epoch = scope.epoch.sql()
            val prior = query("SELECT sha256 FROM editable_record_versions WHERE dataset_id = ? AND id = ?", dataset, id.sql()) {
                it.getString(1)
            }.singleOrNull()
            if (prior != null) {
                if (prior != sha256) conflict("IDEMPOTENCY_MISMATCH", "The version ID already has different content.")
                return@transaction
            }
            execute("DELETE FROM editable_record_staging WHERE dataset_id = ? AND (expires_at <= now() OR epoch_id <> ?)", dataset, epoch)
            val staged = query(
                "SELECT sha256 FROM editable_record_staging WHERE dataset_id = ? AND epoch_id = ? AND id = ?",
                dataset, epoch, id.sql(),
            ) { it.getString(1) }.singleOrNull()
            if (staged != null) {
                if (staged != sha256) conflict("IDEMPOTENCY_MISMATCH", "The upload ID already has different content.")
                return@transaction
            }
            val (count, size) = query(
                "SELECT count(*), coalesce(sum(octet_length(archive)), 0) FROM editable_record_staging WHERE dataset_id = ?",
                dataset,
            ) { it.getLong(1) to it.getLong(2) }.single()
            if (count >= 16 || size + bytes.size > 64L * 1024 * 1024) {
                throw ApiFailure(413, "LIMIT_EXCEEDED", "Staged record capacity is full.")
            }
            execute(
                """INSERT INTO editable_record_staging
                    (dataset_id, epoch_id, id, kind, logical_key, root_id, sha256, identities, archive, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, now() + interval '24 hours')""",
                dataset, epoch, id.sql(), archive.kind, archive.key, archive.root.sql(), sha256, archive.identities.toString(), bytes,
            )
        }
    }

    fun commit(scope: SyncScope, id: Uuid, sha256: String, parents: Set<Uuid>, requireEmpty: Boolean = false) {
        require(parents.size <= 32 && id !in parents && (!requireEmpty || parents.isEmpty()))
        database.transaction {
            lockEpoch(scope, write = true)
            val dataset = scope.dataset.sql()
            val epoch = scope.epoch.sql()
            val parentsSql = createArrayOf("uuid", parents.map { it.sql() }.toTypedArray())
            try {
                val prior = query(
                    "SELECT sha256 = ? AND parents @> ? AND parents <@ ? FROM editable_record_versions WHERE dataset_id = ? AND id = ?",
                    sha256, parentsSql, parentsSql, dataset, id.sql(),
                ) { it.getBoolean(1) }.singleOrNull()
                if (prior != null) {
                    if (!prior) conflict("IDEMPOTENCY_MISMATCH", "The version ID already has different content or parents.")
                    return@transaction
                }
                val staged = query(
                    """SELECT kind, logical_key, root_id, identities FROM editable_record_staging
                        WHERE dataset_id = ? AND epoch_id = ? AND id = ? AND sha256 = ? AND expires_at > now() FOR UPDATE""",
                    dataset, epoch, id.sql(), sha256,
                ) { arrayOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }.singleOrNull()
                    ?: conflict("MISSING_DEPENDENCY", "The matching staged record is missing or expired.")
                val (kind, key, root, identities) = staged
                val heads = heads(dataset, kind, key).map { it.id }.toSet()
                if (requireEmpty && heads.isNotEmpty()) {
                    conflict("CONFLICT_CHANGED", "The server now has a record for this date, month or notebook.")
                }
                val knownParents = query(
                    """SELECT root_id FROM editable_record_versions
                        WHERE dataset_id = ? AND kind = ? AND logical_key = ? AND id = ANY(?)""",
                    dataset, kind, key, parentsSql,
                ) { it.getString(1) }
                if (knownParents.size != parents.size) conflict("MISSING_DEPENDENCY", "A parent version is missing.")
                if (parents.size > 1 && heads != parents) conflict("CONFLICT_CHANGED", "Review the current record variants.")
                if (parents.isNotEmpty() && root !in knownParents) conflict("CONFLICT_CHANGED", "A version must retain a parent record identity.")
                if (heads.size - heads.intersect(parents).size >= 32) {
                    throw ApiFailure(413, "LIMIT_EXCEEDED", "Resolve the existing record variants before adding another.")
                }
                if (query(
                        """WITH incoming AS (SELECT id FROM jsonb_to_recordset(CAST(? AS jsonb)) AS i(id uuid))
                           SELECT 1 FROM journal_ink_entries e WHERE e.dataset_id = ?
                             AND ((? = 'journal' AND e.local_date::text = ?) OR e.id IN (SELECT id FROM incoming) OR e.page_id IN (SELECT id FROM incoming))
                             AND NOT EXISTS (SELECT 1 FROM legacy_journal_versions m WHERE m.dataset_id = e.dataset_id AND m.entry_id = e.id)
                           UNION ALL
                           SELECT 1 FROM journal_ink_strokes s WHERE s.dataset_id = ? AND s.id IN (SELECT id FROM incoming)
                             AND NOT EXISTS (SELECT 1 FROM legacy_journal_versions m JOIN journal_ink_entries e
                                 ON e.dataset_id = m.dataset_id AND e.id = m.entry_id
                                 WHERE e.dataset_id = s.dataset_id AND e.page_id = s.page_id)
                           LIMIT 1""",
                        identities, dataset, kind, key, dataset,
                    ) { true }.isNotEmpty()) {
                    conflict("CONFLICT_CHANGED", "This record overlaps an existing journal import.")
                }
                registerIdentities(dataset, kind, key, identities)
                check(execute(
                    """INSERT INTO editable_record_versions (dataset_id, id, kind, logical_key, root_id, sha256, parents, archive)
                       SELECT dataset_id, id, kind, logical_key, root_id, sha256, ?, archive
                       FROM editable_record_staging WHERE dataset_id = ? AND epoch_id = ? AND id = ?""",
                    parentsSql, dataset, epoch, id.sql(),
                ) == 1)
                execute(
                    "DELETE FROM editable_record_heads WHERE dataset_id = ? AND kind = ? AND logical_key = ? AND version_id = ANY(?)",
                    dataset, kind, key, parentsSql,
                )
                execute("INSERT INTO editable_record_heads VALUES (?, ?, ?, ?)", dataset, kind, key, id.sql())
                execute("DELETE FROM editable_record_staging WHERE dataset_id = ? AND epoch_id = ? AND id = ?", dataset, epoch, id.sql())
            } finally {
                parentsSql.free()
            }
        }
    }

    private fun Connection.registerIdentities(dataset: UUID, kind: String, key: String, identities: String) {
        execute(
            """INSERT INTO editable_record_identities
               (dataset_id, id, kind, logical_key, role, parent_id, created_at, immutable_digest)
               SELECT ?, id, ?, ?, role, parent_id, created_at, immutable_digest
               FROM jsonb_to_recordset(CAST(? AS jsonb))
                 AS i(id uuid, role text, parent_id uuid, created_at text, immutable_digest text)
               ON CONFLICT (dataset_id, id) DO NOTHING""",
            dataset, kind, key, identities,
        )
        if (query(
                """SELECT 1 FROM jsonb_to_recordset(CAST(? AS jsonb))
                   AS i(id uuid, role text, parent_id uuid, created_at text, immutable_digest text)
                   JOIN editable_record_identities r ON r.dataset_id = ? AND r.id = i.id
                   WHERE r.kind <> ? OR r.logical_key <> ? OR r.role <> i.role
                     OR r.parent_id IS DISTINCT FROM i.parent_id OR r.created_at <> i.created_at
                     OR r.immutable_digest IS DISTINCT FROM i.immutable_digest LIMIT 1""",
                identities, dataset, kind, key,
            ) { true }.isNotEmpty()) {
            conflict("CONFLICT_CHANGED", "A record identity or immutable stroke already belongs to different content.")
        }
    }

    fun migrateJournal(scope: SyncScope, entryId: Uuid): Uuid = database.transaction {
        lockEpoch(scope, write = true)
        val dataset = scope.dataset.sql()
        query("SELECT version_id FROM legacy_journal_versions WHERE dataset_id = ? AND entry_id = ?", dataset, entryId.sql()) {
            it.uuid(1)
        }.singleOrNull()?.let { return@transaction it }

        val (page, payload) = query(
            "SELECT page_id, payload FROM journal_ink_entries WHERE dataset_id = ? AND id = ?", dataset, entryId.sql(),
        ) { it.uuid(1) to it.getString(2) }.singleOrNull()
            ?: throw ApiFailure(404, "NOT_FOUND", "Journal entry not found.")
        val entry = JournalInkJson.readEntry(Json.parseToJsonElement(payload).jsonObject)
        check(entry.metadata.id == entryId && entry.pages.single().metadata.id == page)
        val key = entry.date.toString()
        if (heads(dataset, "journal", key).isNotEmpty()) {
            conflict("CONFLICT_CHANGED", "A versioned record already exists for this journal date.")
        }
        val (count, size) = query(
            "SELECT count(*), coalesce(sum(octet_length(bytes)), 0) FROM journal_ink_strokes WHERE dataset_id = ? AND page_id = ?",
            dataset, page.sql(),
        ) { it.getLong(1) to it.getLong(2) }.single()
        if (count > 49_998 || size > 2_000_000L * 20 + count * 12) {
            throw ApiFailure(413, "LIMIT_EXCEEDED", "The journal exceeds the portable record limits.")
        }
        var pointCount = 0L
        val strokes = query(
            """SELECT id, stroke_order, sha256, payload, bytes FROM journal_ink_strokes
               WHERE dataset_id = ? AND page_id = ? ORDER BY stroke_order""",
            dataset, page.sql(),
        ) {
            val bytes = it.getBytes(5)
            check(BackupArchive.sha256(bytes) == it.getString(3))
            pointCount += (bytes.size - 12L) / 20
            if (pointCount > 2_000_000L) {
                throw ApiFailure(413, "LIMIT_EXCEEDED", "The journal exceeds the portable point limit.")
            }
            val stroke = JournalInkJson.readStroke(Json.parseToJsonElement(it.getString(4)).jsonObject, bytes)
            check(stroke.metadata.id == it.uuid(1) && stroke.pageId == page && stroke.order == it.getLong(2))
            check(StrokePointCodec.encode(stroke.points).contentEquals(bytes))
            stroke
        }
        val snapshot = JournalSnapshot(journals = listOf(entry), strokes = strokes)
        val bytes = EditableRecordArchive.encode(snapshot)
        val archive = EditableRecordArchive.fromSnapshot(snapshot)
        registerIdentities(dataset, archive.kind, archive.key, archive.identities.toString())
        val version = Uuid.random()
        execute(
            """INSERT INTO editable_record_versions (dataset_id, id, kind, logical_key, root_id, sha256, parents, archive)
               VALUES (?, ?, 'journal', ?, ?, ?, ARRAY[]::uuid[], ?)""",
            dataset, version.sql(), key, entryId.sql(), BackupArchive.sha256(bytes), bytes,
        )
        execute("INSERT INTO editable_record_heads VALUES (?, 'journal', ?, ?)", dataset, key, version.sql())
        execute("INSERT INTO legacy_journal_versions VALUES (?, ?, ?)", dataset, entryId.sql(), version.sql())
        version
    }

    data class HabitCheck(val id: Uuid, val habit: Uuid, val date: LocalDate, val checked: Boolean)
    data class HabitReceipt(val version: Uuid, val conflicted: Boolean)

    fun editHabit(scope: SyncScope, base: Uuid, edit: HabitCheck): HabitReceipt = database.transaction {
        require(edit.id != base)
        lockEpoch(scope, write = true)
        val dataset = scope.dataset.sql()
        val prior = query(
            """SELECT base_version_id, habit_id, local_date::text, checked, conflicted
               FROM editable_habit_checks WHERE dataset_id = ? AND id = ?""",
            dataset, edit.id.sql(),
        ) {
            val matches = it.uuid(1) == base && it.uuid(2) == edit.habit &&
                it.getString(3) == edit.date.toString() && it.getBoolean(4) == edit.checked
            matches to it.getBoolean(5)
        }.singleOrNull()
        if (prior != null) {
            if (!prior.first) conflict("IDEMPOTENCY_MISMATCH", "The completion operation ID already has different content.")
            return@transaction HabitReceipt(edit.id, prior.second)
        }
        if (query("SELECT 1 FROM editable_record_versions WHERE dataset_id = ? AND id = ?", dataset, edit.id.sql()) {
                true
            }.isNotEmpty()) conflict("IDEMPOTENCY_MISMATCH", "The operation ID is already in use.")
        val source = source(dataset, base)
        require(source.kind == "habits" && source.key == JournalMonth.from(edit.date).toString())
        val head = heads(dataset, source.kind, source.key).singleOrNull()?.id
            ?: conflict("CONFLICT_CHANGED", "Resolve the current habit versions before making another edit.")
        val current = habitLedger(dataset, head)
        val previous = if (base == head) current else habitLedger(dataset, base)
        if (previous.metadata.id != current.metadata.id ||
            previous.activeHabits.none { it.metadata.id == edit.habit } ||
            current.activeHabits.none { it.metadata.id == edit.habit }) {
            conflict("CONFLICT_CHANGED", "This habit is no longer active in the current ledger.")
        }
        val before = previous.completions.find { it.habitId == edit.habit && it.date == edit.date }
        val latest = current.completions.find { it.habitId == edit.habit && it.date == edit.date }
        val conflicted = before != latest && (latest?.checked ?: false) != edit.checked
        val parent = if (conflicted) base else head
        val updated = if (latest?.checked == edit.checked) current else {
            val original = latest ?: before
            val at = maxOf(Clock.System.now(), current.metadata.updatedAt, original?.metadata?.updatedAt ?: current.metadata.updatedAt)
            val metadata = original?.metadata?.revised(at) ?: RecordMetadata(Uuid.random(), at, at)
            val completion = HabitCompletion(metadata, edit.habit, edit.date, edit.checked)
            current.copy(metadata = current.metadata.revised(at), completions = current.completions.filterNot { it.habitId == edit.habit && it.date == edit.date } + completion)
        }
        val snapshot = JournalSnapshot(habits = listOf(updated))
        val archive = EditableRecordArchive.fromSnapshot(snapshot)
        val bytes = EditableRecordArchive.encode(snapshot)
        registerIdentities(dataset, archive.kind, archive.key, archive.identities.toString())
        execute(
            """INSERT INTO editable_record_versions (dataset_id, id, kind, logical_key, root_id, sha256, parents, archive)
               VALUES (?, ?, 'habits', ?, ?, ?, ARRAY[CAST(? AS uuid)], ?)""",
            dataset, edit.id.sql(), archive.key, archive.root.sql(), BackupArchive.sha256(bytes), parent.sql(), bytes,
        )
        if (!conflicted) execute(
            "DELETE FROM editable_record_heads WHERE dataset_id = ? AND kind = 'habits' AND logical_key = ? AND version_id = ?",
            dataset, archive.key, head.sql(),
        )
        execute("INSERT INTO editable_record_heads VALUES (?, 'habits', ?, ?)", dataset, archive.key, edit.id.sql())
        execute(
            "INSERT INTO editable_habit_checks VALUES (?, ?, ?, ?, CAST(? AS date), ?, ?)",
            dataset, edit.id.sql(), base.sql(), edit.habit.sql(), edit.date.toString(), edit.checked, conflicted,
        )
        HabitReceipt(edit.id, conflicted)
    }

    private fun Connection.habitLedger(dataset: UUID, version: Uuid) = query(
        "SELECT archive FROM editable_record_versions WHERE dataset_id = ? AND id = ? AND kind = 'habits'",
        dataset, version.sql(),
    ) { EditableRecordArchive.decode(it.getBytes(1)).habits.single() }.single()

    data class Variant(val id: Uuid, val sequence: Long)
    data class Review(val kind: String, val key: String, val variants: List<Variant>)
    private data class Source(val kind: String, val key: String, val digest: String)

    fun review(scope: SyncScope, version: Uuid): Review = database.transaction {
        lockEpoch(scope)
        val dataset = scope.dataset.sql()
        val source = source(dataset, version)
        Review(source.kind, source.key, heads(dataset, source.kind, source.key))
    }

    /** Keep the selected archive byte-for-byte and retain all superseded variants in history. */
    fun resolve(scope: SyncScope, selected: Uuid, id: Uuid, reviewed: Set<Uuid>) {
        require(reviewed.size in 2..32 && selected in reviewed && id !in reviewed)
        database.transaction {
            lockEpoch(scope, write = true)
            val dataset = scope.dataset.sql()
            val (kind, key, digest) = source(dataset, selected)
            val parentsSql = createArrayOf("uuid", reviewed.map { it.sql() }.toTypedArray())
            try {
                val prior = query(
                    """SELECT kind = ? AND logical_key = ? AND sha256 = ? AND parents @> ? AND parents <@ ?
                       FROM editable_record_versions WHERE dataset_id = ? AND id = ?""",
                    kind, key, digest, parentsSql, parentsSql, dataset, id.sql(),
                ) { it.getBoolean(1) }.singleOrNull()
                if (prior != null) {
                    if (!prior) conflict("IDEMPOTENCY_MISMATCH", "The resolution ID already has different content.")
                    return@transaction
                }
                if (heads(dataset, kind, key).map { it.id }.toSet() != reviewed) {
                    conflict("CONFLICT_CHANGED", "The current record versions changed. Review them again.")
                }
                check(execute(
                    """INSERT INTO editable_record_versions (dataset_id, id, kind, logical_key, root_id, sha256, parents, archive)
                       SELECT dataset_id, ?, kind, logical_key, root_id, sha256, ?, archive
                       FROM editable_record_versions WHERE dataset_id = ? AND id = ?""",
                    id.sql(), parentsSql, dataset, selected.sql(),
                ) == 1)
                execute("DELETE FROM editable_record_heads WHERE dataset_id = ? AND kind = ? AND logical_key = ?", dataset, kind, key)
                execute("INSERT INTO editable_record_heads VALUES (?, ?, ?, ?)", dataset, kind, key, id.sql())
            } finally {
                parentsSql.free()
            }
        }
    }

    private fun Connection.source(dataset: UUID, version: Uuid) = query(
        "SELECT kind, logical_key, sha256 FROM editable_record_versions WHERE dataset_id = ? AND id = ?", dataset, version.sql(),
    ) { Source(it.getString(1), it.getString(2), it.getString(3)) }.singleOrNull()
        ?: throw ApiFailure(404, "NOT_FOUND", "Record version not found.")

    private fun Connection.heads(dataset: UUID, kind: String, key: String) = query(
        """SELECT v.id, v.sequence FROM editable_record_heads h
           JOIN editable_record_versions v ON h.dataset_id = v.dataset_id AND h.version_id = v.id
           WHERE h.dataset_id = ? AND h.kind = ? AND h.logical_key = ? ORDER BY v.sequence""",
        dataset, kind, key,
    ) { Variant(it.uuid(1), it.getLong(2)) }

    fun versions(scope: SyncScope, after: Long, limit: Int) = database.transaction {
        lockEpoch(scope)
        query(
            """SELECT v.id, v.sequence, v.kind, v.logical_key, v.root_id, v.sha256, v.parents::text[],
                      h.version_id IS NOT NULL
               FROM editable_record_versions v LEFT JOIN editable_record_heads h
                 ON h.dataset_id = v.dataset_id AND h.kind = v.kind AND h.logical_key = v.logical_key AND h.version_id = v.id
               WHERE v.dataset_id = ? AND v.sequence > ?
               ORDER BY v.sequence LIMIT ?""",
            scope.dataset.sql(), after, limit + 1,
        ) {
            val parents = it.getArray(7)
            try {
                Version(it.uuid(1), it.getLong(2), it.getString(3), it.getString(4), it.uuid(5), it.getString(6),
                    (parents.array as Array<*>).map { parent -> parent.toString() }.sorted(), it.getBoolean(8))
            } finally {
                parents.free()
            }
        }
    }

    fun download(scope: SyncScope, id: Uuid): Download = database.transaction {
        lockEpoch(scope)
        query("SELECT sha256, archive FROM editable_record_versions WHERE dataset_id = ? AND id = ?", scope.dataset.sql(), id.sql()) {
            Download(it.getString(1), it.getBytes(2))
        }.singleOrNull() ?: throw ApiFailure(404, "NOT_FOUND", "Record version not found.")
    }

    fun purgeExpiredUploads() = database.transaction {
        for (table in listOf("editable_record_staging", "staged_ink")) {
            execute(
                """DELETE FROM $table WHERE ctid IN (SELECT ctid FROM $table WHERE expires_at <= now()
                    ORDER BY expires_at LIMIT 256 FOR UPDATE SKIP LOCKED)""",
            )
        }
    }

    companion object { const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024 }
}
