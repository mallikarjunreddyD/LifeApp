package com.myjournal.data

import com.myjournal.domain.ExpenseLabelKind
import com.myjournal.domain.ExpenseLedger
import com.myjournal.domain.expenseDecimal
import kotlinx.serialization.json.JsonArray

object ExpenseExport {
    fun json(ledger: ExpenseLedger): Map<String, JsonArray> {
        val canonical = ledger.canonical()
        return mapOf(
            "expenses.json" to JsonArray(canonical.expenses.map { ExpenseSyncJson.wireRecord(it.expenseJson()) }),
            "categories.json" to JsonArray(canonical.labels.filter { it.kind == ExpenseLabelKind.CATEGORY }
                .map { ExpenseSyncJson.wireRecord(it.expenseJson()) }),
            "payment-methods.json" to JsonArray(canonical.labels.filter { it.kind == ExpenseLabelKind.PAYMENT }
                .map { ExpenseSyncJson.wireRecord(it.expenseJson()) }),
        )
    }

    fun csv(ledger: ExpenseLedger): String = buildString {
        append("id,date,name,amount,currency,category,payment_method,remarks\r\n")
        val names = ledger.labels.associate { it.metadata.id to it.name }
        ledger.canonical().expenses.filterNot { it.deleted }.forEach { item ->
            append(listOf(item.metadata.id.toString(), item.date.toString(), csvText(item.name),
                expenseDecimal(item.amountMinor), item.currency, csvText(names.getValue(item.categoryId)),
                csvText(names.getValue(item.paymentMethodId)), csvText(item.remarks)).joinToString(","))
            append("\r\n")
        }
    }

    private fun csvText(text: String): String {
        val formula = text.firstOrNull() in listOf('\t', '\r', '\n') ||
            text.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '＝', '＋', '－', '＠')
        return "\"" + (if (formula) "'" else "") + text.replace("\"", "\"\"") + "\""
    }
}
