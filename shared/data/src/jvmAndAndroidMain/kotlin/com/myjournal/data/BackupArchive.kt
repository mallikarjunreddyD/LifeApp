package com.myjournal.data

import com.myjournal.domain.*
import kotlinx.serialization.json.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Never extracts archive paths. Every stream and aggregate allocation is bounded. */
object BackupArchive {
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_JSON_BYTES = 8L * 1024 * 1024
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024 + 12
    private val sections = listOf("metadata", "journal", "planner", "habits", "notebooks", "ink")
    private val json = Json { prettyPrint = true }
    data class Validated(val snapshot: JournalSnapshot, val createdAt: Instant, val appVersion: String,
        val kind: String, val counts: Map<String, Int>)
    fun write(file: File, snapshot: JournalSnapshot, kind: String = "backup", appVersion: String = "0.7.0") {
        require(kind in listOf("backup", "vectors")); snapshot.validate()
        val data = SnapshotJson.encode(snapshot, externalInk = true).encodeToByteArray()
        require(data.size <= MAX_JSON_BYTES) { "Record JSON exceeds 8 MiB" }
        data class Item(val path: String, val size: Int, val hash: String)
        val items = mutableListOf<Item>()
        var total = 0L
        try {
            FileOutputStream(file).use { raw ->
                val zip = ZipOutputStream(BufferedOutputStream(raw))
                fun entry(path: String, bytes: ByteArray) {
                    total += bytes.size; require(total <= MAX_TOTAL_BYTES) { "Backup exceeds 64 MiB expanded limit" }
                    zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                    items.add(Item(path, bytes.size, sha256(bytes)))
                }
                entry("data.json", data)
                snapshot.strokes.forEach { entry("ink/${it.metadata.id}.ljpt", StrokePointCodec.encode(it.points)) }
                val manifest = buildJsonObject {
                    put("format", "LifeAppArchive"); put("backupFormatVersion", 1); put("kind", kind)
                    put("appVersion", appVersion); put("createdAt", Clock.System.now().toString()); put("dataSchemaVersion", snapshot.dataSchemaVersion)
                    put("sections", JsonArray(sectionsFor(snapshot.dataSchemaVersion).map(::JsonPrimitive)))
                    put("counts", buildJsonObject { snapshot.counts().forEach { (name, count) -> put(name, count) } })
                    put("files", JsonArray(items.map { item -> buildJsonObject {
                        put("path", item.path); put("size", item.size); put("sha256", item.hash)
                    } }))
                }
                val bytes = json.encodeToString(JsonObject.serializer(),manifest).encodeToByteArray()
                require(bytes.size <= MAX_JSON_BYTES && total + bytes.size <= MAX_TOTAL_BYTES)
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry()
                zip.finish(); zip.flush(); raw.fd.sync()
                // finish/flush before syncing; closing zip also releases its native deflater.
                zip.close()
            }
            require(file.length() <= MAX_ARCHIVE_BYTES)
            val verified = read(file, expectedKind = kind)
            require(SnapshotJson.encode(verified.snapshot) == SnapshotJson.encode(snapshot)) { "Backup verification mismatch" }
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
    private fun sectionsFor(schema: Int) = sections +
        (if (schema >= 6) listOf("todos") else emptyList()) +
        (if (schema >= 7) listOf("workouts") else emptyList())

    fun read(file: File, expectedKind: String = "backup"): Validated {
        require(file.length() in 1..MAX_ARCHIVE_BYTES) { "Archive is empty or exceeds 64 MiB" }
        ZipFile(file).use { zip ->
            val entries = linkedMapOf<String, ZipEntry>()
            var total = 0L
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                val path = entry.name
                require(!entry.isDirectory && safePath(path)) { "Invalid archive path" }
                require(entries.put(path, entry) == null) { "Duplicate archive path" }
                require(entries.size <= 50_002) { "Too many archive files" }
                require(entry.size in 0..MAX_FILE_BYTES) { "Archive entry exceeds size limit" }
                total += entry.size; require(total <= MAX_TOTAL_BYTES) { "Excessive archive expansion" }
            }
            fun read(path: String, limit: Long): ByteArray {
                val entry = requireNotNull(entries[path]) { "Missing archive entry: $path" }
                require(entry.size <= limit)
                val bytes = zip.getInputStream(entry).use { boundedRead(it, limit) }
                require(bytes.size.toLong() == entry.size)
                val crc = CRC32().apply { update(bytes) }.value
                require(crc == entry.crc) { "Corrupt ZIP entry" }
                return bytes
            }
            val manifestText = read("manifest.json", MAX_JSON_BYTES).decodeToString(throwOnInvalidSequence = true)
            checkJsonSizeAndDepth(manifestText)
            val manifest = json.parseToJsonElement(manifestText).jsonObject
            require(manifest.getValue("format").jsonPrimitive.content == "LifeAppArchive")
            require(manifest.getValue("backupFormatVersion").jsonPrimitive.int == 1 && manifest.getValue("dataSchemaVersion").jsonPrimitive.int in 4..8) { "Unsupported backup version" }
            val kind = manifest.getValue("kind").jsonPrimitive.content; require(kind == expectedKind) { "This file is not a full backup" }
            val schema = manifest.getValue("dataSchemaVersion").jsonPrimitive.int
            val expectedSections = sectionsFor(schema)
            require(manifest.getValue("sections").jsonArray.map { it.jsonPrimitive.content } == expectedSections)
            val created = Instant.parse(manifest.getValue("createdAt").jsonPrimitive.content)
            val version = manifest.getValue("appVersion").jsonPrimitive.content; require(version.length in 1..80)
            val files = manifest.getValue("files").jsonArray
            val declared = linkedMapOf<String, JsonObject>()
            files.forEach { value ->
                val item = value.jsonObject; val path = item.getValue("path").jsonPrimitive.content
                require(path != "manifest.json" && safePath(path) && declared.put(path, item) == null)
            }
            require(entries.keys == declared.keys + "manifest.json") { "Unexpected or missing archive files" }
            declared.forEach { (path, item) ->
                val bytes = read(path, if (path == "data.json") MAX_JSON_BYTES else MAX_FILE_BYTES)
                require(bytes.size.toLong() == item.getValue("size").jsonPrimitive.long)
                require(sha256(bytes) == item.getValue("sha256").jsonPrimitive.content) { "Checksum mismatch: $path" }
            }
            val used = mutableSetOf("data.json")
            val snapshot = SnapshotJson.decode(read("data.json", MAX_JSON_BYTES).decodeToString(throwOnInvalidSequence = true), expectedDataSchemaVersion = manifest.getValue("dataSchemaVersion").jsonPrimitive.int) { path ->
                require(used.add(path)) { "Duplicate ink reference" }; read(path, MAX_FILE_BYTES)
            }
            require(used == declared.keys) { "Unreferenced archive data" }
            val counts = manifest.getValue("counts").jsonObject.mapValues { it.value.jsonPrimitive.int }
            require(counts == if (schema >= 6) snapshot.counts() else snapshot.counts() - "todos") { "Record counts do not match manifest" }
            return Validated(snapshot,created,version,kind,counts)
        }
    }
    fun boundedRead(input: InputStream, limit: Long): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024); var count = 0L
        while (true) {
            val size = input.read(buffer); if (size < 0) break
            count += size; require(count <= limit) { "File exceeds size limit" }; result.write(buffer,0,size)
        }
        return result.toByteArray()
    }
    private fun safePath(path: String): Boolean = path == "manifest.json" || path == "data.json" ||
        path.startsWith("ink/") && path.endsWith(".ljpt") && runCatching {
            Uuid.parse(path.removePrefix("ink/").removeSuffix(".ljpt")).toString() == path.removePrefix("ink/").removeSuffix(".ljpt")
        }.getOrDefault(false)
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
