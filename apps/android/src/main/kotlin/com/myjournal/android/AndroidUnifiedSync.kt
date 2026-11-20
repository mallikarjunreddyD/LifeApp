package com.myjournal.android

import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class UnifiedSyncState(
    val automatic: Boolean = true,
    val running: Boolean = false,
    val status: String = "Ready to sync",
    val lastChecked: String? = null,
    val issues: List<String> = emptyList(),
)

/** One application-owned queue drives all structured data adapters. */
internal class AndroidUnifiedSync(private val app: JournalApplication) {
    private val preferences = app.getSharedPreferences("structured-sync", android.content.Context.MODE_PRIVATE)
    private val gate = Mutex()
    @Volatile private var online: Boolean? = null
    private val requested = AtomicLong()
    private val mutable = MutableStateFlow(UnifiedSyncState(
        automatic = preferences.getBoolean("automatic", true),
        lastChecked = preferences.getString("lastChecked", null),
    ))
    val state = mutable.asStateFlow()

    fun initialize() {
        val manager = WorkManager.getInstance(app)
        // Replace schedules created by earlier app versions. Their queued data stays in Room.
        if (!preferences.getBoolean("migrated", false)) {
            listOf("lifeapp-todo-server-sync", "lifeapp-expense-sync", "lifeapp-workout-sync",
                "lifeapp-editable-server-sync", "lifeapp-editable-upload").forEach(manager::cancelUniqueWork)
            preferences.edit().putBoolean("migrated", true).apply()
        }
        periodic()
    }

    fun networkChanged(available: Boolean) {
        val reconnect = online == false && available
        online = available
        if (!available) mutable.update { it.copy(status = "Offline") }
        if (available && mutable.value.status == "Offline") mutable.update { it.copy(status = "Ready to sync") }
        if (reconnect) schedule()
    }

    fun setAutomatic(enabled: Boolean) {
        preferences.edit().putBoolean("automatic", enabled).apply()
        mutable.update { it.copy(automatic = enabled) }
        if (enabled) {
            periodic()
            schedule()
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(app).cancelUniqueWork(RETRY)
        }
    }

    private fun periodic() {
        if (!mutable.value.automatic) return
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UnifiedSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun schedule(manual: Boolean = false) {
        if (!manual && !mutable.value.automatic) return
        requested.incrementAndGet()
        if (!mutable.value.running) mutable.update { it.copy(status = if (online == false) "Offline" else "Pending sync") }
        WorkManager.getInstance(app).enqueueUniqueWork(ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<UnifiedSyncWorker>()
                .setInputData(workDataOf("manual" to manual))
                .setInitialDelay(if (manual) 0 else 1, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    suspend fun synchronize(manual: Boolean): Boolean = gate.withLock {
        if (!manual && !mutable.value.automatic) return true
        val credential = try {
            ServerCredentialStore(app).load()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutable.update { it.copy(status = "Needs attention", issues = listOf("Could not read the saved sign-in. Sign in again.")) }
            return true
        }
        if (credential == null) {
            mutable.update { it.copy(status = "Signed out", issues = emptyList(), lastChecked = null) }
            return true
        }
        val identity = "${credential.endpoint.origin}/${credential.session.scope.dataset}/${credential.session.scope.epoch}"
        if (preferences.getString("scope", null) != identity) {
            val edit = preferences.edit().putString("scope", identity).remove("lastChecked")
            listOf("To-do", "Expenses", "Workouts", "Habits").forEach {
                edit.remove("retry/$it").remove("failures/$it")
            }
            edit.apply()
            mutable.update { it.copy(lastChecked = null, issues = emptyList()) }
        }
        val started = requested.get()
        mutable.update { it.copy(running = true, status = "Syncing") }
        val issues = mutableListOf<String>()
        var retryModule = false
        var nextRetry = 0L
        suspend fun module(name: String, action: suspend () -> Unit) {
            val deadline = preferences.getLong("retry/$name", 0)
            if (deadline > System.currentTimeMillis()) {
                issues += "$name · Waiting to retry."
                nextRetry = if (nextRetry == 0L) deadline else minOf(nextRetry, deadline)
                return
            }
            retryModule = false
            var serverDelay = 0L
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                issues += "$name · ${failure.message ?: "Could not synchronize"}"
                retryModule = failure !is ServerHttpFailure || failure.retryable
                serverDelay = (failure as? ServerHttpFailure)?.retrySeconds?.coerceIn(0, 86_400)?.times(1000) ?: 0
            }
            if (retryModule) {
                val attempts = preferences.getInt("failures/$name", 0).coerceAtMost(6)
                val delay = maxOf(serverDelay, minOf(300_000L, 30_000L * (1L shl attempts)))
                val until = System.currentTimeMillis() + delay
                preferences.edit().putLong("retry/$name", until).putInt("failures/$name", attempts + 1).apply()
                nextRetry = if (nextRetry == 0L) until else minOf(nextRetry, until)
            } else {
                preferences.edit().remove("retry/$name").remove("failures/$name").apply()
            }
        }
        try {
            module("To-do") {
                if (app.todos.checkpoint() == null) {
                    issues += "To-do · Review synchronization to connect."
                } else {
                    val outcome = app.serverSync.work(uploadOnly = false)
                    val state = app.serverSync.state.value
                    if (outcome != SyncOutcome.DONE) retryModule = true
                    when {
                        state.conflicts.isNotEmpty() -> issues += "To-do · ${state.conflicts.size} conflicts need review."
                        state.error -> issues += "To-do · ${state.message ?: "Needs attention"}"
                        state.pending > 0 -> issues += "To-do · Pending changes."
                    }
                }
            }
            module("Expenses") {
                if (app.expenses.syncState() == null) {
                    issues += "Expenses · Review synchronization to connect."
                } else {
                    val until = app.getSharedPreferences("expense-sync-retry", android.content.Context.MODE_PRIVATE).getLong("until", 0)
                    if (until > System.currentTimeMillis()) {
                        retryModule = true
                        issues += "Expenses · Waiting to retry."
                    } else {
                        if (!app.expenseSync.synchronize()) retryModule = true
                        val message = app.expenseSync.status.value
                        val state = app.expenses.syncState()
                        if (app.expenseSync.needsAttention || state?.pending?.isNotEmpty() == true ||
                            state?.effectiveHeads()?.values?.any { versions -> versions.map { it.record }.distinct().size > 1 } == true) {
                            issues += "Expenses · ${message ?: "Pending changes"}"
                        }
                    }
                }
            }
            module("Workouts") {
                if (app.workouts.syncBinding() == null) {
                    issues += "Workouts · Review synchronization to connect."
                } else {
                    if (!app.workoutSync.synchronize()) retryModule = true
                    val message = app.workoutSync.status.value
                    if (app.workoutSync.needsAttention) issues += "Workouts · ${message ?: "Needs attention"}"
                }
            }
            module("Habits") {
                if (!app.serverSync.automaticEditableEnabled()) {
                    issues += "Habits · Review synchronization to connect."
                } else {
                    val complete = app.operations.synchronizeHabits(app.serverSync)
                    val message = app.serverSync.state.value.editableStatus
                    if (!complete) issues += "Habits · ${message ?: "Needs attention"}"
                }
            }
            val checked = if (issues.isEmpty() && started == requested.get()) kotlin.time.Clock.System.now().toString() else mutable.value.lastChecked
            if (issues.isEmpty() && started == requested.get()) preferences.edit().putString("lastChecked", checked).apply()
            mutable.update { it.copy(
                status = if (issues.isNotEmpty()) "Needs attention" else if (started != requested.get()) "Pending sync" else "Up to date",
                lastChecked = checked, issues = issues,
            ) }
            if (nextRetry > 0 && mutable.value.automatic) {
                WorkManager.getInstance(app).enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<UnifiedSyncWorker>()
                        .setInitialDelay(maxOf(1000, nextRetry - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            }
            true
        } finally {
            mutable.update { it.copy(running = false) }
        }
    }

    companion object {
        private const val ONCE = "lifeapp-structured-sync"
        private const val RETRY = "lifeapp-structured-retry"
        private const val PERIODIC = "lifeapp-structured-periodic"
    }
}

class UnifiedSyncWorker(context: android.content.Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = (applicationContext as JournalApplication).unifiedSync
        controller.synchronize(inputData.getBoolean("manual", false))
        return Result.success()
    }
}
