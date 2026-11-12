package com.myjournal.android

import android.accounts.Account
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.myjournal.data.BackupArchive
import com.myjournal.data.CloudBackupEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal const val BACKUP_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

internal data class AutomaticBackupState(
    val account: String? = null,
    val local: String? = null,
    val drive: String? = null,
    val message: String? = null,
)

internal class AutomaticAndroidBackup(private val app: JournalApplication) {
    private val preferences = app.getSharedPreferences("automatic-backup", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(readState())
    val state = mutable.asStateFlow()
    private val gate = Mutex()

    private fun readState() = AutomaticBackupState(
        preferences.getString("account", null), preferences.getString("local", null),
        preferences.getString("drive", null), preferences.getString("message", null),
    )

    suspend fun enroll(account: String, secret: CharArray) = gate.withLock {
        try {
            withContext(Dispatchers.IO) {
                require(secret.size in 12..1024)
                require(connectedAccount() == account) { "Connect the Google account you want to use." }
                val id = Uuid.random().toString()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(true))
                cipher.updateAAD(id.encodeToByteArray())
                val plaintext = secret.concatToString().encodeToByteArray()
                val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
                check(preferences.edit().clear().putString("id", id).putString("account", account)
                    .putString("secret", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
                mutable.value = readState()
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    suspend fun pause() = gate.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().remove("secret").putString("message", "Automatic backup paused. Existing backups were kept.").commit())
            mutable.value = readState()
            for (name in listOf(PERIODIC, CAPTURE, UPLOAD)) WorkManager.getInstance(app).cancelUniqueWork(name)
        }
    }

    fun enabled() = preferences.contains("secret")

    fun schedule() {
        if (!enabled()) return
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AndroidBackupCaptureWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
        scheduleUpload()
    }

    fun captureNow() {
        if (enabled()) WorkManager.getInstance(app).enqueueUniqueWork(CAPTURE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AndroidBackupCaptureWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
    }

    fun scheduleUpload() {
        if (enabled()) WorkManager.getInstance(app).enqueueUniqueWork(UPLOAD, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AndroidBackupUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
    }

    suspend fun capture(): Boolean = gate.withLock {
        if (!enabled()) return@withLock true
        try {
            val snapshot = withContext(Dispatchers.Main.immediate) { app.operations.committedBackupSnapshot() }
            withContext(Dispatchers.IO) {
                val directory = directory()
                val plain = File.createTempFile("lifeapp-automatic-", ".zip", app.cacheDir)
                val secret = recoverySecret()
                try {
                    BackupArchive.write(plain, snapshot)
                    BackupArchive.read(plain)
                    val bytes = plain.inputStream().use { BackupArchive.boundedRead(it, CloudBackupEnvelope.MAX_ARCHIVE_BYTES.toLong()) }
                    val envelope = try { CloudBackupEnvelope.seal(bytes, secret) } finally { bytes.fill(0) }
                    val name = Uuid.random().toString()
                    val time = Clock.System.now().toString()
                    val archive = directory.resolve("$name.bin")
                    write(archive, envelope)
                    val digest = BackupArchive.sha256(envelope)
                    check(BackupArchive.sha256(readEnvelope(archive)) == digest)
                    writeGeneration(directory, Generation(name, time, digest))
                    check(preferences.edit().putString("local", time).remove("message").commit())
                    mutable.value = readState()
                    pruneLocal(directory)
                } finally {
                    secret.fill('\u0000')
                    plain.delete()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            note("Local automatic backup needs attention. Check the recovery key and available storage.")
            false
        }
    }

    suspend fun upload(): Boolean = gate.withLock {
        if (!enabled()) return@withLock true
        val account = preferences.getString("account", null)
        if (account == null || connectedAccount() != account) {
            note("Backup needs authorization for the enrolled Google account.")
            return@withLock true
        }
        try {
            withContext(Dispatchers.IO) {
                val directory = directory()
                val items = generations(directory)
                if (items.none { !it.verified } && items.count { it.verified } <= 2) return@withContext
                val token = authorization(account)
                if (token == null) {
                    note("Backup needs authorization. Reauthorize Google Drive in Settings.")
                    return@withContext
                }
                val storage = DriveBackupStorage(token)
                for (item in items.take(2).filterNot { it.verified }) {
                    check(connectedAccount() == account)
                    val archive = readEnvelope(directory.resolve("${item.name}.bin"))
                    check(BackupArchive.sha256(archive) == item.hash)
                    val known = item.id?.let { item } ?: item.copy(id = storage.newId())
                        .also { writeGeneration(directory, it) }
                    storage.createVerified(requireNotNull(known.id), archive)
                    writeGeneration(directory, known.copy(verified = true))
                    check(preferences.edit().putString("drive", Clock.System.now().toString()).remove("message").commit())
                    mutable.value = readState()
                }
                prune(directory, storage, account)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is DriveHttpFailure && failure.status == 401) {
                note("Backup needs authorization. Reauthorize Google Drive in Settings.")
                true
            } else {
                note("Drive backup needs attention. Local recovery copies were kept.")
                false
            }
        }
    }

    private fun pruneLocal(directory: File) {
        val local = generations(directory).filter { directory.resolve("${it.name}.bin").isFile }
        if (local.size <= 2) return
        for (item in local.take(2)) {
            check(BackupArchive.sha256(readEnvelope(directory.resolve("${item.name}.bin"))) == item.hash)
        }
        for (item in local.drop(2).filter { it.verified }) {
            check(directory.resolve("${item.name}.bin").delete())
            audit(directory, item.name, "local")
        }
    }

    private suspend fun prune(directory: File, storage: DriveBackupStorage, account: String) {
        val items = generations(directory)
        val survivors = items.take(2)
        if (survivors.size < 2 || survivors.any { !it.verified }) return
        for (item in survivors) {
            val local = readEnvelope(directory.resolve("${item.name}.bin"))
            check(BackupArchive.sha256(local) == item.hash)
            check(BackupArchive.sha256(storage.download(requireNotNull(item.id))) == item.hash)
        }
        for (item in items.drop(2)) {
            check(connectedAccount() == account)
            // A persisted generated ID may exist after an interrupted upload. Verify it
            // before pruning it. Missing IDs need no remote deletion.
            item.id?.let { id ->
                val bytes = try { storage.download(id) } catch (failure: DriveHttpFailure) {
                    if (failure.status != 404) throw failure
                    null
                }
                if (bytes != null) {
                    check(BackupArchive.sha256(bytes) == item.hash)
                    storage.deleteManaged(id)
                    audit(directory, item.name, "drive")
                }
            }
            val local = directory.resolve("${item.name}.bin")
            if (local.exists()) {
                check(local.delete())
                audit(directory, item.name, "local")
            }
            check(directory.resolve("${item.name}.json").delete())
        }
    }

    private fun audit(directory: File, name: String, destination: String) {
        val file = directory.resolve("retention.log")
        val prior = if (file.isFile) file.readLines().takeLast(99) else emptyList()
        write(file, (prior + "${Clock.System.now()} $destination $name").joinToString("\n", postfix = "\n").encodeToByteArray())
    }

    suspend fun localBackups(): List<LocalAutomaticBackup> = gate.withLock {
        withContext(Dispatchers.IO) {
            val root = app.noBackupFilesDir.resolve("automatic-backups")
            root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { folder ->
                generations(folder).mapNotNull { item ->
                    val file = folder.resolve("${item.name}.bin")
                    if (file.isFile) LocalAutomaticBackup(file, item.time, file.length(), item.hash) else null
                }
            }.sortedByDescending { kotlin.time.Instant.parse(it.createdAt) }.take(2)
        }
    }

    private fun directory(): File {
        val id = Uuid.parse(requireNotNull(preferences.getString("id", null)))
        return app.noBackupFilesDir.resolve("automatic-backups/$id").also {
            check(it.isDirectory || it.mkdirs())
        }
    }

    private fun recoverySecret(): CharArray {
        val id = requireNotNull(preferences.getString("id", null))
        val encoded = Base64.decode(requireNotNull(preferences.getString("secret", null)), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encoded, 0, 12))
        cipher.updateAAD(id.encodeToByteArray())
        val plaintext = cipher.doFinal(encoded, 12, encoded.size - 12)
        return try { plaintext.decodeToString(throwOnInvalidSequence = true).toCharArray() } finally { plaintext.fill(0) }
    }

    private fun key(create: Boolean): SecretKey {
        val alias = "lifeapp-automatic-recovery-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Enroll again using a recovery passphrase." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun connectedAccount() =
        app.getSharedPreferences("drive-backup", Context.MODE_PRIVATE).getString("account", null)

    private suspend fun authorization(account: String): String? = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder().setAccount(Account(account, "com.google"))
            .setRequestedScopes(listOf(Scope(BACKUP_DRIVE_SCOPE))).build()
        Identity.getAuthorizationClient(app).authorize(request).addOnCompleteListener { task ->
            val grant = if (task.isSuccessful) task.result else null
            continuation.resume(grant?.takeIf { it.pendingIntent == null && BACKUP_DRIVE_SCOPE in it.grantedScopes }?.accessToken)
        }
    }

    private fun note(message: String) {
        preferences.edit().putString("message", message).apply()
        mutable.value = readState()
    }

    private data class Generation(val name: String, val time: String, val hash: String, val id: String? = null,
        val verified: Boolean = false)

    private fun generations(directory: File): List<Generation> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.map { file ->
            val json = Json.parseToJsonElement(AtomicFile(file).readFully().decodeToString()).jsonObject
            Generation(Uuid.parse(file.nameWithoutExtension).toString(), json.getValue("time").jsonPrimitive.content,
                json.getValue("hash").jsonPrimitive.content.also { check(it.matches(Regex("[a-f0-9]{64}"))) },
                json["id"]?.jsonPrimitive?.content, json.getValue("verified").jsonPrimitive.boolean).also {
                kotlin.time.Instant.parse(it.time)
                check(!it.verified || it.id != null)
            }
        }.sortedWith(compareByDescending<Generation> { kotlin.time.Instant.parse(it.time) }.thenByDescending { it.name })

    private fun writeGeneration(directory: File, generation: Generation) {
        val json = buildJsonObject {
            put("time", generation.time)
            put("hash", generation.hash)
            generation.id?.let { put("id", it) }
            put("verified", generation.verified)
        }
        write(directory.resolve("${generation.name}.json"), json.toString().encodeToByteArray())
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun readEnvelope(file: File) = file.inputStream().use {
        BackupArchive.boundedRead(it, CloudBackupEnvelope.MAX_ENVELOPE_BYTES.toLong())
    }

    companion object {
        private const val CAPTURE = "lifeapp-automatic-backup-now"
        private const val PERIODIC = "lifeapp-automatic-backup-daily"
        private const val UPLOAD = "lifeapp-automatic-backup-upload"
    }
}

internal data class LocalAutomaticBackup(val file: File, val createdAt: String, val size: Long, val hash: String)

class AndroidBackupCaptureWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as JournalApplication).automaticBackup
        if (backup.capture()) {
            backup.scheduleUpload()
            return Result.success()
        }
        return if (runAttemptCount >= 3) Result.success() else Result.retry()
    }
}

class AndroidBackupUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as JournalApplication).automaticBackup
        return if (backup.upload() || runAttemptCount >= 6) Result.success() else Result.retry()
    }
}
