package com.myjournal.server

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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
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
    val inkUploads = Semaphore(2)
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
            get("/journal/ink/{strokeId}") {
                val scope = call.authenticate(auth).second.scope
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
private fun inkTooLarge(): Nothing = throw ApiFailure(413, "LIMIT_EXCEEDED", "The stroke exceeds 20,000,012 bytes.")
private fun tooLarge(): Nothing = throw ApiFailure(413, "LIMIT_EXCEEDED", "The request exceeds 16 KiB.")
private suspend fun <T> databaseCall(block: () -> T): T = withContext(databaseDispatcher) { block() }
