package com.myjournal.data

import com.myjournal.domain.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

internal fun ExpenseLabel.expenseJson() = buildJsonObject {
    put("metadata", encodeMeta(metadata))
    put("kind", kind.name)
    put("name", name)
    put("icon", icon.name)
    put("color", color)
    put("archived", archived)
}

internal fun Expense.expenseJson() = buildJsonObject {
    put("metadata", encodeMeta(metadata))
    put("date", date.toString())
    put("name", name)
    put("amountMinor", amountMinor.toString())
    put("currency", currency)
    put("categoryId", categoryId.toString())
    put("paymentMethodId", paymentMethodId.toString())
    put("remarks", remarks)
    put("deleted", deleted)
}

internal fun JsonObject.expenseLabel() = ExpenseLabel(
    decodeMeta(getValue("metadata").jsonObject), ExpenseLabelKind.valueOf(text("kind")), text("name"),
    ExpenseIcon.valueOf(text("icon")), getValue("color").jsonPrimitive.int, getValue("archived").jsonPrimitive.boolean,
)

internal fun JsonObject.expense(): Expense {
    require(text("currency") == "INR")
    val amount = text("amountMinor")
    require(Regex("[1-9][0-9]{0,10}").matches(amount))
    return Expense(decodeMeta(getValue("metadata").jsonObject), LocalDate.parse(text("date")), text("name"), amount.toLong(),
        Uuid.parse(text("categoryId")), Uuid.parse(text("paymentMethodId")), text("remarks"), getValue("deleted").jsonPrimitive.boolean)
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content

internal fun ExpenseLedger.expenseJson() = buildJsonObject {
    val ledger = canonical()
    put("items", JsonArray(ledger.expenses.map { it.expenseJson() }))
    put("labels", JsonArray(ledger.labels.map { it.expenseJson() }))
}

internal fun JsonObject.expenseLedger() = ExpenseLedger(
    getValue("items").jsonArray.map { it.jsonObject.expense() },
    getValue("labels").jsonArray.map { it.jsonObject.expenseLabel() },
).also { it.validate() }
