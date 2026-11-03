package com.myjournal.data

import com.myjournal.core.RecordMetadata
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MetadataMappingTest {
    @Test fun roundTripPreservesNanosecondsAndRevision() {
        val instant = Instant.parse("2026-11-03T00:00:00.123456789Z")
        val metadata = RecordMetadata(Uuid.random(), instant, instant, 3, 42)
        assertEquals(metadata, metadata.toRow().toMetadata())
    }
    @Test fun rejectsCorruptStoredIdentity() {
        assertFailsWith<IllegalArgumentException> {
            MetadataRow("invalid", "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", 1, 1).toMetadata()
        }
    }
}
