package com.myjournal.data

import com.myjournal.domain.CommittedTodo
import com.myjournal.domain.SyncScope
import com.myjournal.domain.TodoAcknowledgment
import com.myjournal.domain.TodoSyncPage
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** Server response codec. Network authentication and HTTP stay outside shared data. */
object TodoServerJson {
    fun decodePage(text: String): TodoSyncPage = with(read(text)) {
        val entries = getValue("entries").jsonArray
        require(entries.size <= TodoSyncPage.MAX_ENTRIES)
        TodoSyncPage(scope(), entries.map { it.jsonObject.commit() }, getValue("hasMore").jsonPrimitive.boolean)
    }

    fun decodeAcknowledgment(text: String): TodoAcknowledgment = with(read(text)) {
        TodoAcknowledgment(scope(), getValue("commit").jsonObject.commit())
    }

    private fun read(text: String): JsonObject {
        require(text.length <= 2 * 1024 * 1024)
        checkJsonSizeAndDepth(text)
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.scope(): SyncScope = with(getValue("scope").jsonObject) {
        SyncScope(Uuid.parse(getValue("dataset").jsonPrimitive.content), Uuid.parse(getValue("epoch").jsonPrimitive.content))
    }

    private fun JsonObject.commit(): CommittedTodo {
        val value = getValue("sequence").jsonPrimitive
        val sequence = requireNotNull(value.content.toLongOrNull())
        require(value.isString && sequence > 0 && value.content == sequence.toString())
        return CommittedTodo(sequence, TodoOperationJson.decode(getValue("operation").jsonObject.toString()))
    }
}
