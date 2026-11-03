package com.myjournal.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class SaveStatus(val pending: Int, val error: String?)

/** Caller prevents new UI edits for the entire snapshot/restore operation. */
suspend fun awaitCommittedWrites(timeoutMillis: Long = 30_000, status: () -> SaveStatus) {
    withTimeout(timeoutMillis) {
        while (true) {
            val current = status()
            check(current.error == null) { "Unsaved or unreadable editor data. Resolve its error and retry before exporting or restoring." }
            require(current.pending >= 0)
            if (current.pending == 0) break
            delay(20)
        }
    }
}
