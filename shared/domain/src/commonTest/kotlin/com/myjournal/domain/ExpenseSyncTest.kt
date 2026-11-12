package com.myjournal.domain

import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.uuid.Uuid

class ExpenseSyncTest {
    @Test fun concurrentDeleteAndEditKeepBothVersionsAndAnEchoCannotClearTheOutbox() {
        val scope = SyncScope(Uuid.random(), Uuid.random())
        val labels = expenseDefaults()
        val item = Expense(newRecordMetadata(), LocalDate(2026, 11, 12), "Lunch", 12550,
            labels.first().metadata.id, labels.first { it.kind == ExpenseLabelKind.PAYMENT }.metadata.id)
        val root = ExpenseOperation(Uuid.random(), emptySet(), ExpenseMutation.PUT, ExpenseRecord.Item(item))
        val seed = labels.map { ExpenseOperation(Uuid.random(), emptySet(), ExpenseMutation.PUT, ExpenseRecord.Label(it)) } + root
        var state = ExpenseSyncState(scope, "https://example.test").replay(seed.mapIndexed { index, op -> CommittedExpense(index + 1L, op) })
        val local = ExpenseRecord.Item(item.copy(metadata = item.metadata.revised(item.metadata.updatedAt), amountMinor = 20000))
        state = state.append(local, ExpenseMutation.PUT)
        val sent = state.pending.single()
        val removal = ExpenseOperation(Uuid.random(), setOf(root.id), ExpenseMutation.DELETE,
            ExpenseRecord.Item(item.copy(metadata = item.metadata.revised(item.metadata.updatedAt), deleted = true)))
        val echo = CommittedExpense(state.cursor + 2, sent)
        state = state.replay(listOf(CommittedExpense(state.cursor + 1, removal), echo))
        assertEquals(listOf(sent), state.pending)
        assertTrue(state.project().expenses.single().deleted)
        assertEquals(setOf(local, removal.record), state.effectiveHeads().getValue(item.metadata.id).map { it.record }.toSet())
        assertTrue(state.acknowledge(echo).pending.isEmpty())
        assertFailsWith<IllegalArgumentException> { state.append(local, ExpenseMutation.PUT) }
    }
}
