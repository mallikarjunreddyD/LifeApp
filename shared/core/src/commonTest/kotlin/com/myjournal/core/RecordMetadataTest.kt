package com.myjournal.core

import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RecordMetadataTest {
    private val start = Instant.parse("2026-11-03T00:00:00Z")
    private fun record() = RecordMetadata(Uuid.parse("550e8400-e29b-41d4-a716-446655440000"), start, start)

    @Test fun revisionPreservesIdentityAndCreationTime() {
        val original = record()
        val revised = original.revised(Instant.parse("2026-11-03T01:00:00Z"))
        assertEquals(original.id, revised.id)
        assertEquals(original.createdAt, revised.createdAt)
        assertEquals(2L, revised.revision)
        assertEquals(1L, original.revision)
    }
    @Test fun rejectsInvalidMetadata() {
        assertFailsWith<IllegalArgumentException> { record().copy(schemaVersion = 0) }
        assertFailsWith<IllegalArgumentException> { record().copy(revision = 0) }
        assertFailsWith<IllegalArgumentException> { record().copy(updatedAt = Instant.parse("2026-11-02T00:00:00Z")) }
    }
    @Test fun rejectsBackwardRevisionAndOverflow() {
        assertFailsWith<IllegalArgumentException> { record().revised(Instant.parse("2026-11-02T00:00:00Z")) }
        assertFailsWith<IllegalStateException> { record().copy(revision = Long.MAX_VALUE).revised(start) }
    }
}
