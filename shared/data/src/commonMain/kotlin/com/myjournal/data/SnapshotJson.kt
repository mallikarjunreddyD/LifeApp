package com.myjournal.data

import com.myjournal.core.RecordMetadata
import com.myjournal.domain.*
import kotlinx.serialization.json.*
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.io.encoding.Base64

/** Wire format1. JSON includes full records; ZIP uses separate versioned LJPT files. */
object SnapshotJson {
    private val json = Json { prettyPrint = true }
    fun encode(source: JournalSnapshot, externalInk: Boolean = false): String {
        val snapshot = source.canonical()
        snapshot.validate()
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("format", "LifeAppRecords"); put("formatVersion", 1); put("dataSchemaVersion", 6)
            put("metadata", JsonArray(snapshot.metadata.map { encodeMeta(it) }))
            put("journals", JsonArray(snapshot.journals.map { encodeEntry(it) }))
            put("planners", JsonArray(snapshot.planners.map { encodePlanner(it) }))
            put("habits", JsonArray(snapshot.habits.map { encodeLedger(it) }))
            put("notebooks", JsonArray(snapshot.notebooks.map { encodeBook(it) }))
            put("notebookPages", JsonArray(snapshot.notebookPages.map { encodeBookPage(it) }))
            put("todos", JsonArray(snapshot.todos.map(::encodeTodo)))
            put("strokes", JsonArray(snapshot.strokes.map { stroke -> buildJsonObject {
                put("metadata", encodeMeta(stroke.metadata)); put("surfaceId", stroke.pageId.toString())
                put("order", stroke.order); put("brush", stroke.tool.name); put("colorArgb", stroke.colorArgb)
                put("width", stroke.width); put("pointFormatVersion", stroke.formatVersion)
                if (externalInk) put("pointsFile", "ink/${stroke.metadata.id}.ljpt")
                else put("pointsBase64", Base64.encode(StrokePointCodec.encode(stroke.points)))
            } }))
        })
    }
    fun decode(text: String, expectedDataSchemaVersion: Int? = null, readInk: ((String) -> ByteArray)? = null): JournalSnapshot {
        checkJsonSizeAndDepth(text)
        val root = json.parseToJsonElement(text).jsonObject
        require(root.getValue("format").jsonPrimitive.content == "LifeAppRecords")
        require(root.getValue("formatVersion").jsonPrimitive.int == 1 && root.getValue("dataSchemaVersion").jsonPrimitive.int in 4..6) { "Unsupported record format/schema" }
        val dataVersion = root.getValue("dataSchemaVersion").jsonPrimitive.int
        require(expectedDataSchemaVersion == null || expectedDataSchemaVersion == dataVersion) { "Manifest and record schema disagree" }
        root.getValue("journals").jsonArray.forEach { entry -> entry.jsonObject.getValue("pages").jsonArray.forEach { page ->
            val fields = page.jsonObject
            if (dataVersion >= 5) require("archived" in fields) { "Missing journal page visibility" }
            else require(fields["archived"]?.jsonPrimitive?.boolean != true) { "Schema4 has no archived journal pages" }
        } }
        require(dataVersion >= 6 || "todos" !in root) { "Unexpected To-do section" }
        return with(root) { JournalSnapshot(
            getValue("metadata").jsonArray.map { decodeMeta(it.jsonObject) },
            getValue("journals").jsonArray.map { decodeEntry(it.jsonObject) },
            getValue("planners").jsonArray.map { decodePlanner(it.jsonObject) },
            getValue("habits").jsonArray.map { decodeLedger(it.jsonObject) },
            getValue("notebooks").jsonArray.map { decodeBook(it.jsonObject) },
            getValue("notebookPages").jsonArray.map { decodeBookPage(it.jsonObject) },
            getValue("strokes").jsonArray.map { element -> with(element.jsonObject) {
                val metadata = decodeMeta(getValue("metadata").jsonObject)
                val bytes = if (readInk != null) {
                    val path = getValue("pointsFile").jsonPrimitive.content
                    require(path == "ink/${metadata.id}.ljpt")
                    require("pointsBase64" !in this)
                    readInk(path)
                } else Base64.decode(getValue("pointsBase64").jsonPrimitive.content)
                InkStroke(metadata, Uuid.parse(getValue("surfaceId").jsonPrimitive.content), getValue("order").jsonPrimitive.long,
                    BrushTool.valueOf(getValue("brush").jsonPrimitive.content), getValue("colorArgb").jsonPrimitive.long,
                    getValue("width").jsonPrimitive.float, StrokePointCodec.decode(bytes), getValue("pointFormatVersion").jsonPrimitive.int)
            } },
            if (dataVersion >= 6) getValue("todos").jsonArray.map { decodeTodo(it.jsonObject) } else emptyList(),
        ).also { it.validate() } }
    }
}

/** Bound nesting before invoking a JSON tree parser; ignore brackets inside strings. */
fun checkJsonSizeAndDepth(text: String) {
    require(text.length <= 64 * 1024 * 1024) { "JSON too large" }
    var depth = 0; var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else if (c == '"') quoted = true
        else if (c == '{' || c == '[') { depth++; require(depth <= 32) { "JSON nesting too deep" } }
        else if (c == '}' || c == ']') { depth--; require(depth >= 0) }
    }
    require(!quoted && depth == 0) { "Truncated JSON" }
}

private fun encodeMeta(value: RecordMetadata) = buildJsonObject {
    put("id", JsonPrimitive(value.id.toString()))
    put("createdAt", JsonPrimitive(value.createdAt.toString()))
    put("updatedAt", JsonPrimitive(value.updatedAt.toString()))
    put("schemaVersion", JsonPrimitive(value.schemaVersion))
    put("revision", JsonPrimitive(value.revision))
}
private fun decodeMeta(value: JsonObject): RecordMetadata = with(value) { RecordMetadata(
    Uuid.parse(getValue("id").jsonPrimitive.content),
    Instant.parse(getValue("createdAt").jsonPrimitive.content),
    Instant.parse(getValue("updatedAt").jsonPrimitive.content),
    getValue("schemaVersion").jsonPrimitive.int,
    getValue("revision").jsonPrimitive.long
) }

private fun encodeJournalPage(value: JournalPage) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("entryId", JsonPrimitive(value.entryId.toString()))
    put("order", JsonPrimitive(value.order))
    put("width", JsonPrimitive(value.width))
    put("height", JsonPrimitive(value.height))
    put("paper", JsonPrimitive(value.paper.toString()))
    put("archived", JsonPrimitive(value.archived))
}
private fun decodeJournalPage(value: JsonObject): JournalPage = with(value) { JournalPage(
    decodeMeta(getValue("metadata").jsonObject),
    Uuid.parse(getValue("entryId").jsonPrimitive.content),
    getValue("order").jsonPrimitive.int,
    getValue("width").jsonPrimitive.float,
    getValue("height").jsonPrimitive.float,
    PaperTemplate.valueOf(getValue("paper").jsonPrimitive.content),
    get("archived")?.jsonPrimitive?.boolean ?: false
) }

private fun encodeEntry(value: JournalEntry) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("date", JsonPrimitive(value.date.toString()))
    put("pages", JsonArray(value.pages.map { encodeJournalPage(it) }))
}
private fun decodeEntry(value: JsonObject): JournalEntry = with(value) { JournalEntry(
    decodeMeta(getValue("metadata").jsonObject),
    LocalDate.parse(getValue("date").jsonPrimitive.content),
    getValue("pages").jsonArray.map { decodeJournalPage(it.jsonObject) }
) }

private fun encodeLayout(value: CalendarLayout) = buildJsonObject {
    put("month", JsonPrimitive(value.month.toString()))
    put("version", JsonPrimitive(value.version))
    put("rows", JsonPrimitive(value.rows))
    put("width", JsonPrimitive(value.width))
    put("headerHeight", JsonPrimitive(value.headerHeight))
    put("cellHeight", JsonPrimitive(value.cellHeight))
}
private fun decodeLayout(value: JsonObject): CalendarLayout = with(value) { CalendarLayout(
    JournalMonth.parse(getValue("month").jsonPrimitive.content),
    getValue("version").jsonPrimitive.int,
    getValue("rows").jsonPrimitive.int,
    getValue("width").jsonPrimitive.float,
    getValue("headerHeight").jsonPrimitive.float,
    getValue("cellHeight").jsonPrimitive.float
) }

private fun encodePlanner(value: MonthlyPlanner) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("layout", encodeLayout(value.layout))
}
private fun decodePlanner(value: JsonObject): MonthlyPlanner = with(value) { MonthlyPlanner(
    decodeMeta(getValue("metadata").jsonObject),
    decodeLayout(getValue("layout").jsonObject)
) }

private fun encodeHabit(value: HabitDefinition) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("ledgerId", JsonPrimitive(value.ledgerId.toString()))
    put("name", JsonPrimitive(value.name))
    put("order", JsonPrimitive(value.order))
    put("category", JsonPrimitive(value.category))
    put("colorArgb", JsonPrimitive(value.colorArgb))
    put("target", JsonPrimitive(value.target))
    put("archived", JsonPrimitive(value.archived))
}
private fun decodeHabit(value: JsonObject): HabitDefinition = with(value) { HabitDefinition(
    decodeMeta(getValue("metadata").jsonObject),
    Uuid.parse(getValue("ledgerId").jsonPrimitive.content),
    getValue("name").jsonPrimitive.content,
    getValue("order").jsonPrimitive.int,
    getValue("category").takeUnless { it is JsonNull }?.jsonPrimitive?.let { value -> value.content },
    getValue("colorArgb").takeUnless { it is JsonNull }?.jsonPrimitive?.let { value -> value.long },
    getValue("target").takeUnless { it is JsonNull }?.jsonPrimitive?.let { value -> value.int },
    getValue("archived").jsonPrimitive.boolean
) }

private fun encodeCompletion(value: HabitCompletion) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("habitId", JsonPrimitive(value.habitId.toString()))
    put("date", JsonPrimitive(value.date.toString()))
    put("checked", JsonPrimitive(value.checked))
}
private fun decodeCompletion(value: JsonObject): HabitCompletion = with(value) { HabitCompletion(
    decodeMeta(getValue("metadata").jsonObject),
    Uuid.parse(getValue("habitId").jsonPrimitive.content),
    LocalDate.parse(getValue("date").jsonPrimitive.content),
    getValue("checked").jsonPrimitive.boolean
) }

private fun encodeLedger(value: MonthlyHabitLedger) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("month", JsonPrimitive(value.month.toString()))
    put("habits", JsonArray(value.habits.map { encodeHabit(it) }))
    put("completions", JsonArray(value.completions.map { encodeCompletion(it) }))
}
private fun decodeLedger(value: JsonObject): MonthlyHabitLedger = with(value) { MonthlyHabitLedger(
    decodeMeta(getValue("metadata").jsonObject),
    JournalMonth.parse(getValue("month").jsonPrimitive.content),
    getValue("habits").jsonArray.map { decodeHabit(it.jsonObject) },
    getValue("completions").jsonArray.map { decodeCompletion(it.jsonObject) }
) }

private fun encodeBook(value: Notebook) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("name", JsonPrimitive(value.name))
    put("description", JsonPrimitive(value.description))
    put("defaultTemplate", JsonPrimitive(value.defaultTemplate.toString()))
    put("archived", JsonPrimitive(value.archived))
}
private fun decodeBook(value: JsonObject): Notebook = with(value) { Notebook(
    decodeMeta(getValue("metadata").jsonObject),
    getValue("name").jsonPrimitive.content,
    getValue("description").takeUnless { it is JsonNull }?.jsonPrimitive?.let { value -> value.content },
    PaperTemplate.valueOf(getValue("defaultTemplate").jsonPrimitive.content),
    getValue("archived").jsonPrimitive.boolean
) }

private fun encodeBookPage(value: NotebookPage) = buildJsonObject {
    put("metadata", encodeMeta(value.metadata))
    put("notebookId", JsonPrimitive(value.notebookId.toString()))
    put("order", JsonPrimitive(value.order))
    put("title", JsonPrimitive(value.title))
    put("paper", JsonPrimitive(value.paper.toString()))
    put("width", JsonPrimitive(value.width))
    put("height", JsonPrimitive(value.height))
    put("archived", JsonPrimitive(value.archived))
}
private fun decodeBookPage(value: JsonObject): NotebookPage = with(value) { NotebookPage(
    decodeMeta(getValue("metadata").jsonObject),
    Uuid.parse(getValue("notebookId").jsonPrimitive.content),
    getValue("order").jsonPrimitive.int,
    getValue("title").takeUnless { it is JsonNull }?.jsonPrimitive?.let { value -> value.content },
    PaperTemplate.valueOf(getValue("paper").jsonPrimitive.content),
    getValue("width").jsonPrimitive.float,
    getValue("height").jsonPrimitive.float,
    getValue("archived").jsonPrimitive.boolean
) }

internal fun encodeTodo(todo: Todo) = buildJsonObject {
    put("metadata", encodeMeta(todo.metadata))
    put("title", todo.title)
    put("deadline", todo.deadline.toString())
    put("completedAt", todo.completedAt?.toString())
    put("deleted", todo.deleted)
}

private fun decodeTodo(value: JsonObject): Todo = with(value) {
    Todo(
        decodeMeta(getValue("metadata").jsonObject),
        getValue("title").jsonPrimitive.content,
        LocalDate.parse(getValue("deadline").jsonPrimitive.content),
        getValue("completedAt").jsonPrimitive.contentOrNull?.let(Instant::parse),
        getValue("deleted").jsonPrimitive.boolean,
    )
}

internal fun decodeTodoPayload(value: String): Todo = decodeTodo(Json.parseToJsonElement(value).jsonObject)
