package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val MAX_EXPENSE_PAISE = 99_999_999_999L

fun parseExpensePaise(input: String): Long {
    val text = input.trim()
    require(Regex("(?:[0-9]+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3}|[0-9]{1,3},[0-9]{3})(?:\\.[0-9]{1,2})?").matches(text) && text.length <= 20) {
        "Enter a rupee amount with up to two decimal places."
    }
    val parts = text.replace(",", "").split('.')
    val rupees = parts.first().toLongOrNull() ?: error("Amount exceeds the limit.")
    require(rupees <= MAX_EXPENSE_PAISE / 100) { "Amount exceeds the limit." }
    return (rupees * 100 + parts.getOrNull(1).orEmpty().padEnd(2, '0').toInt()).also {
        require(it in 1..MAX_EXPENSE_PAISE) { "Enter an amount greater than zero." }
    }
}

fun expenseDecimal(paise: Long) = "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

fun formatExpensePaise(paise: Long): String {
    require(paise >= 0)
    val rupees = (paise / 100).toString()
    val grouped = if (rupees.length <= 3) rupees else
        rupees.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + rupees.takeLast(3)
    return "₹$grouped.${(paise % 100).toString().padStart(2, '0')}"
}

/** Locale-independent Unicode lowercasing with whitespace folded to a single space. */
fun expenseNameKey(name: String) = name.trim().split(Regex("[\\s\\p{Z}]+")).joinToString(" ").lowercase()

enum class ExpenseLabelKind { CATEGORY, PAYMENT }
enum class ExpenseIcon(val symbol: String) {
    BASKET("▤"), FOOD("◇"), TRANSPORT("↗"), SHOPPING("▣"), BILL("≡"), HEALTH("+"),
    TRAVEL("✦"), EDUCATION("▱"), ENTERTAINMENT("♪"), PAYMENT("▰"), OTHER("○"),
}

data class ExpenseLabel(
    val metadata: RecordMetadata,
    val kind: ExpenseLabelKind,
    val name: String,
    val icon: ExpenseIcon = ExpenseIcon.OTHER,
    val color: Int = 0x527062,
    val archived: Boolean = false,
) {
    init {
        require(name == name.trim() && name.isNotBlank() && name.length <= 120) { "Enter a name of up to 120 characters." }
        require(color in 0..0xffffff)
    }
}

data class Expense(
    val metadata: RecordMetadata,
    val date: LocalDate,
    val name: String,
    val amountMinor: Long,
    val categoryId: Uuid,
    val paymentMethodId: Uuid,
    val remarks: String = "",
    val deleted: Boolean = false,
) {
    val currency get() = "INR"

    init {
        require(date.year in 1..9999) { "Enter a valid expense date." }
        require(name == name.trim() && name.isNotBlank() && name.length <= 120) { "Enter a name of up to 120 characters." }
        require(amountMinor in 1..MAX_EXPENSE_PAISE) { "Enter a positive amount within the limit." }
        require(remarks.length <= 4000) { "Remarks may contain up to 4,000 characters." }
    }
}

data class ExpenseLedger(val expenses: List<Expense> = emptyList(), val labels: List<ExpenseLabel> = emptyList()) {
    val records get() = expenses.map { it.metadata } + labels.map { it.metadata }

    fun validate() {
        require(records.map { it.id }.distinct().size == records.size && records.all { it.schemaVersion == 1 })
        val kinds = labels.associate { it.metadata.id to it.kind }
        require(expenses.all { kinds[it.categoryId] == ExpenseLabelKind.CATEGORY && kinds[it.paymentMethodId] == ExpenseLabelKind.PAYMENT }) {
            "An expense refers to a missing category or payment method."
        }
    }

    fun canonical() = ExpenseLedger(expenses.sortedBy { it.metadata.id.toString() }, labels.sortedBy { it.metadata.id.toString() })
}

fun expenseDefaults(): List<ExpenseLabel> {
    val categories = listOf("Groceries", "Eating Out", "Transport", "Shopping", "Bills & Utilities", "Health",
        "Travel", "Education", "Entertainment", "Other")
    val payments = listOf("Google Pay", "PhonePe", "Cash", "Credit Card", "Debit Card", "Bank Transfer", "Other")
    val icons = ExpenseIcon.entries.filterNot { it == ExpenseIcon.PAYMENT }
    val colors = listOf(0x527062, 0x97583b, 0x415f83, 0x795683, 0x79613d, 0x965761, 0x3f7474, 0x58648a, 0x88603f, 0x636860)
    val created = Instant.parse("2026-01-01T00:00:00Z")
    return (categories + payments).mapIndexed { index, name ->
        val id = Uuid.parse("00000000-0000-480a-8000-${(index + 1).toString().padStart(12, '0')}")
        val category = index < categories.size
        ExpenseLabel(RecordMetadata(id, created, created), if (category) ExpenseLabelKind.CATEGORY else ExpenseLabelKind.PAYMENT,
            name, if (category) icons[index] else ExpenseIcon.PAYMENT, colors[index % colors.size])
    }
}

interface ExpenseRepository {
    fun observe(): Flow<ExpenseLedger>
    suspend fun seed()
    suspend fun saveExpense(expense: Expense, previousRevision: Long?)
    suspend fun saveLabel(label: ExpenseLabel, previousRevision: Long?)
    suspend fun setDeleted(id: Uuid, deleted: Boolean, previousRevision: Long)
}
