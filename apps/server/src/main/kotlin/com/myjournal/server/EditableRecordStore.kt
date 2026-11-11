package com.myjournal.server

import com.myjournal.domain.SyncScope
import java.security.MessageDigest
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

    fun commit(scope: SyncScope, id: Uuid, sha256: String, parents: Set<Uuid>) {
        require(parents.size <= 32 && id !in parents)
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
                val heads = query(
                    "SELECT version_id FROM editable_record_heads WHERE dataset_id = ? AND kind = ? AND logical_key = ?",
                    dataset, kind, key,
                ) { it.uuid(1) }.toSet()
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
                           SELECT 1 FROM journal_ink_entries WHERE dataset_id = ? AND
                             ((? = 'journal' AND local_date::text = ?) OR id IN (SELECT id FROM incoming) OR page_id IN (SELECT id FROM incoming))
                           UNION ALL
                           SELECT 1 FROM journal_ink_strokes WHERE dataset_id = ? AND id IN (SELECT id FROM incoming)
                           LIMIT 1""",
                        identities, dataset, kind, key, dataset,
                    ) { true }.isNotEmpty()) {
                    conflict("CONFLICT_CHANGED", "This record overlaps an existing journal import.")
                }
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
