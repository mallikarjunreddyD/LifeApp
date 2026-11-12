package com.myjournal.domain

data class ExpenseCategoryTotal(val category: ExpenseLabel, val amountMinor: Long)
data class ExpenseTotals(val amountMinor: Long, val categories: List<ExpenseCategoryTotal>)

fun ExpenseLedger.spending(year: Int, month: Int? = null): ExpenseTotals {
    require(year in 1..9999 && (month == null || month in 1..12))
    val totals = expenses.asSequence()
        .filter { !it.deleted && it.date.year == year && (month == null || it.date.month.ordinal + 1 == month) }
        .groupingBy { it.categoryId }
        .fold(0L) { sum, expense -> sum + expense.amountMinor }
    val categories = labels.mapNotNull { label ->
        totals[label.metadata.id]?.let { ExpenseCategoryTotal(label, it) }
    }.sortedWith(compareByDescending<ExpenseCategoryTotal> { it.amountMinor }.thenBy { it.category.metadata.id.toString() })
    return ExpenseTotals(categories.sumOf { it.amountMinor }, categories)
}
