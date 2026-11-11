package com.myjournal.server

import com.myjournal.data.StrokePointCodec
import com.myjournal.domain.SyncScope
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.uuid.Uuid

internal class StagedInkStore(private val database: Database) {
    fun stage(scope: SyncScope, id: Uuid, sha256: String, bytes: ByteArray) {
        require(bytes.size in 32..MAX_BYTES)
        require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == sha256) {
            "Stroke checksum mismatch"
        }
        StrokePointCodec.decode(bytes)
        database.transaction {
            lockEpoch(scope, write = true)
            val dataset = scope.dataset.sql()
            val epoch = scope.epoch.sql()
            execute("DELETE FROM staged_ink WHERE dataset_id = ? AND (expires_at <= now() OR epoch_id <> ?)", dataset, epoch)
            val existing = query(
                "SELECT sha256, bytes FROM staged_ink WHERE dataset_id = ? AND epoch_id = ? AND id = ?",
                dataset, epoch, id.sql(),
            ) { it.getString(1) to it.getBytes(2) }.singleOrNull()
            if (existing != null) {
                if (existing.first != sha256 || !existing.second.contentEquals(bytes)) {
                    conflict("IDEMPOTENCY_MISMATCH", "The stroke upload ID already has different content.")
                }
                return@transaction
            }
            val (count, size) = query(
                "SELECT count(*), coalesce(sum(octet_length(bytes)), 0) FROM staged_ink WHERE dataset_id = ?",
                dataset,
            ) { it.getLong(1) to it.getLong(2) }.single()
            if (count >= 256 || size + bytes.size > 64L * 1024 * 1024) {
                throw ApiFailure(413, "LIMIT_EXCEEDED", "Staged stroke capacity is full.")
            }
            execute(
                """INSERT INTO staged_ink (dataset_id, epoch_id, id, sha256, bytes, expires_at)
                    VALUES (?, ?, ?, ?, ?, now() + interval '24 hours')""",
                dataset, epoch, id.sql(), sha256, bytes,
            )
        }
    }

    companion object { const val MAX_BYTES = 20_000_012 }
}
