package com.myjournal.domain

import kotlin.time.Instant

data class CloudBackupVersion(val id: String, val createdAt: Instant)

interface CloudBackupStorage {
    suspend fun create(encryptedArchive: ByteArray)
    suspend fun list(): List<CloudBackupVersion>
    suspend fun download(id: String): ByteArray
}
