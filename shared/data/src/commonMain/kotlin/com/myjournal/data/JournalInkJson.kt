package com.myjournal.data

import com.myjournal.domain.BrushTool
import com.myjournal.domain.InkStroke
import com.myjournal.domain.JournalEntry
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

object JournalInkJson {
    fun entry(value: JsonObject): JournalEntry = decodeEntry(value).also {
        require(value == encodeEntry(it))
        require(it.metadata.schemaVersion == 1 && it.pages.all { page -> page.metadata.schemaVersion == 1 })
    }

    fun stroke(value: JsonObject, bytes: ByteArray): InkStroke = with(value) {
        val metadata = decodeMeta(getValue("metadata").jsonObject)
        val stroke = InkStroke(
            metadata,
            Uuid.parse(getValue("surfaceId").jsonPrimitive.content),
            getValue("order").jsonPrimitive.long,
            BrushTool.valueOf(getValue("brush").jsonPrimitive.content),
            getValue("colorArgb").jsonPrimitive.long,
            getValue("width").jsonPrimitive.float,
            StrokePointCodec.decode(bytes),
            getValue("pointFormatVersion").jsonPrimitive.int,
        )
        require(metadata.schemaVersion == 1 && value == strokeHeader(stroke))
        stroke
    }

    fun strokeHeader(stroke: InkStroke) = buildJsonObject {
        put("metadata", encodeMeta(stroke.metadata))
        put("surfaceId", stroke.pageId.toString())
        put("order", stroke.order)
        put("brush", stroke.tool.name)
        put("colorArgb", stroke.colorArgb)
        put("width", stroke.width)
        put("pointFormatVersion", stroke.formatVersion)
    }
}
