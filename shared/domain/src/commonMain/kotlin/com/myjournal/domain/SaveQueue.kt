package com.myjournal.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Single-dispatcher FIFO; failed heads and their immutable payloads stay until Retry. */
class SaveQueue<T>(scope: CoroutineScope, private val persist: suspend (T) -> Unit, private val changed: () -> Unit) {
    private val edits = ArrayDeque<T>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    val count get() = edits.size
    var error: String? = null
        private set
    fun any(predicate: (T) -> Boolean) = edits.any(predicate)
    init {
        scope.launch {
            for (ignored in signal) {
                if (error != null) continue
                while (edits.isNotEmpty()) {
                    try { persist(edits.first()); edits.removeFirst(); changed() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = "Changes are not saved. ${failure.message ?: "Local storage failed"}"
                        changed(); break
                    }
                }
            }
        }
    }
    fun add(edit: T) { edits.addLast(edit); changed(); signal.trySend(Unit) }
    fun retry() { error = null; changed(); signal.trySend(Unit) }
}
