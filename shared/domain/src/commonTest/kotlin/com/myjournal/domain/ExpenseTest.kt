package com.myjournal.domain

import kotlinx.datetime.LocalDate
import kotlin.test.*

class ExpenseTest {
    @Test fun rupeeInputIsExactAndUsesIndianGrouping() {
        assertEquals(10000L, parseExpensePaise("100"))
        assertEquals(12550L, parseExpensePaise("125.50"))
        assertEquals(125075L, parseExpensePaise("1,250.75"))
        assertEquals(12500000L, parseExpensePaise("1,25,000"))
        assertEquals(MAX_EXPENSE_PAISE, parseExpensePaise("99,99,99,999.99"))
        assertEquals("₹1,25,000.00", formatExpensePaise(12500000))
        assertEquals("₹0.01", formatExpensePaise(1))
        listOf("0", "-1", "1.001", "10,00", "1e3", "1000000000", "").forEach { text ->
            assertFailsWith<IllegalArgumentException> { parseExpensePaise(text) }
        }
        assertEquals(expenseNameKey("  École Gym "), expenseNameKey("école gym"))
    }

    @Test fun historicalReferencesRemainValidAfterArchive() {
        val category = expenseDefaults().first().copy(archived = true)
        val method = expenseDefaults().first { it.kind == ExpenseLabelKind.PAYMENT }
        val record = Expense(newRecordMetadata(), LocalDate(2024, 2, 29), "दवा", 1, category.metadata.id, method.metadata.id)
        ExpenseLedger(listOf(record.copy(deleted = true)), listOf(category, method)).validate()
        assertFailsWith<IllegalArgumentException> { ExpenseLedger(listOf(record), listOf(category)).validate() }
        assertFailsWith<IllegalArgumentException> { record.copy(amountMinor = 0) }
        assertFailsWith<IllegalArgumentException> { record.copy(date = LocalDate(0, 1, 1)) }
    }
}
