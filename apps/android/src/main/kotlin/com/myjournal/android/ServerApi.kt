package com.myjournal.android

import com.myjournal.data.BackupArchive
import com.myjournal.data.JournalInkJson
import com.myjournal.data.TodoOperationJson
import com.myjournal.data.TodoServerJson
import com.myjournal.data.checkJsonSizeAndDepth
import com.myjournal.domain.JournalEntry
import com.myjournal.domain.ServerEndpoint
import com.myjournal.domain.SyncCheckpoint
import com.myjournal.domain.SyncScope
import com.myjournal.domain.TodoOperation
import com.myjournal.domain.TodoSyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class ServerSession(val scope: SyncScope, val expiresAt: Instant)
internal data class ServerCredential(
    val endpoint: ServerEndpoint,
    val username: String,
    val token: String,
    val session: ServerSession,
)

internal class ServerHttpFailure(val status: Int, val code: String?, val retrySeconds: Long?) : IOException(when {
    status == 401 -> "Server sign-in expired. Sign in again."
    code == "SCOPE_MISMATCH" || status == 410 -> "The server dataset, recovery epoch or cursor changed. Local records were kept."
    code == "CONFLICT_CHANGED" -> "The server task conflict changed. Review its current versions."
    status == 429 -> "The server is rate limiting requests. Try again later."
    status >= 500 -> "The LifeApp server is unavailable."
    else -> "The LifeApp server declined the request (HTTP $status)."
}) {
    val retryable get() = status == 429 || status >= 500
}

/** All paths are fixed protocol routes. Production uses Android's default TLS and hostname verification. */
internal class ServerApi(
    val endpoint: ServerEndpoint,
    private val connection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    suspend fun ready() {
        val status = request("/ready").objectValue().getValue("status").jsonPrimitive.content
        require(status == "ready") { "This endpoint did not report LifeApp readiness" }
    }

    suspend fun login(username: String, password: String, secondFactor: String? = null): ServerCredential {
        require(username.length in 1..254 && password.length in 1..1024) { "Enter a username and password" }
        val body = buildJsonObject {
            put("client", "android")
            put("username", username)
            put("password", password)
            secondFactor?.let { put("secondFactor", it) }
        }
        val response = request("/v1/session", "POST", body = body).objectValue()
        val token = response.getValue("accessToken").jsonPrimitive.content
        require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid LifeApp session response" }
        return ServerCredential(endpoint, username, token, response.session())
    }

    suspend fun session(token: String): ServerSession = request("/v1/session", token = token).objectValue().session()

    suspend fun logout(token: String) {
        request("/v1/session", "DELETE", token)
    }

    suspend fun visibleTaskIds(token: String): Set<Uuid> {
        val found = mutableSetOf<Uuid>()
        var cursor: Uuid? = null
        var pages = 0
        do {
            check(++pages <= 500) { "The server task list exceeds the verification limit" }
            val query = "/v1/todos?limit=100" + (cursor?.let { "&cursor=$it" } ?: "")
            val response = request(query, token = token).objectValue()
            val items = response.getValue("items").jsonArray
            require(items.size <= 100)
            for (item in items) {
                val id = item.jsonObject.getValue("metadata").jsonObject.getValue("id").jsonPrimitive.content
                require(found.add(Uuid.parse(id))) { "Duplicate task in server projection" }
            }
            val next = response.getValue("next").jsonPrimitive.contentOrNull?.let(Uuid::parse)
            require(next == null || (items.isNotEmpty() && cursor != next)) { "Invalid task pagination" }
            cursor = next
        } while (cursor != null)
        return found
    }

    suspend fun journalEntries(session: ServerSession, token: String): List<JournalEntry> {
        val entries = mutableListOf<JournalEntry>()
        var cursor: String? = null
        repeat(500) {
            val path = "/v1/journal/entries?limit=100" + (cursor?.let { "&cursor=$it" } ?: "")
            val response = request(path, token = token, journalScope = session.scope).objectValue()
            val items = response.getValue("items").jsonArray
            require(items.size <= 100) { "Invalid server journal page size" }
            for (item in items) {
                val entry = JournalInkJson.readEntry(item.jsonObject)
                val id = entry.metadata.id.toString()
                require(entry.pages.size == 1 && !entry.pages.single().archived) { "Invalid server journal page" }
                require(cursor?.let { id > it } ?: true) { "Invalid server journal entry order" }
                entries += entry
                cursor = id
            }
            val next = response.getValue("next").jsonPrimitive.contentOrNull ?: return entries
            require(items.size == 100 && next == cursor) { "Invalid server journal cursor" }
        }
        error("The server journal inventory exceeds 50,000 entries.")
    }

    fun transport(
        session: ServerSession,
        token: String,
        onRejectedResolution: suspend (TodoOperation) -> Unit = {},
    ): TodoSyncTransport = object : TodoSyncTransport {
        override val scope = session.scope
        override suspend fun pull(after: SyncCheckpoint) = TodoServerJson.decodePage(
            request("/v1/sync/pull?after=${after.sequence}&epoch=${after.scope.epoch}", token = token),
        )

        override suspend fun submit(operation: TodoOperation) = try {
            val path = if (operation.parents.size > 1) "/v1/conflicts/${operation.record.metadata.id}/resolve" else "/v1/sync/push"
            val body = buildJsonObject {
                put("scope", buildJsonObject {
                    put("dataset", scope.dataset.toString())
                    put("epoch", scope.epoch.toString())
                })
                put("operation", Json.parseToJsonElement(TodoOperationJson.encode(operation)))
            }
            TodoServerJson.decodeAcknowledgment(request(path, "POST", token, body))
        } catch (failure: ServerHttpFailure) {
            if (failure.status == 409 && failure.code == "CONFLICT_CHANGED" && operation.parents.size > 1) {
                onRejectedResolution(operation)
            }
            throw failure
        }
    }

    private suspend fun request(
        path: String, method: String = "GET", token: String? = null, body: JsonObject? = null, journalScope: SyncScope? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val conn = connection(URI(endpoint.origin + path).toURL())
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.requestMethod = method
                conn.setRequestProperty("Accept", "application/json")
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                journalScope?.let {
                    conn.setRequestProperty("X-LifeApp-Dataset", it.dataset.toString())
                    conn.setRequestProperty("X-LifeApp-Epoch", it.epoch.toString())
                }
                if (body != null) {
                    val bytes = body.toString().encodeToByteArray()
                    require(bytes.size <= 16_384) { "Request exceeds the LifeApp limit" }
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                if (status !in 200..299) {
                    val code = runCatching {
                        conn.errorStream?.use { BackupArchive.boundedRead(it, 16_384).decodeToString() }
                            ?.objectValue()?.get("code")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    throw ServerHttpFailure(status, code, conn.getHeaderField("Retry-After")?.toLongOrNull()?.takeIf { it >= 0 })
                }
                if (status == 204) return@withContext ""
                require(conn.contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true) {
                    "The endpoint did not return LifeApp JSON"
                }
                conn.inputStream.use { BackupArchive.boundedRead(it, 2L * 1024 * 1024).decodeToString(throwOnInvalidSequence = true) }
            } finally {
                conn.disconnect()
            }
        }

    private fun String.objectValue(): JsonObject {
        checkJsonSizeAndDepth(this)
        return Json.parseToJsonElement(this).jsonObject
    }

    private fun JsonObject.session(): ServerSession {
        val scope = getValue("scope").jsonObject
        return ServerSession(
            SyncScope(Uuid.parse(scope.getValue("dataset").jsonPrimitive.content),
                Uuid.parse(scope.getValue("epoch").jsonPrimitive.content)),
            Instant.parse(getValue("expiresAt").jsonPrimitive.content),
        )
    }
}
