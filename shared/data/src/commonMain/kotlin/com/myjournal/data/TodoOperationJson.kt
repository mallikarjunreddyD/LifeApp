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
        put("sequence", operation.sequence)
        put("recordType", "todo")
        put("recordId", operation.record.metadata.id.toString())
        put("type", if (operation.record.deleted) "delete" else "put")
        put("parents", JsonArray(operation.parents.map(Uuid::toString).sorted().map(::JsonPrimitive)))
        put("record", encodeTodo(operation.record))
    }.toString()

    fun decode(text: String): TodoOperation {
        require(text.length <= 16_384) { "To-do operation exceeds size limit" }
        checkJsonSizeAndDepth(text)
        return with(Json.parseToJsonElement(text).jsonObject) {
            fun string(key: String) = getValue(key).jsonPrimitive.content
            require(string("format") == "LifeAppOperation" && getValue("protocolVersion").jsonPrimitive.int == 1)
            require(string("recordType") == "todo")
            val record = decodeTodoPayload(getValue("record").jsonObject.toString())
            require(Uuid.parse(string("recordId")) == record.metadata.id)
            require(string("type") == if (record.deleted) "delete" else "put")
            val parents = getValue("parents").jsonArray.map { Uuid.parse(it.jsonPrimitive.content) }
            require(parents.size == parents.toSet().size)
            TodoOperation(Uuid.parse(string("operationId")), Uuid.parse(string("deviceId")),
                getValue("sequence").jsonPrimitive.long, parents.toSet(), record)
        }
    }
}
