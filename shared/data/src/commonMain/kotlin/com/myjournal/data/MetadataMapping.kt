package com.myjournal.data

import com.myjournal.core.RecordMetadata
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun RecordMetadata.toRow() = MetadataRow(id.toString(), createdAt.toString(), updatedAt.toString(), schemaVersion, revision)
fun MetadataRow.toMetadata() = RecordMetadata(Uuid.parse(id), Instant.parse(createdAt), Instant.parse(updatedAt), schemaVersion, revision)
