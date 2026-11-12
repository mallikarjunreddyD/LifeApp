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
    @Test fun spendingUsesCalendarPeriodsAndIncludesArchivedCategories() {
        val defaults = expenseDefaults()
        val category = defaults.first().copy(name = "Renamed", archived = true)
        val payment = defaults.first { it.kind == ExpenseLabelKind.PAYMENT }
        val leap = Expense(newRecordMetadata(), LocalDate(2024, 2, 29), "Train", 1, category.metadata.id, payment.metadata.id)
        val ledger = ExpenseLedger(listOf(leap, leap.copy(date = LocalDate(2024, 1, 1), amountMinor = 100),
            leap.copy(date = LocalDate(2025, 1, 1), amountMinor = 10), leap.copy(deleted = true)), listOf(category, payment))
        assertEquals(1L, ledger.spending(2024, 2).amountMinor)
        assertEquals(listOf(ExpenseCategoryTotal(category, 101)), ledger.spending(2024).categories)
        assertEquals(10L, ledger.spending(2025).amountMinor)
        assertTrue(ledger.spending(2024, 12).categories.isEmpty())
    }
}
