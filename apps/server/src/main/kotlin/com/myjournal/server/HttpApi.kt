package com.myjournal.server

import com.myjournal.core.RecordMetadata
import com.myjournal.data.workoutJson
import com.myjournal.data.TodoOperationJson
import com.myjournal.data.checkJsonSizeAndDepth
import com.myjournal.domain.CommittedTodo
import com.myjournal.domain.SyncScope
import com.myjournal.domain.TodoOperation
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlinx.datetime.LocalDate
import java.security.MessageDigest
import java.sql.SQLException
import kotlin.uuid.Uuid

private const val SESSION_COOKIE = "__Host-lifeapp_session"
private const val REQUEST_ID = "X-Request-ID"
private const val BODY_LIMIT = 16_384
private val databaseDispatcher = Dispatchers.IO.limitedParallelism(12)

internal fun Application.lifeApp(database: Database, auth: Authentication, origin: String) {
    val todos = TodoStore(database)
    val workouts = WorkoutReader(database)
    val stagedInk = StagedInkStore(database)
    val journalInk = JournalInkStore(database)
    val editable = EditableRecordStore(database)
    val inkUploads = Semaphore(2)
    val recordTransfers = Semaphore(1)
    launch(Dispatchers.IO) {
        while (isActive) {
            try {
                editable.purgeExpiredUploads()
            } catch (_: SQLException) {
                log.warn("Could not clean up expired record uploads.")
            }
            delay(15 * 60 * 1000L)
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append(REQUEST_ID, Uuid.random().toString())
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    }
    install(StatusPages) {
        exception<ApiFailure> { call, failure ->
            if (failure.status == 429) call.response.headers.append(HttpHeaders.RetryAfter, "60")
            call.error(failure.status, failure.code, failure.message)
        }
        exception<SQLException> { call, _ ->
            this@lifeApp.log.warn("Database request failed, request ID {}", call.response.headers[REQUEST_ID])
            call.error(503, "UNAVAILABLE", "Database unavailable.")
        }
        exception<Throwable> { call, failure ->
            if (failure is CancellationException) throw failure
            this@lifeApp.log.error("Request failed, request ID {}, error type {}", call.response.headers[REQUEST_ID], failure.javaClass.simpleName)
            call.error(503, "UNAVAILABLE", "Service unavailable.")
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            if (!call.response.isCommitted) call.error(404, "NOT_FOUND", "Resource not found.")
        }
    }
    routing {
        get("/health") { call.json(buildJsonObject { put("status", "ok") }) }
        get("/ready") {
            val ready = databaseCall { runCatching { database.ready() }.getOrDefault(false) }
            if (!ready) throw ApiFailure(503, "UNAVAILABLE", "Database or schema unavailable.")
            call.json(buildJsonObject { put("status", "ready") })
        }
        route("/v1") {
            post("/session") {
                val body = call.body()
                val (browser, username, password) = validated {
                    val required = setOf("client", "username", "password")
                    require(body.keys == required || body.keys == required + "secondFactor")
                    body["secondFactor"]?.let { require(body.text("secondFactor").length in 1..43) }
                    val mode = body.text("client")
                    require(mode == "browser" || mode == "android")
                    val username = body.text("username")
                    val password = body.text("password")
                    require(username.length in 1..254 && password.length in 1..1024)
                    Triple(mode == "browser", username, password.toCharArray())
                }
                val (token, session) = try {
                    if (browser) call.requireOrigin(origin)
                    else if (call.request.headers[HttpHeaders.Origin] != null) forbidden()
                    databaseCall { auth.login(username, password, browser, body["secondFactor"]?.let { body.text("secondFactor") }) }
                } finally {
                    password.fill('\u0000')
                }
                if (browser) call.sessionCookie(token, 43_200)
                call.json(session.json(if (browser) null else token))
            }
            get("/session") { call.json(call.authenticate(auth).second.json()) }
            delete("/session") {
                val (token, session) = call.authenticate(auth)
                call.requireMutation(session, origin)
                databaseCall { auth.revoke(token) }
                if (session.csrfToken != null) call.sessionCookie("", 0)
                call.respond(HttpStatusCode.NoContent)
            }
            put("/ink/staged/{uploadId}") {
                val session = call.authenticate(auth).second
                call.requireMutation(session, origin)
                val (id, scope, checksum) = validated {
                    val id = Uuid.parse(requireNotNull(call.parameters["uploadId"]))
                    val scope = SyncScope(
                        Uuid.parse(requireNotNull(call.request.headers["X-LifeApp-Dataset"])),
                        Uuid.parse(requireNotNull(call.request.headers["X-LifeApp-Epoch"])),
                    )
                    val checksum = requireNotNull(call.request.headers["X-Content-SHA256"])
                    require(checksum.matches(Regex("[0-9a-f]{64}")))
                    require(call.request.contentType().match(ContentType.Application.OctetStream))
                    Triple(id, scope, checksum)
                }
                if (scope != session.scope) conflict("SCOPE_MISMATCH", "The submitted dataset or epoch does not match the session.")
                if ((call.request.contentLength() ?: 0) > StagedInkStore.MAX_BYTES) inkTooLarge()
                if (!inkUploads.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the stroke upload later.")
                try {
                    val bytes = withTimeoutOrNull(30_000) {
                        call.receiveChannel().readBuffer(StagedInkStore.MAX_BYTES.toLong() + 1).readByteArray()
                    } ?: throw ApiFailure(408, "INVALID_REQUEST", "The stroke upload timed out.")
                    if (bytes.size > StagedInkStore.MAX_BYTES) inkTooLarge()
                    databaseCall { validated { stagedInk.stage(scope, id, checksum, bytes) } }
                    call.respond(HttpStatusCode.NoContent)
                } finally {
                    inkUploads.release()
                }
            }
            post("/journal/ink/commit") {
                val session = call.authenticate(auth).second
                call.requireMutation(session, origin)
                val request = call.body()
                val scope = validated {
                    require(request.keys == setOf("scope", "entry", "stroke", "uploadId", "sha256"))
                    val fields = request.getValue("scope").jsonObject
                    require(fields.keys == setOf("dataset", "epoch"))
                    SyncScope(Uuid.parse(fields.text("dataset")), Uuid.parse(fields.text("epoch")))
                }
                if (scope != session.scope) conflict("SCOPE_MISMATCH", "The submitted dataset or epoch does not match the session.")
                if (!inkUploads.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the stroke commit later.")
                try {
                    databaseCall {
                        validated {
                            val digest = request.text("sha256")
                            require(digest.matches(Regex("[0-9a-f]{64}")))
                            journalInk.commit(
                                scope, request.getValue("entry").jsonObject, request.getValue("stroke").jsonObject,
                                Uuid.parse(request.text("uploadId")), digest,
                            )
                        }
                    }
                    call.respond(HttpStatusCode.NoContent)
                } finally {
                    inkUploads.release()
                }
            }
            get("/journal/entries") {
                val scope = call.authenticate(auth).second.scope
                call.requireRecordScope(scope)
                val (after, limit) = call.pagination()
                val rows = databaseCall { journalInk.entries(scope, after, limit) }
                call.json(paged(rows, limit, { it.first }) { it.second })
            }
            post("/journal/entries/{entryId}/migrate") {
                val session = call.authenticate(auth).second
                call.requireMutation(session, origin)
                call.requireRecordScope(session.scope)
                val id = validated { Uuid.parse(requireNotNull(call.parameters["entryId"])) }
                if (!recordTransfers.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the journal migration later.")
                try {
                    val version = databaseCall { validated { editable.migrateJournal(session.scope, id) } }
                    call.json(buildJsonObject { put("versionId", version.toString()) })
                } finally {
                    recordTransfers.release()
                }
            }
            get("/journal/pages/{pageId}/strokes") {
                val scope = call.authenticate(auth).second.scope
                call.requireRecordScope(scope)
                val page = validated { Uuid.parse(requireNotNull(call.parameters["pageId"])) }
                val (after, limit) = call.pagination()
                val rows = databaseCall { journalInk.strokes(scope, page, after, limit) }
                call.json(paged(rows, limit, { it.id }) { stroke ->
                    buildJsonObject { put("stroke", stroke.header); put("sha256", stroke.sha256) }
                })
            }
            get("/journal/ink/{strokeId}") {
                val scope = call.authenticate(auth).second.scope
                call.requireRecordScope(scope, optional = true)
                val id = validated { Uuid.parse(requireNotNull(call.parameters["strokeId"])) }
                if (!inkUploads.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the stroke download later.")
                try {
                    val stroke = databaseCall { journalInk.download(scope, id) }
                    call.response.headers.append("X-Content-SHA256", stroke.sha256)
                    call.respondBytes(stroke.bytes, ContentType.Application.OctetStream)
                } finally {
                    inkUploads.release()
                }
            }
            put("/editable/staged/{versionId}") {
                val session = call.authenticate(auth).second
                call.requireMutation(session, origin)
                call.requireRecordScope(session.scope)
                val id = validated { Uuid.parse(requireNotNull(call.parameters["versionId"])) }
                val digest = validated {
                    require(call.request.contentType().match(ContentType.Application.OctetStream))
                    requireNotNull(call.request.headers["X-Content-SHA256"]).also {
                        require(it.matches(Regex("[0-9a-f]{64}")))
                    }
                }
                val limit = EditableRecordStore.MAX_ARCHIVE_BYTES
                if ((call.request.contentLength() ?: 0) > limit) recordTooLarge()
                if (!recordTransfers.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the record upload later.")
                try {
                    val bytes = withTimeoutOrNull(30_000) {
                        call.receiveChannel().readBuffer(limit + 1).readByteArray()
                    } ?: throw ApiFailure(408, "INVALID_REQUEST", "The record upload timed out.")
                    if (bytes.size > limit) recordTooLarge()
                    databaseCall { validated { editable.stage(session.scope, id, digest, bytes) } }
                    call.respond(HttpStatusCode.NoContent)
                } finally {
                    recordTransfers.release()
                }
            }
            post("/editable/versions/{versionId}/commit") {
                val session = call.authenticate(auth).second
                call.requireMutation(session, origin)
                call.requireRecordScope(session.scope)
                val id = validated { Uuid.parse(requireNotNull(call.parameters["versionId"])) }
                val request = call.body()
                val (digest, parents, requireEmpty) = validated {
                    val fields = setOf("sha256", "parents")
                    require(request.keys == fields || request.keys == fields + "requireEmpty")
                    val requireEmpty = request["requireEmpty"]?.jsonPrimitive?.let {
                        require(!it.isString)
                        requireNotNull(it.booleanOrNull)
                    } ?: false
                    val digest = request.text("sha256")
                    require(digest.matches(Regex("[0-9a-f]{64}")))
                    val parents = request.getValue("parents").jsonArray.map {
                        Uuid.parse(it.jsonPrimitive.also { value -> require(value.isString) }.content)
                    }
                    require(parents.size == parents.toSet().size)
                    Triple(digest, parents.toSet(), requireEmpty)
                }
                databaseCall { validated { editable.commit(session.scope, id, digest, parents, requireEmpty) } }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/editable/versions") {
                val scope = call.authenticate(auth).second.scope
                call.requireRecordScope(scope)
                val (after, limit) = validated {
                    val raw = call.request.queryParameters["after"] ?: "0"
                    val cursor = requireNotNull(raw.toLongOrNull())
                    require(cursor >= 0 && raw == cursor.toString())
                    val size = call.request.queryParameters["limit"]?.let { requireNotNull(it.toIntOrNull()) } ?: 100
                    require(size in 1..100)
                    cursor to size
                }
                val rows = databaseCall { editable.versions(scope, after, limit) }
                val page = rows.take(limit)
                call.json(buildJsonObject {
                    put("scope", scope.json())
                    put("hasMore", rows.size > limit)
                    put("items", JsonArray(page.map {
                        buildJsonObject {
                            put("id", it.id.toString())
                            put("sequence", it.sequence.toString())
                            put("kind", it.kind)
                            put("key", it.key)
                            put("rootId", it.root.toString())
                            put("sha256", it.sha256)
                            put("parents", JsonArray(it.parents.map(::JsonPrimitive)))
                            put("current", it.current)
                        }
                    }))
                })
            }
            get("/editable/versions/{versionId}") {
                val scope = call.authenticate(auth).second.scope
                call.requireRecordScope(scope)
                val id = validated { Uuid.parse(requireNotNull(call.parameters["versionId"])) }
                if (!recordTransfers.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the record download later.")
                try {
                    val archive = databaseCall { editable.download(scope, id) }
                    call.response.headers.append("X-Content-SHA256", archive.sha256)
                    call.respondBytes(archive.bytes, ContentType.Application.OctetStream)
                } finally {
                    recordTransfers.release()
                }
            }
            post("/editable/versions/{versionId}/habit-check") {
                val session = call.authenticate(auth).second
                call.requireMutation(session, origin)
                call.requireRecordScope(session.scope)
                val base = validated { Uuid.parse(requireNotNull(call.parameters["versionId"])) }
                val request = call.body()
                val edit = validated {
                    val fields = setOf("id", "habitId", "date", "checked")
                    require(request.keys == fields || request.keys == fields + "completionMetadata")
                    val source = request["completionMetadata"]?.jsonObject?.let {
                        require(it.keys == setOf("id", "createdAt", "updatedAt", "revision"))
                        RecordMetadata(Uuid.parse(it.text("id")), kotlin.time.Instant.parse(it.text("createdAt")),
                            kotlin.time.Instant.parse(it.text("updatedAt")), revision = it.text("revision").toLong())
                    }
                    val checked = request.getValue("checked").jsonPrimitive
                    require(!checked.isString)
                    val raw = request.text("date")
                    val date = LocalDate.parse(raw)
                    require(date.year in 1..9999 && date.toString() == raw)
                    EditableRecordStore.HabitCheck(Uuid.parse(request.text("id")), Uuid.parse(request.text("habitId")),
                        date, requireNotNull(checked.booleanOrNull), source)
                }
                if (!recordTransfers.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the habit edit later.")
                try {
                    val receipt = databaseCall { validated { editable.editHabit(session.scope, base, edit) } }
                    call.json(buildJsonObject {
                        put("versionId", receipt.version.toString())
                        put("conflicted", receipt.conflicted)
                    })
                } finally {
                    recordTransfers.release()
                }
            }
            get("/editable/versions/{versionId}/review") {
                val scope = call.authenticate(auth).second.scope
                call.requireRecordScope(scope)
                val id = validated { Uuid.parse(requireNotNull(call.parameters["versionId"])) }
                val review = databaseCall { editable.review(scope, id) }
                call.json(buildJsonObject {
                    put("scope", scope.json())
                    put("kind", review.kind)
                    put("key", review.key)
                    put("variants", JsonArray(review.variants.map {
                        buildJsonObject { put("id", it.id.toString()); put("sequence", it.sequence.toString()) }
                    }))
                })
            }
            post("/editable/versions/{versionId}/resolve") {
                val session = call.authenticate(auth).second
                call.requireMutation(session, origin)
                call.requireRecordScope(session.scope)
                val selected = validated { Uuid.parse(requireNotNull(call.parameters["versionId"])) }
                val request = call.body()
                val (id, reviewed) = validated {
                    require(request.keys == setOf("id", "reviewed"))
                    val id = Uuid.parse(request.text("id"))
                    val reviewed = request.getValue("reviewed").jsonArray.map {
                        Uuid.parse(it.jsonPrimitive.also { item -> require(item.isString) }.content)
                    }
                    require(reviewed.size == reviewed.toSet().size)
                    id to reviewed.toSet()
                }
                databaseCall { validated { editable.resolve(session.scope, selected, id, reviewed) } }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/editable/versions/{versionId}/preview") {
                val scope = call.authenticate(auth).second.scope
                call.requireRecordScope(scope)
                val id = validated { Uuid.parse(requireNotNull(call.parameters["versionId"])) }
                if (!recordTransfers.tryAcquire()) throw ApiFailure(429, "RATE_LIMITED", "Retry the preview later.")
                try {
                    val includeHabitIds = call.request.queryParameters["habitIds"] == "true"
                    val preview = databaseCall { EditableRecordArchive.preview(editable.download(scope, id).bytes, includeHabitIds) }
                    val body = preview.toString()
                    if (body.encodeToByteArray().size > 8 * 1024 * 1024) {
                        throw ApiFailure(413, "LIMIT_EXCEEDED", "The record exceeds the browser preview limit.")
                    }
                    call.respondText(body, ContentType.Application.Json)
                } finally {
                    recordTransfers.release()
                }
            }
            get("/bootstrap") {
                val scope = call.authenticate(auth).second.scope
                call.json(buildJsonObject { put("scope", scope.json()); put("sequence", "0") })
            }
            post("/sync/push") { call.submit(auth, todos, origin, resolve = false) }
            post("/conflicts/{recordId}/resolve") { call.submit(auth, todos, origin, resolve = true) }
            get("/sync/pull") {
                val scope = call.authenticate(auth).second.scope
                val (epoch, after) = validated {
                    val raw = requireNotNull(call.request.queryParameters["after"])
                    val after = requireNotNull(raw.toLongOrNull())
                    require(after >= 0 && raw == after.toString())
                    Uuid.parse(requireNotNull(call.request.queryParameters["epoch"])) to after
                }
                if (epoch != scope.epoch) conflict("SCOPE_MISMATCH", "The recovery epoch changed.")
                val page = databaseCall { todos.pull(scope, after) }
                call.json(buildJsonObject {
                    put("scope", page.scope.json())
                    put("entries", JsonArray(page.entries.map(CommittedTodo::json)))
                    put("hasMore", page.hasMore)
                })
            }
            get("/todos") {
                val scope = call.authenticate(auth).second.scope
                val (after, limit) = call.pagination()
                val page = databaseCall { todos.todos(scope, after, limit) }
                call.json(paged(page, limit, { it.record.metadata.id }) { it.recordJson() })
            }
            get("/todos/{recordId}") {
                val scope = call.authenticate(auth).second.scope
                val id = validated { Uuid.parse(requireNotNull(call.parameters["recordId"])) }
                val record = databaseCall { todos.todo(scope, id) }
                    ?: throw ApiFailure(404, "NOT_FOUND", "Task not found.")
                call.json(record.recordJson())
            }
            get("/conflicts") {
                val scope = call.authenticate(auth).second.scope
                val (after, limit) = call.pagination()
                val page = databaseCall { todos.conflicts(scope, after, limit) }
                call.json(paged(page, limit, { it.first }) { (id, heads) ->
                    buildJsonObject {
                        put("recordId", id.toString())
                        put("heads", JsonArray(heads.map(TodoOperation::json)))
                    }
                })
            }
            get("/backups/status") {
                val scope = call.authenticate(auth).second.scope
                val (state, verified) = databaseCall { todos.backupStatus(scope) }
                call.json(buildJsonObject { put("state", state); put("lastVerifiedAt", verified) })
            }
            get("/exercises") {
                val scope = call.authenticate(auth).second.scope
                val (after, limit) = call.pagination()
                val page = databaseCall { workouts.exercises(scope, after, limit) }
                call.json(paged(page, limit, { it.metadata.id }) { it.workoutJson() })
            }
            get("/workouts") {
                val scope = call.authenticate(auth).second.scope
                val (after, limit) = call.pagination()
                val pageSize = minOf(limit, 10)
                val page = databaseCall { workouts.workouts(scope, after, pageSize) }
                call.json(paged(page, pageSize, { it.metadata.id }) { it.workoutJson() })
            }
            get("/workouts/{recordId}") {
                val scope = call.authenticate(auth).second.scope
                val id = validated { Uuid.parse(requireNotNull(call.parameters["recordId"])) }
                val record = databaseCall { workouts.workout(scope, id) }
                    ?: throw ApiFailure(404, "NOT_FOUND", "Workout not found.")
                call.json(record.workoutJson())
            }
        }
    }
}

private suspend fun ApplicationCall.submit(auth: Authentication, todos: TodoStore, origin: String, resolve: Boolean) {
    val session = authenticate(auth).second
    requireMutation(session, origin)
    val body = body()
    val (scope, operation) = validated {
        require(body.keys == setOf("scope", "operation"))
        val scope = body.getValue("scope").jsonObject
        require(scope.keys == setOf("dataset", "epoch"))
        val op = body.getValue("operation").jsonObject
        val operation = TodoOperationJson.decode(op.toString())
        require(operation.json() == op)
        if (resolve) require(Uuid.parse(requireNotNull(parameters["recordId"])) == operation.record.metadata.id)
        SyncScope(Uuid.parse(scope.text("dataset")), Uuid.parse(scope.text("epoch"))) to operation
    }
    if (scope != session.scope) conflict("SCOPE_MISMATCH", "The submitted dataset or epoch does not match the session.")
    val commit = databaseCall { todos.push(session.scope, operation, resolve || operation.parents.size > 1) }
    json(buildJsonObject { put("scope", session.scope.json()); put("commit", commit.json()) })
}

private suspend fun ApplicationCall.authenticate(auth: Authentication): Pair<String, Session> {
    val cookie = request.cookies[SESSION_COOKIE]
    val bearer = request.headers[HttpHeaders.Authorization]
    if (cookie != null && bearer != null) unauthorized()
    val token = cookie ?: bearer?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7) ?: unauthorized()
    return token to databaseCall { auth.session(token, cookie != null) }
}

private fun ApplicationCall.requireOrigin(origin: String) {
    if (request.headers.getAll(HttpHeaders.Origin) != listOf(origin)) forbidden()
}

private fun ApplicationCall.requireMutation(session: Session, origin: String) {
    val expected = session.csrfToken ?: return
    requireOrigin(origin)
    val actual = request.headers["X-CSRF-Token"] ?: forbidden()
    if (!MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())) forbidden()
}

private fun forbidden(): Nothing = throw ApiFailure(403, "FORBIDDEN", "Request origin or authorization is invalid.")

private fun ApplicationCall.sessionCookie(value: String, age: Int) {
    response.headers.append(HttpHeaders.SetCookie, "$SESSION_COOKIE=$value; Path=/; Max-Age=$age; Secure; HttpOnly; SameSite=Strict")
}

private suspend fun ApplicationCall.body(): JsonObject {
    if (!request.contentType().match(ContentType.Application.Json)) invalid()
    if ((request.contentLength() ?: 0) > BODY_LIMIT) tooLarge()
    val bytes = receiveChannel().readBuffer(BODY_LIMIT.toLong() + 1).readByteArray()
    if (bytes.size > BODY_LIMIT) tooLarge()
    return validated {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        checkJsonSizeAndDepth(text)
        Json.parseToJsonElement(text).jsonObject
    }
}

private fun ApplicationCall.requireRecordScope(scope: SyncScope, optional: Boolean = false) {
    val dataset = request.headers["X-LifeApp-Dataset"]
    val epoch = request.headers["X-LifeApp-Epoch"]
    if (optional && dataset == null && epoch == null) return
    val requested = validated { SyncScope(Uuid.parse(requireNotNull(dataset)), Uuid.parse(requireNotNull(epoch))) }
    if (requested != scope) conflict("SCOPE_MISMATCH", "The requested dataset or epoch does not match the session.")
}

private fun ApplicationCall.pagination(): Pair<Uuid?, Int> = validated {
    val cursor = request.queryParameters["cursor"]?.let(Uuid::parse)
    val limit = request.queryParameters["limit"]?.let { requireNotNull(it.toIntOrNull()) } ?: 100
    require(limit in 1..100)
    cursor to limit
}

private fun <T> paged(rows: List<T>, limit: Int, key: (T) -> Uuid, item: (T) -> JsonObject) = buildJsonObject {
    val page = rows.take(limit)
    put("items", JsonArray(page.map(item)))
    put("next", if (rows.size > limit) page.last().let(key).toString() else null)
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun SyncScope.json() = buildJsonObject { put("dataset", dataset.toString()); put("epoch", epoch.toString()) }
private fun TodoOperation.json() = Json.parseToJsonElement(TodoOperationJson.encode(this)).jsonObject
private fun TodoOperation.recordJson() = json().getValue("record").jsonObject
private fun CommittedTodo.json() = buildJsonObject { put("sequence", sequence.toString()); put("operation", operation.json()) }
private fun Session.json(token: String? = null) = buildJsonObject {
    put("expiresAt", expiresAt.toString())
    put("scope", scope.json())
    csrfToken?.let { put("csrfToken", it) }
    token?.let { put("accessToken", it) }
}
private suspend fun ApplicationCall.json(value: JsonObject, status: Int = 200) =
    respondText(value.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))

private suspend fun ApplicationCall.error(status: Int, code: String, message: String) = json(buildJsonObject {
    put("code", code); put("message", message); put("requestId", response.headers[REQUEST_ID] ?: Uuid.random().toString())
}, status)

private inline fun <T> validated(block: () -> T): T = try {
    block()
} catch (_: IllegalArgumentException) {
    invalid()
} catch (_: NoSuchElementException) {
    invalid()
} catch (_: java.nio.charset.CharacterCodingException) {
    invalid()
}

private fun invalid(): Nothing = throw ApiFailure(400, "INVALID_REQUEST", "Malformed or invalid request.")
private fun recordTooLarge(): Nothing = throw ApiFailure(413, "LIMIT_EXCEEDED", "The record archive exceeds 16 MiB.")
private fun inkTooLarge(): Nothing = throw ApiFailure(413, "LIMIT_EXCEEDED", "The stroke exceeds 20,000,012 bytes.")
private fun tooLarge(): Nothing = throw ApiFailure(413, "LIMIT_EXCEEDED", "The request exceeds 16 KiB.")
private suspend fun <T> databaseCall(block: () -> T): T = withContext(databaseDispatcher) { block() }
