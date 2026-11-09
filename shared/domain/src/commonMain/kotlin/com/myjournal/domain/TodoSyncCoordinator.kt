package com.myjournal.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TodoSyncCoordinator(
    private val journal: TodoChangeJournal,
    private val cloud: TodoCloud,
) {
    private val mutex = Mutex()

    suspend fun synchronize() = mutex.withLock {
        journal.receive(cloud.read())
        for (operation in journal.pending()) {
            cloud.put(operation)
            journal.acknowledge(operation)
        }
    }
}
