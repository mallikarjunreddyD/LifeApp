package com.myjournal.android

import android.content.Context
import androidx.work.*
import com.myjournal.data.RoomTodoRepository
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal data class BindingPreview(val scope: SyncScope, val localCount: Int, val remoteHasHistory: Boolean) {
    val allowed get() = localCount == 0 || !remoteHasHistory
}

internal data class AndroidServerState(
    val origin: String? = null,
    val username: String? = null,
    val accountScope: SyncScope? = null,
    val bound: SyncCheckpoint? = null,
    val pinnedOrigin: String? = null,
    val pending: Int = 0,
    val working: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val preview: BindingPreview? = null,
    val lastSync: String? = null,
    val conflicts: List<List<TodoOperation>> = emptyList(),
)

internal enum class SyncOutcome { DONE, RETRY, CONTINUE }

internal class AndroidServerSync(private val context: Context, private val repository: RoomTodoRepository) {
    private val store = ServerCredentialStore(context)
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AndroidServerState(origin = store.origin(), lastSync = store.lastVerified()))
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            try { gate.withLock { refresh() } }
            catch (failure: Exception) { report(failure) }
            repository.observePendingCount().collect { count -> mutable.update { it.copy(pending = count) } }
        }
    }

    fun onForeground() {
        scope.launch {
            try {
                gate.withLock { refresh() }
                schedule()
            } catch (failure: Exception) { report(failure) }
        }
    }

    /** Queue the trigger after a committed edit. Work itself requires a network. */
    fun onLocalCommit() = schedule(uploadOnly = true)

    fun testConnection(origin: String) = action {
        val api = ServerApi(endpoint(origin))
        api.ready()
        message("LifeApp server is ready. Sign in to verify the account.")
    }

    fun signIn(origin: String, username: String, password: String) = action(invalidateOnExpired = false) {
        val api = ServerApi(endpoint(origin))
        val pinned = repository.boundOrigin()
        if (pinned != null && pinned != api.endpoint.origin) throw UserSyncError("This database is bound to a different HTTPS origin.")
        val credential = api.login(username.trim(), password)
        val bound = repository.checkpoint()
        if (bound != null && (bound.scope != credential.session.scope || pinned != api.endpoint.origin)) {
            runCatching { api.logout(credential.token) }
            throw UserSyncError("This sign-in has a different dataset or recovery epoch. Local data was kept.")
        }
        store.save(credential)
        mutable.update { it.copy(preview = null) }
        refresh(credential)
        message(if (bound == null) "Signed in. Review first sync before sending any tasks." else "Signed in to the bound dataset.")
        schedule()
    }

    fun preview() = action {
        val (credential, session, api) = currentSession()
        if (repository.checkpoint() != null) throw UserSyncError("This database already has a server binding.")
        val first = api.transport(session, credential.token).pull(SyncCheckpoint(session.scope))
        first.advance(SyncCheckpoint(session.scope))
        val preview = BindingPreview(session.scope, repository.localTasks().size, first.entries.isNotEmpty())
        mutable.update { it.copy(preview = preview) }
        if (!preview.allowed) throw UserSyncError("Both devices have task history. No data was imported.")
        message("Review the dataset and save a verified local backup to continue.")
    }

    /** Invoked only after the chosen backup document was written and read back. */
    suspend fun bindFromVerifiedBackup(expected: BindingPreview, backedUp: List<Todo>) = gate.withLock {
        try {
            val (credential, session, api) = currentSession()
            if (mutable.value.preview != expected || !expected.allowed || expected.scope != session.scope) {
                throw UserSyncError("The first-sync preview changed. Review it again.")
            }
            if (backedUp.size != expected.localCount) throw UserSyncError("The task count changed. Review first sync again.")
            val first = api.transport(session, credential.token).pull(SyncCheckpoint(session.scope))
            first.advance(SyncCheckpoint(session.scope))
            if (expected.remoteHasHistory != first.entries.isNotEmpty()) {
                throw UserSyncError("The server task history changed. Review first sync again.")
            }
            repository.bindEndpoint(session.scope, api.endpoint, backedUp, first.entries.isNotEmpty())
            mutable.update { it.copy(preview = null) }
            refresh(credential)
            message("Backup verified and To-do binding saved. First sync is queued.")
            schedule()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            report(failure)
            throw UserSyncError(safeMessage(failure))
        }
    }

    fun disconnect() = action {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        val credential = runCatching { store.load() }.getOrNull()
        store.clear()
        mutable.update { it.copy(preview = null) }
        refresh(null, load = false)
        val revoked = credential == null || try {
            ServerApi(credential.endpoint).logout(credential.token)
            true
        } catch (_: ServerHttpFailure) { false }
          catch (_: IOException) { false }
        message(if (revoked) "Disconnected. Local records, queued tasks and the dataset binding were kept."
            else "Disconnected locally. The server could not revoke the session and it will expire.")
    }

    fun syncNow() = action {
        if (synchronizeLocked() != SyncOutcome.DONE) schedule()
    }

    fun resolve(recordId: Uuid, chosen: Uuid, heads: Set<Uuid>) = action {
        repository.resolve(recordId, chosen, heads)
        refresh()
        message("Task resolution saved locally.")
        schedule()
    }

    /** True when WorkManager should apply its exponential retry policy. */
    suspend fun work(uploadOnly: Boolean): SyncOutcome = gate.withLock {
        if (uploadOnly && repository.observePendingCount().first() == 0) return@withLock SyncOutcome.DONE
        mutable.update { it.copy(working = true) }
        try { synchronizeLocked() }
        finally { mutable.update { it.copy(working = false) } }
    }

    private suspend fun synchronizeLocked(): SyncOutcome {
        try {
            val checkpoint = repository.checkpoint() ?: return SyncOutcome.DONE
            if (store.retryAt() > System.currentTimeMillis()) {
                message("To-do sync is waiting before retrying.")
                return SyncOutcome.RETRY
            }
            val (credential, session, api) = currentSession()
            if (session.scope != checkpoint.scope || repository.boundOrigin() != api.endpoint.origin) {
                throw UserSyncError("The server origin, dataset or recovery epoch changed. Local records were kept.")
            }
            val transport = api.transport(session, credential.token, repository::retainRejectedResolution)
            val coordinator = TodoSyncCoordinator(repository, transport, uploadLimit = 10)
            repeat(3) {
                val progress = coordinator.synchronize()
                if (!progress.moreAvailable && repository.pending().isEmpty()) {
                    // The first completion compares the server projection against the applied local tasks.
                    val active = repository.localTasks().filterNot(Todo::deleted).map { it.metadata.id }.toSet()
                    if (store.lastVerified() == null) {
                        val serverIds = api.visibleTaskIds(credential.token)
                        val tail = transport.pull(progress.checkpoint)
                        tail.advance(progress.checkpoint)
                        if (tail.entries.isNotEmpty() || repository.pending().isNotEmpty()) return@repeat
                        if (serverIds != active) throw UserSyncError("Task counts or identities differ. First sync was not verified.")
                    }
                    store.saveLastVerified(Clock.System.now().toString())
                    store.saveRetryAt(0)
                    refresh(credential)
                    message("To-do synchronized · ${active.size} active tasks · commit ${progress.checkpoint.sequence}.")
                    return SyncOutcome.DONE
                }
            }
            refresh(credential)
            message("More To-do changes are queued.")
            return SyncOutcome.CONTINUE
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (failure is ServerHttpFailure && failure.status == 401 || failure is ExpiredSyncSession) {
                store.clear()
                refresh(null, load = false)
            } else {
                refresh()
            }
            val retryable = when (failure) {
                is ServerHttpFailure -> failure.retryable
                is SSLException -> false
                is IOException -> true
                else -> false
            }
            if (retryable) {
                val seconds = (failure as? ServerHttpFailure)?.retrySeconds ?: 0L
                val delay = seconds.coerceAtMost(Long.MAX_VALUE / 2000) * 1000 + Random.nextLong(1_000, 5_001)
                val now = System.currentTimeMillis()
                store.saveRetryAt(now + delay)
            }
            report(failure)
            return if (retryable) SyncOutcome.RETRY else SyncOutcome.DONE
        }
    }

    private suspend fun currentSession(): Triple<ServerCredential, ServerSession, ServerApi> {
        val credential = store.load() ?: throw UserSyncError("Sign in to the LifeApp server first.")
        if (credential.session.expiresAt <= Clock.System.now()) throw ExpiredSyncSession()
        val api = ServerApi(credential.endpoint)
        val session = api.session(credential.token)
        if (session.scope != credential.session.scope) throw UserSyncError("The server recovery epoch changed. Local records were kept.")
        return Triple(credential, session, api)
    }

    private suspend fun refresh(credential: ServerCredential? = null, load: Boolean = true) {
        val saved = credential ?: if (load) store.load() else null
        val checkpoint = repository.checkpoint()
        val pinned = repository.boundOrigin()
        val conflicts = if (checkpoint == null) emptyList() else repository.allConflicts()
        mutable.update { it.copy(
            origin = pinned ?: saved?.endpoint?.origin ?: store.origin(),
            username = saved?.username,
            accountScope = saved?.session?.scope,
            bound = checkpoint,
            pinnedOrigin = pinned,
            lastSync = store.lastVerified(),
            conflicts = conflicts,
        ) }
    }

    private fun endpoint(origin: String): ServerEndpoint = try {
        ServerEndpoint.parse(origin.trim())
    } catch (_: IllegalArgumentException) {
        throw UserSyncError("Enter a valid HTTPS origin without credentials or a path.")
    }

    private fun action(invalidateOnExpired: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            gate.withLock {
                mutable.update { it.copy(working = true, message = null, error = false) }
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (invalidateOnExpired && ((failure is ServerHttpFailure && failure.status == 401) || failure is ExpiredSyncSession)) {
                        store.clear()
                        refresh(null, load = false)
                    }
                    report(failure)
                }
                finally { mutable.update { it.copy(working = false) } }
            }
        }
    }

    internal fun schedule(uploadOnly: Boolean = false) {
        val state = mutable.value
        if (state.bound?.scope == null || state.bound.scope != state.accountScope || state.pinnedOrigin == null) return
        val request = OneTimeWorkRequestBuilder<ServerSyncWorker>()
            .setInputData(workDataOf("uploadOnly" to uploadOnly))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun message(value: String) = mutable.update { it.copy(message = value, error = false) }
    private fun report(error: Exception) = mutable.update { it.copy(message = safeMessage(error), error = true) }

    private fun safeMessage(failure: Exception): String = when (failure) {
        is UserSyncError, is ServerHttpFailure, is ExpiredSyncSession -> failure.message!!
        is SSLException -> "TLS verification failed. Use a trusted HTTPS certificate and the matching hostname."
        is IOException -> "Could not reach the LifeApp server. Local records and queued tasks were kept."
        else -> "Server sync could not be completed safely. Local records and queued tasks were kept."
    }

    private class UserSyncError(message: String) : IllegalStateException(message)
    private class ExpiredSyncSession : IllegalStateException("Server sign-in expired. Sign in again.")

    companion object { private const val WORK = "lifeapp-todo-server-sync" }
}

class ServerSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as JournalApplication).serverSync
        return when (sync.work(inputData.getBoolean("uploadOnly", false))) {
            SyncOutcome.DONE -> Result.success()
            SyncOutcome.RETRY -> if (runAttemptCount < 7) Result.retry() else Result.success()
            SyncOutcome.CONTINUE -> {
                sync.schedule()
                Result.success()
            }
        }
    }
}
