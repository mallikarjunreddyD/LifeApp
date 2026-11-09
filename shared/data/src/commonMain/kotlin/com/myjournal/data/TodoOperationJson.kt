package com.myjournal.data

import com.myjournal.domain.TodoOperation
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** Portable operation bytes to be carried inside an authenticated transport envelope. */
object TodoOperationJson {
    fun encode(operation: TodoOperation): String = buildJsonObject {
        put("format", "LifeAppOperation")
        put("protocolVersion", 1)
        put("operationId", operation.id.toString())
        put("deviceId", operation.deviceId.toString())
        put("sequence", operation.sequence.toString())
        put("payloadVersion", 1)
        put("recordType", "todo")
        put("recordId", operation.record.metadata.id.toString())
        put("type", if (operation.record.deleted) "delete" else "put")
        put("parents", JsonArray(operation.parents.map(Uuid::toString).sorted().map(::JsonPrimitive)))
        val record = encodeTodo(operation.record)
        val metadata = record.getValue("metadata").jsonObject
        put("record", JsonObject(record + ("metadata" to JsonObject(
            metadata + ("revision" to JsonPrimitive(operation.record.metadata.revision.toString()))
        ))))
    }.toString()

    fun decode(text: String): TodoOperation {
        require(text.length <= 16_384) { "To-do operation exceeds size limit" }
        checkJsonSizeAndDepth(text)
        return with(Json.parseToJsonElement(text).jsonObject) {
            fun string(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
            fun version(key: String): Boolean = getValue(key).jsonPrimitive.let { !it.isString && it.content == "1" }
            require(string("format") == "LifeAppOperation" && version("protocolVersion"))
            require(string("recordType") == "todo" && version("payloadVersion"))
            val payload = getValue("record").jsonObject
            val metadata = payload.getValue("metadata").jsonObject
            val revision = decimal(metadata.getValue("revision").jsonPrimitive)
            val normalized = JsonObject(payload + ("metadata" to JsonObject(metadata + ("revision" to JsonPrimitive(revision)))))
            val record = decodeTodoPayload(normalized.toString())
            require(Uuid.parse(string("recordId")) == record.metadata.id)
            require(string("type") == if (record.deleted) "delete" else "put")
            val parents = getValue("parents").jsonArray.map { Uuid.parse(it.jsonPrimitive.content) }
            require(parents.size == parents.toSet().size)
            TodoOperation(Uuid.parse(string("operationId")), Uuid.parse(string("deviceId")),
                decimal(getValue("sequence").jsonPrimitive), parents.toSet(), record)
        }
    }
    private fun decimal(value: JsonPrimitive): Long {
        require(value.isString)
        val number = requireNotNull(value.content.toLongOrNull())
        require(number > 0 && value.content == number.toString())
        return number
    }
}
