package com.myjournal.android

import android.content.Context
import androidx.work.*
import com.myjournal.data.RoomWorkoutRepository
import com.myjournal.data.WorkoutSyncRecord
import com.myjournal.data.WorkoutSyncVersion
import com.myjournal.data.syncRecords
import com.myjournal.domain.WorkoutLog
import com.myjournal.domain.SyncScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

internal data class WorkoutSyncConflict(val local: WorkoutSyncRecord, val remote: WorkoutSyncVersion)

internal class AndroidWorkoutSync(private val app: JournalApplication, private val repository: RoomWorkoutRepository) {
    private val credentials = ServerCredentialStore(app)
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val review = MutableStateFlow<List<WorkoutSyncConflict>>(emptyList())
    val conflicts = review.asStateFlow()
    private val message = MutableStateFlow<String?>(null)
    val status = message.asStateFlow()
    private val connected = MutableStateFlow(false)
    val bound = connected.asStateFlow()

    init {
        scope.launch {
            connected.value = repository.syncBinding() != null
            if (connected.value) schedule()
            repository.observe().map { it.canonical() }.distinctUntilChanged().collect {
                val binding = repository.syncBinding()
                if (binding != null && it.syncRecords().any { record -> binding.versions[record.id]?.record != record }) schedule()
            }
        }
    }

    suspend fun bind(scope: SyncScope, origin: String, reviewed: WorkoutLog) = gate.withLock {
        val credential = credentials.load() ?: error("Sign in before connecting workouts.")
        val session = ServerApi(credential.endpoint).session(credential.token)
        check(session.scope == scope && credential.endpoint.origin == origin) { "The account changed. Review synchronization again." }
        repository.bindSync(scope, origin, reviewed)
        connected.value = true
        schedule()
    }

    fun schedule() {
        if (!connected.value) return
        WorkManager.getInstance(app).enqueueUniqueWork("lifeapp-workout-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WorkoutSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build())
    }

    suspend fun synchronize(): Boolean = gate.withLock {
        val binding = repository.syncBinding() ?: return@withLock true
        try {
            review.value = emptyList()
            val credential = credentials.load() ?: error("Sign in to sync workouts.")
            val api = ServerApi(credential.endpoint)
            val session = api.session(credential.token)
            check(binding.scope == session.scope && binding.origin == api.endpoint.origin) {
                "Workouts are connected to another account or recovery epoch. Local changes were kept."
            }
            val remote = api.workoutVersions(session, credential.token).associateBy { it.record.id }
            val local = repository.syncRecords().associateBy { it.id }
            val conflicts = mutableListOf<WorkoutSyncConflict>()
            val ids = (remote.keys + local.keys).sortedBy { id ->
                when (local[id]?.kind ?: remote[id]?.record?.kind) {
                    "exercise" -> 0
                    "routine" -> 1
                    else -> 2
                }
            }
            for (id in ids) {
                val saved = local[id]
                val server = remote[id]
                val baseline = binding.versions[id]
                when {
                    saved == server?.record && server != null -> {
                        if (baseline?.version != server.version) repository.applySyncVersion(binding, server, saved, download = false)
                    }
                    server != null && (saved == null || saved == baseline?.record) -> {
                        if (!repository.applySyncVersion(binding, server, saved, download = true) && saved != null) {
                            conflicts += WorkoutSyncConflict(saved, server)
                        }
                    }
                    saved != null && server?.version == baseline?.version -> {
                        try {
                            val receipt = api.pushWorkout(session, credential.token, WorkoutSyncVersion(Uuid.random(), saved), baseline?.version)
                            check(receipt.record == saved) { "The workout receipt did not match the saved record." }
                            repository.applySyncVersion(binding, receipt, saved, download = false)
                        } catch (failure: ServerHttpFailure) {
                            if (failure.code != "WORKOUT_CHANGED") throw failure
                            val latest = api.workoutHead(session, credential.token, id)
                            conflicts += WorkoutSyncConflict(saved, latest)
                        }
                    }
                    saved != null && server != null -> conflicts += WorkoutSyncConflict(saved, server)
                    else -> error("The server workout inventory changed. Local records were kept.")
                }
            }
            review.value = conflicts
            message.value = if (conflicts.isEmpty()) "Workouts checked with the server."
                else "${conflicts.size} workout records need review. Both copies were kept."
            val unchanged = repository.syncRecords().all { record -> local[record.id] == record || remote[record.id]?.record == record }
            unchanged
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            message.value = failure.message ?: "Workout synchronization needs attention."
            failure !is IOException || failure is ServerHttpFailure && !failure.retryable
        }
    }
    fun resolve(conflict: WorkoutSyncConflict, keepLocal: Boolean) {
        scope.launch {
            try {
                gate.withLock {
                    val binding = requireNotNull(repository.syncBinding())
                    val credential = requireNotNull(credentials.load()) { "Sign in to resolve workouts." }
                    val api = ServerApi(credential.endpoint)
                    val session = api.session(credential.token)
                    check(binding.scope == session.scope && binding.origin == api.endpoint.origin)
                    val id = conflict.local.id
                    check(repository.loadDraft()?.session?.metadata?.id != id) { "Finish the workout draft before resolving this copy." }
                    val local = repository.syncRecords().single { it.id == id }
                    check(local == conflict.local) { "The local copy changed. Sync and review again." }
                    val latest = api.workoutHead(session, credential.token, id)
                    check(latest == conflict.remote) { "The server copy changed. Sync and review again." }
                    val chosen = if (keepLocal) {
                        val metadata = local.value.getValue("metadata").jsonObject
                        val remote = latest.record.value.getValue("metadata").jsonObject
                        check(metadata["createdAt"] == remote["createdAt"] && local.kind == latest.record.kind) {
                            "These copies have different origins. Export your local copy before choosing the server copy."
                        }
                        fun JsonObject.revision() = getValue("revision").jsonPrimitive.content.toLong()
                        fun JsonObject.updated() = Instant.parse(getValue("updatedAt").jsonPrimitive.content)
                        val revised = JsonObject(metadata + mapOf(
                            "revision" to JsonPrimitive((maxOf(metadata.revision(), remote.revision()) + 1).toString()),
                            "updatedAt" to JsonPrimitive(maxOf(metadata.updated(), remote.updated(), Clock.System.now()).toString()),
                        ))
                        val record = local.copy(value = JsonObject(local.value + ("metadata" to revised)))
                        api.pushWorkout(session, credential.token, WorkoutSyncVersion(Uuid.random(), record), latest.version)
                            .also { check(it.record == record) { "Workout receipt did not match." } }
                    } else latest
                    check(repository.applySyncVersion(binding, chosen, local, download = true)) {
                        "The local copy changed during review. Its changes were kept."
                    }
                    review.value = review.value.filterNot { it.local.id == id }
                    message.value = "Workout copy saved."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message.value = failure.message ?: "Could not resolve the workout copy."
            }
        }
    }

}

class WorkoutSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as JournalApplication).workoutSync
        return if (sync.synchronize() || runAttemptCount >= 7) Result.success() else Result.retry()
    }
}
