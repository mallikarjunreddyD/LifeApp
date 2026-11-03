package com.myjournal.data

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.zip.*
import kotlin.test.*

class BackupCorruptionTest {
    private fun entries(file: File): LinkedHashMap<String,ByteArray> = ZipFile(file).use { zip ->
        linkedMapOf<String,ByteArray>().apply { zip.entries().asSequence().forEach { put(it.name,zip.getInputStream(it).use { s->s.readBytes() }) } }
    }
    private fun write(file: File, data: Map<String,ByteArray>) = ZipOutputStream(file.outputStream()).use { zip ->
        data.forEach { (path,bytes)->zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry() }
    }
    private fun fixture(change: (LinkedHashMap<String,ByteArray>)->Unit) {
        val file=File.createTempFile("lifeapp-corrupt", ".zip")
        try { BackupArchive.write(file,backupFixture());val entries=entries(file);change(entries);write(file,entries)
            assertFailsWith<Exception> { BackupArchive.read(file) }
        } finally { file.delete() }
    }
    private fun manifest(data: LinkedHashMap<String,ByteArray>, change: (MutableMap<String,JsonElement>)->Unit) {
        val objectValue=Json.parseToJsonElement(data.getValue("manifest.json").decodeToString()).jsonObject.toMutableMap()
        change(objectValue);data["manifest.json"]=JsonObject(objectValue).toString().encodeToByteArray()
    }
    private fun data(data: LinkedHashMap<String,ByteArray>, change: (MutableMap<String,JsonElement>)->Unit) {
        val objectValue=Json.parseToJsonElement(data.getValue("data.json").decodeToString()).jsonObject.toMutableMap()
        change(objectValue);data["data.json"]=JsonObject(objectValue).toString().encodeToByteArray()
        // Re-sign manifest for semantically invalid records: validation must go beyond hashes.
        manifest(data) { m -> m["files"]=JsonArray(m.getValue("files").jsonArray.map { item ->
            if(item.jsonObject.getValue("path").jsonPrimitive.content=="data.json") JsonObject(item.jsonObject.toMutableMap().apply {
                put("size",JsonPrimitive(data.getValue("data.json").size));put("sha256",JsonPrimitive(BackupArchive.sha256(data.getValue("data.json"))))
            }) else item
        }) }
    }
    @Test fun corruptManifestInvalidJsonAndUnsupportedVersionsAreRejected() {
        fixture { it["manifest.json"]="{".encodeToByteArray() }
        fixture { manifest(it){m->m["backupFormatVersion"]=JsonPrimitive(999)} }
        fixture { data(it){m->m["dataSchemaVersion"]=JsonPrimitive(999)} }
        fixture { it["data.json"]="not json".encodeToByteArray() }
    }
    @Test fun missingUnexpectedFilesAndWrongHashesAreRejected() {
        fixture { it.remove(it.keys.first { k->k.endsWith(".ljpt") }) }
        fixture { it["data.json"]=it.getValue("data.json")+byteArrayOf(32) }
        fixture { manifest(it){m->m["counts"]=buildJsonObject{put("strokes",999)}} }
        fixture { it["unexpected.txt"]=byteArrayOf(1) }
    }
    @Test fun semanticIdentityOwnershipCompletionAndInkFormatCorruptionAreRejected() {
        fixture { data(it){m->m["strokes"]=JsonArray(m.getValue("strokes").jsonArray.let { list->list+list.first() })} }
        fixture { data(it){m->m["notebooks"]=JsonArray(emptyList())} }
        fixture { data(it){m->m["journals"]=JsonArray(m.getValue("journals").jsonArray.let { list->list+list.first() })} }
        fixture { data(it){m->m["habits"]=JsonArray(m.getValue("habits").jsonArray.mapIndexed { i,ledger ->
            if(i!=0) ledger else JsonObject(ledger.jsonObject.toMutableMap().apply { put("completions",JsonArray(getValue("completions").jsonArray.let { list->list+list.first() })) })
        })} }
        fixture { data(it){m->m["strokes"]=JsonArray(m.getValue("strokes").jsonArray.mapIndexed { i,stroke ->
            if(i!=0) stroke else JsonObject(stroke.jsonObject.toMutableMap().apply { put("pointFormatVersion",JsonPrimitive(999)) })
        })} }
        fixture { values ->
            val path=values.keys.first { it.endsWith(".ljpt") };values[path]=byteArrayOf(0,1,2)
            manifest(values){m->m["files"]=JsonArray(m.getValue("files").jsonArray.map { file ->
                if(file.jsonObject.getValue("path").jsonPrimitive.content!=path) file else JsonObject(file.jsonObject.toMutableMap().apply {
                    put("size",JsonPrimitive(3));put("sha256",JsonPrimitive(BackupArchive.sha256(values.getValue(path))))
                })
            })}
        }
    }
    @Test fun zipTraversalAbsolutePathsDuplicatesAndTruncationAreRejected() {
        for(path in listOf("../data.json","/data.json","ink/../../data.json","ink\\bad.ljpt")) fixture { it[path]=byteArrayOf(1) }
        val file=File.createTempFile("lifeapp-zip", ".zip")
        try {
            BackupArchive.write(file,backupFixture());val bytes=file.readBytes();file.writeBytes(bytes.copyOf(bytes.size-40))
            assertFailsWith<Exception> { BackupArchive.read(file) }
            // Replace equal-length ZIP names in both headers to manufacture duplicate paths.
            write(file,linkedMapOf("data.json" to byteArrayOf(1),"xxxx.json" to byteArrayOf(2)))
            val duplicate=file.readBytes();val from="xxxx.json".encodeToByteArray();val to="data.json".encodeToByteArray()
            for(i in 0..duplicate.size-from.size) if(from.indices.all { duplicate[i+it]==from[it] }) to.copyInto(duplicate,i)
            file.writeBytes(duplicate);assertFailsWith<Exception> { BackupArchive.read(file) }
        } finally { file.delete() }
    }
    @Test fun excessiveExpansionAndJsonNestingAreRejectedBeforeAllocation() {
        fixture { it["data.json"]=ByteArray(9*1024*1024) { 32 } }
        fixture { it["data.json"]=ByteArray(65*1024*1024) { 32 } }
        assertFailsWith<IllegalArgumentException> { checkJsonSizeAndDepth("[".repeat(33)+"]".repeat(33)) }
        assertFailsWith<IllegalArgumentException> { BackupArchive.boundedRead(ByteArray(1025).inputStream(),1024) }
    }
}
