package com.myjournal.android

import android.net.Uri
import com.myjournal.data.BackupArchive
import com.myjournal.data.CloudBackupEnvelope
import com.myjournal.domain.CloudBackupStorage
import com.myjournal.domain.CloudBackupVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Drive files are immutable, hidden in appDataFolder and named without private content. */
class DriveBackupStorage(private val accessToken: String) : CloudBackupStorage {
    override suspend fun create(encryptedArchive: ByteArray) =
        createVerified(newId(), encryptedArchive)

    internal suspend fun newId() = withContext(Dispatchers.IO) {
        val ids = json(request(api("/files/generateIds", "count" to "1", "space" to "appDataFolder")).body)
        ids.getValue("ids").jsonArray.single().jsonPrimitive.content.also(::validateId)
    }

    internal suspend fun createVerified(id: String, encryptedArchive: ByteArray) = withContext(Dispatchers.IO) {
        validateId(id)
        require(encryptedArchive.size in 1..CloudBackupEnvelope.MAX_ENVELOPE_BYTES)
        val metadata = buildJsonObject {
            put("id", id)
            put("name", "lifeapp-backup-${Uuid.random()}.bin")
            put("parents", JsonArray(listOf(JsonPrimitive("appDataFolder"))))
            put("mimeType", "application/octet-stream")
            put("appProperties", buildJsonObject { put("lifeapp", "backup-v1") })
        }
        val url = endpoint("https://www.googleapis.com/upload/drive/v3/files",
            "uploadType" to "resumable", "fields" to "id")
        try {
            val session = request(url, method = "POST", contentType = "application/json; charset=UTF-8",
                body = metadata.toString().encodeToByteArray(),
                headers = mapOf("X-Upload-Content-Type" to "application/octet-stream",
                    "X-Upload-Content-Length" to encryptedArchive.size.toString())).location
            val upload = URI(requireNotNull(session) { "Drive returned no upload session" })
            require(upload.scheme == "https" && upload.host == "www.googleapis.com" &&
                upload.userInfo == null && upload.port in setOf(-1, 443) && upload.path == "/upload/drive/v3/files") {
                "Drive returned an unexpected upload endpoint"
            }
            val result = request(upload.toString(), method = "PUT", contentType = "application/octet-stream",
                body = encryptedArchive, headers = mapOf("Content-Range" to
                    "bytes 0-${encryptedArchive.lastIndex}/${encryptedArchive.size}")).body
            require(json(result).getValue("id").jsonPrimitive.content == id) { "Drive returned a different backup ID" }
        } catch (error: IOException) {
            // A dropped response can follow a successful immutable create. Verify
            // this exact ID before reporting success and never overwrite any file.
            if (runCatching { readFile(id).contentEquals(encryptedArchive) }.getOrDefault(false)) return@withContext
            throw error
        }
        check(readFile(id).contentEquals(encryptedArchive)) { "Drive backup verification failed" }
    }

    override suspend fun list(): List<CloudBackupVersion> = withContext(Dispatchers.IO) {
        val versions = linkedMapOf<String, CloudBackupVersion>()
        val seenTokens = mutableSetOf<String>()
        var page: String? = null
        do {
            val params = mutableListOf(
                "spaces" to "appDataFolder",
                "q" to "trashed = false and appProperties has { key='lifeapp' and value='backup-v1' }",
                "fields" to "nextPageToken,incompleteSearch,files(id,createdTime)",
                "pageSize" to "100",
            )
            page?.let { params.add("pageToken" to it) }
            val root = json(request(api("/files", *params.toTypedArray())).body)
            check(root["incompleteSearch"]?.jsonPrimitive?.booleanOrNull != true) { "Drive did not return a complete backup list" }
            for (item in root.getValue("files").jsonArray) {
                val fields = item.jsonObject
                val id = fields.getValue("id").jsonPrimitive.content
                versions[id] = CloudBackupVersion(id, Instant.parse(fields.getValue("createdTime").jsonPrimitive.content))
            }
            page = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
            check(page == null || seenTokens.size < 100 && seenTokens.add(page)) { "Drive backup listing exceeded the page limit" }
        } while (page != null)
        versions.values.sortedByDescending { it.createdAt }
    }

    override suspend fun download(id: String): ByteArray = withContext(Dispatchers.IO) { readFile(id) }

    internal suspend fun deleteManaged(id: String) = withContext(Dispatchers.IO) {
        validateId(id)
        try {
            request(api("/files/$id"), method = "DELETE")
        } catch (failure: DriveHttpFailure) {
            if (failure.status != 404) throw failure
        }
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Invalid Drive file identifier" }
    }

    private fun readFile(id: String): ByteArray {
        validateId(id)
        return request(api("/files/$id", "alt" to "media"), limit = CloudBackupEnvelope.MAX_ENVELOPE_BYTES.toLong()).body
    }

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject

    private fun api(path: String, vararg params: Pair<String, String>) =
        endpoint("https://www.googleapis.com/drive/v3$path", *params)

    private fun endpoint(base: String, vararg params: Pair<String, String>): String =
        Uri.parse(base).buildUpon().apply {
            params.forEach { (key, value) -> appendQueryParameter(key, value) }
        }.build().toString()

    private data class Response(val body: ByteArray, val location: String?)

    private fun request(url: String, method: String = "GET", contentType: String? = null,
        body: ByteArray? = null, headers: Map<String, String> = emptyMap(), limit: Long = 1024 * 1024): Response {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.requestMethod = method
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw DriveHttpFailure(code, when (code) {
                401 -> "Drive authorization expired. Reauthorize your Google account."
                403 -> "Drive denied this request. Check app consent, Drive API setup and account access."
                429 -> "Drive is rate limiting requests. Try again later."
                else -> "Drive request failed (HTTP $code)."
            })
            return Response(connection.inputStream.use { BackupArchive.boundedRead(it, limit) },
                connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }
}

internal class DriveHttpFailure(val status: Int, message: String) : IOException(message)
