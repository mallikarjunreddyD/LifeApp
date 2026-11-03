package com.myjournal.domain

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

// Implementations must publish local committed data and propagate failures.
// An absent record emits null; deletion of an absent ID is a no-op.
interface NotebookRepository {
    fun observeAll(): Flow<List<Notebook>>
    fun observe(id: Uuid): Flow<Notebook?>
    suspend fun save(record: Notebook)
    suspend fun delete(id: Uuid)
}
