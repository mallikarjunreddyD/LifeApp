package com.myjournal.data

import com.myjournal.domain.*
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

object ExpenseSyncJson {
    fun operation(value: ExpenseOperation) = buildJsonObject {
        put("version", 1)
        put("id", value.id.toString())
        put("parents", JsonArray(value.parents.map(Uuid::toString).sorted().map(::JsonPrimitive)))
        put("mutation", value.mutation.name)
        when (val record = value.record) {
            is ExpenseRecord.Item -> { put("kind", "expense"); put("record", wireRecord(record.value.expenseJson())) }
            is ExpenseRecord.Label -> { put("kind", "label"); put("record", wireRecord(record.value.expenseJson())) }
        }
    }

    fun operation(value: JsonObject): ExpenseOperation {
        require(value.keys == setOf("version", "id", "parents", "mutation", "kind", "record"))
        require(value.getValue("version").jsonPrimitive.int == 1)
        val parents = value.getValue("parents").jsonArray.map { Uuid.parse(it.jsonPrimitive.content) }
        require(parents.distinct().size == parents.size)
        val wire = value.getValue("record").jsonObject
        val meta = wire.getValue("metadata").jsonObject
        val revision = meta.string("revision").toLong()
        val record = JsonObject(wire + ("metadata" to JsonObject(meta + ("revision" to JsonPrimitive(revision)))))
        return ExpenseOperation(Uuid.parse(value.string("id")), parents.toSet(), ExpenseMutation.valueOf(value.string("mutation")),
            when (value.string("kind")) {
                "expense" -> ExpenseRecord.Item(record.expense())
                "label" -> ExpenseRecord.Label(record.expenseLabel())
                else -> error("Invalid expense record kind.")
            })
    }

    internal fun wireRecord(record: JsonObject): JsonObject {
        val meta = record.getValue("metadata").jsonObject
        return JsonObject(record + ("metadata" to JsonObject(meta + ("revision" to
            JsonPrimitive(meta.getValue("revision").jsonPrimitive.content)))))
    }

    fun committed(value: CommittedExpense) = buildJsonObject {
        put("sequence", value.sequence.toString())
        put("operation", operation(value.operation))
    }

    fun committed(value: JsonObject) = CommittedExpense(value.string("sequence").toLong(),
        operation(value.getValue("operation").jsonObject))

    internal fun state(value: ExpenseSyncState) = buildJsonObject {
        put("dataset", value.scope.dataset.toString())
        put("epoch", value.scope.epoch.toString())
        put("origin", value.origin)
        put("cursor", value.cursor.toString())
        put("heads", JsonArray(value.heads.values.flatten().map(::operation)))
        put("pending", JsonArray(value.pending.map(::operation)))
        put("acknowledged", JsonArray(value.acknowledged.map { JsonPrimitive(it.toString()) }))
    }

    internal fun state(value: JsonObject) = ExpenseSyncState(
        SyncScope(Uuid.parse(value.string("dataset")), Uuid.parse(value.string("epoch"))), value.string("origin"),
        value.string("cursor").toLong(), value.operations("heads").groupBy { it.record.metadata.id }, value.operations("pending"),
        value.getValue("acknowledged").jsonArray.map { Uuid.parse(it.jsonPrimitive.content) }.toSet(),
    )

    private fun JsonObject.operations(key: String) = getValue(key).jsonArray.map { operation(it.jsonObject) }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
}
