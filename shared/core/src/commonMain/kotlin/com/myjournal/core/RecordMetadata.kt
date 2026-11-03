package com.myjournal.core

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Stable identity and local revision information; no cloud semantics implied. */
data class RecordMetadata(
    val id: Uuid,
    val createdAt: Instant,
    val updatedAt: Instant,
    val schemaVersion: Int = 1,
    val revision: Long = 1,
) {
    init {
        require(schemaVersion > 0)
        require(revision > 0)
        require(updatedAt >= createdAt)
    }

    fun revised(at: Instant): RecordMetadata {
        require(at >= updatedAt)
        check(revision < Long.MAX_VALUE) { "Revision exhausted" }
        return copy(updatedAt = at, revision = revision + 1)
    }
}
