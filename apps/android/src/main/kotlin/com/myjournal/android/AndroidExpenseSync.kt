package com.myjournal.android

import android.content.Context
import androidx.work.*
import com.myjournal.data.RoomExpenseRepository
import com.myjournal.domain.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

internal data class ExpenseConnectionReview(val origin: String, val scope: SyncScope, val local: ExpenseLedger, val serverCount: Long)

internal class AndroidExpenseSync(
    private val app: JournalApplication,
    private val repository: RoomExpenseRepository,
) {
    private val credentials = ServerCredentialStore(app)
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<String?>(null)
    val status = mutable.asStateFlow()
    private val reviewMutable = MutableStateFlow<ExpenseConnectionReview?>(null)
    val review = reviewMutable.asStateFlow()
    val state = repository.observeSync()

    init {
        scope.launch {
            state.map { it?.pending?.lastOrNull()?.id }.distinctUntilChanged().collect { id ->
                if (id != null) schedule()
            }
        }
    }

    fun reviewConnection() = launch {
        val (saved, session, api) = connection()
        repository.seed()
        val local = repository.observe().first()
        reviewMutable.value = ExpenseConnectionReview(api.endpoint.origin, session.scope, local,
            api.expenseInventory(session, saved.token))
    }

    fun dismissReview() { reviewMutable.value = null }

    suspend fun bind(review: ExpenseConnectionReview, backedUp: ExpenseLedger) = gate.withLock {
        check(reviewMutable.value == review)
        val (saved, session, api) = connection()
        check(review.origin == api.endpoint.origin && review.scope == session.scope)
        check(api.expenseInventory(session, saved.token) == review.serverCount) { "Server expenses changed. Review again." }
        check(backedUp.canonical() == review.local.canonical()) { "Expenses changed. Review again." }
        repository.bind(session.scope, api.endpoint.origin, backedUp)
        reviewMutable.value = null
        schedule()
    }

    fun resolve(id: Uuid, version: Uuid, heads: Set<Uuid>) = launch {
        repository.resolve(id, version, heads)
        schedule()
    }

    fun schedule() {
        WorkManager.getInstance(app).enqueueUniqueWork("lifeapp-expense-sync", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ExpenseSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    suspend fun synchronize(): Boolean = gate.withLock {
        val initial = repository.syncState() ?: return@withLock true
        try {
            val (saved, session, api) = connection()
            check(initial.scope == session.scope && initial.origin == api.endpoint.origin) {
                "Review your connection before syncing expenses again. Local changes were kept."
            }
            mutable.value = "Syncing expenses…"
            var reviewChanged = false
            repeat(4) {
                val state = requireNotNull(repository.syncState())
                for (operation in state.pending.take(20).filterNot { it.id in state.acknowledged }) {
                    try {
                        val receipt = api.pushExpense(session, saved.token, operation)
                        check(receipt.operation == operation)
                        repository.acknowledge(state.scope, receipt)
                    } catch (failure: ServerHttpFailure) {
                        if (failure.code != "CONFLICT_CHANGED" ||
                            operation.mutation !in setOf(ExpenseMutation.RESOLVE, ExpenseMutation.RESTORE)) throw failure
                        repository.rejectResolution(state.scope, operation.id)
                        reviewChanged = true
                    }
                }
                val cursor = requireNotNull(repository.syncState()).cursor
                val (entries, more) = api.pullExpenses(session, saved.token, cursor)
                require(entries.size <= 32 && (entries.isNotEmpty() || !more))
                repository.replay(state.scope, cursor, entries)
                if (!more && requireNotNull(repository.syncState()).pending.isEmpty()) {
                    mutable.value = if (reviewChanged) "Expense versions changed. Review them again." else "Expenses checked with the server."
                    return@withLock true
                }
            }
            mutable.value = "More expense changes are waiting."
            false
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mutable.value = failure.message ?: "Expense synchronization needs attention."
            if (failure is ServerHttpFailure && failure.retrySeconds != null) {
                val retry = failure.retrySeconds.coerceAtMost(86_400)
                app.getSharedPreferences("expense-sync-retry", Context.MODE_PRIVATE).edit()
                    .putLong("until", System.currentTimeMillis() + retry * 1000).apply()
            }
            when (failure) {
                is ServerHttpFailure -> !failure.retryable
                is SSLException -> true
                is IOException -> false
                else -> true
            }
        }
    }

    private fun launch(action: suspend () -> Unit) {
        scope.launch {
            try { gate.withLock { action() } }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { mutable.value = failure.message ?: "Expense synchronization needs attention." }
        }
    }

    private suspend fun connection(): Triple<ServerCredential, ServerSession, ServerApi> {
        val saved = credentials.load() ?: error("Sign in under Account & Security to connect expenses.")
        val api = ServerApi(saved.endpoint)
        return Triple(saved, api.session(saved.token), api)
    }
}

class ExpenseSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as JournalApplication
        val until = app.getSharedPreferences("expense-sync-retry", Context.MODE_PRIVATE).getLong("until", 0)
        if (until > System.currentTimeMillis()) return Result.retry()
        return if (app.expenseSync.synchronize() || runAttemptCount >= 7) Result.success() else Result.retry()
    }
}
