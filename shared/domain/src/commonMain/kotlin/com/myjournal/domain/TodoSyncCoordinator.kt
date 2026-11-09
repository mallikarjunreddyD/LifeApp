package com.myjournal.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Server-issued dataset and recovery generation. A repository can bind only once. */
data class SyncScope(val dataset: Uuid, val epoch: Uuid)

data class SyncCheckpoint(val scope: SyncScope, val sequence: Long = 0) {
    init { require(sequence >= 0) }
}

data class CommittedTodo(val sequence: Long, val operation: TodoOperation) {
    init { require(sequence > 0) }
}

data class TodoSyncPage(val scope: SyncScope, val entries: List<CommittedTodo>, val hasMore: Boolean) {
    fun advance(from: SyncCheckpoint): SyncCheckpoint {
        require(scope == from.scope && entries.size <= MAX_ENTRIES)
        require(!hasMore || entries.isNotEmpty())
        var sequence = from.sequence
        for (entry in entries) {
            require(sequence < Long.MAX_VALUE && entry.sequence == sequence + 1) { "Invalid commit sequence" }
            sequence = entry.sequence
        }
        return from.copy(sequence = sequence)
    }

    companion object { const val MAX_ENTRIES = 100 }
}

data class TodoAcknowledgment(val scope: SyncScope, val commit: CommittedTodo)

interface TodoSyncTransport {
    val scope: SyncScope
    suspend fun submit(operation: TodoOperation): TodoAcknowledgment
    suspend fun pull(after: SyncCheckpoint): TodoSyncPage
}

interface TodoChangeJournal {
    suspend fun bind(scope: SyncScope): SyncCheckpoint
    suspend fun pending(): List<TodoOperation>
    suspend fun acknowledge(acknowledgment: TodoAcknowledgment)
    suspend fun applyPage(from: SyncCheckpoint, page: TodoSyncPage): SyncCheckpoint
}

data class TodoSyncProgress(val checkpoint: SyncCheckpoint, val moreAvailable: Boolean)

class TodoSyncCoordinator(
    private val journal: TodoChangeJournal,
    private val transport: TodoSyncTransport,
) {
    private val mutex = Mutex()

    suspend fun synchronize(): TodoSyncProgress = mutex.withLock {
        val scope = transport.scope
        var checkpoint = journal.bind(scope)
        val firstPage = transport.pull(checkpoint)
        checkpoint = journal.applyPage(checkpoint, firstPage)
        // Drain known remote commits first. Work is bounded per call.
        if (firstPage.hasMore) return@withLock TodoSyncProgress(checkpoint, true)
        val pending = journal.pending()
        for (operation in pending.take(TodoSyncPage.MAX_ENTRIES)) {
            val acknowledgment = transport.submit(operation)
            require(acknowledgment.scope == scope && acknowledgment.commit.operation == operation) {
                "Acknowledgment does not match the submitted operation"
            }
            journal.acknowledge(acknowledgment)
        }
        val finalPage = transport.pull(checkpoint)
        checkpoint = journal.applyPage(checkpoint, finalPage)
        TodoSyncProgress(checkpoint, finalPage.hasMore || pending.size > TodoSyncPage.MAX_ENTRIES)
    }
}
