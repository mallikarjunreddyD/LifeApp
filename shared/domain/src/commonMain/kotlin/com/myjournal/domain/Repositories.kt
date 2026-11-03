package com.myjournal.domain

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

// Implementations must publish local committed data and propagate failures.
// An absent record emits null; deletion of an absent ID is a no-op.
interface MonthlyPlanRepository {
    fun observeAll(): Flow<List<MonthlyPlan>>
    fun observe(id: Uuid): Flow<MonthlyPlan?>
    suspend fun save(record: MonthlyPlan)
    suspend fun delete(id: Uuid)
}

interface HabitRepository {
    fun observeAll(): Flow<List<Habit>>
    fun observe(id: Uuid): Flow<Habit?>
    suspend fun save(record: Habit)
    suspend fun delete(id: Uuid)
}

interface NotebookRepository {
    fun observeAll(): Flow<List<Notebook>>
    fun observe(id: Uuid): Flow<Notebook?>
    suspend fun save(record: Notebook)
    suspend fun delete(id: Uuid)
}
