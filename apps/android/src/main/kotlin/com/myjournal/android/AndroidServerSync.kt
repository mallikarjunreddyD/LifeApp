package com.myjournal.android

import android.content.Context
import androidx.work.*
import com.myjournal.data.BackupArchive
import com.myjournal.data.RoomTodoRepository
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
    val pendingImport: Int = 0,
    val automaticEditable: Boolean = false,
    val editableStatus: String? = null,
    val importQueueUnreadable: Boolean = false,
    val working: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val preview: BindingPreview? = null,
    val lastSync: String? = null,
    val conflicts: List<List<TodoOperation>> = emptyList(),
)

internal enum class SyncOutcome { DONE, RETRY, CONTINUE }

internal class AndroidServerSync(
    private val context: Context,
    private val repository: RoomTodoRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val apiFor: (ServerEndpoint) -> ServerApi = { ServerApi(it) },
    editableDirectory: File = context.noBackupFilesDir,
) {
    private val store = ServerCredentialStore(context)
    private val editableBaselines = EditableSyncBaselines(editableDirectory.resolve("editable-baselines"))
    private val importQueue = EditableImportQueue(editableDirectory.resolve("editable-import"))
    private val gate = Mutex()
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
        val api = apiFor(endpoint(origin))
        api.ready()
        message("LifeApp server is ready. Sign in to verify the account.")
    }

    fun signIn(origin: String, username: String, password: String, secondFactor: String?) = action(invalidateOnExpired = false) {
        val api = apiFor(endpoint(origin))
        val pinned = repository.boundOrigin()
        if (pinned != null && pinned != api.endpoint.origin) throw UserSyncError("This database is bound to a different HTTPS origin.")
        val credential = api.login(username.trim(), password, secondFactor)
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

    suspend fun inspectJournal(expectedOrigin: String, expectedScope: SyncScope, backup: JournalSnapshot): JournalImportInventory =
        gate.withLock {
            try {
                val (credential, session, api) = currentSession()
                if (api.endpoint.origin != expectedOrigin || session.scope != expectedScope) {
                    throw UserSyncError("The server or dataset changed. Sign in and review the journal inventory again.")
                }
                withContext(Dispatchers.IO) {
                    JournalImportInventory.compare(backup, api.journalEntries(session, credential.token))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if ((failure is ServerHttpFailure && failure.status == 401) || failure is ExpiredSyncSession) {
                    store.clear()
                    refresh(null, load = false)
                }
                throw UserSyncError("The backup was verified. Journal review failed. ${safeMessage(failure)}")
            }
        }

    suspend fun reviewEditableImport(target: EditableImportQueue.Target, backup: JournalSnapshot): EditableImportReview =
        gate.withLock {
            val (credential, session, api) = currentSession()
            requireImportTarget(target, api, session)
            withContext(Dispatchers.IO) {
                importQueue.finishIfEmpty()
                check(importQueue.load() == null) { "Resume the previous record upload first." }
                planEditable(target, backup, session, api, credential.token, trackedOnly = false)
            }
        }

    private suspend fun planEditable(
        target: EditableImportQueue.Target, backup: JournalSnapshot, session: ServerSession, api: ServerApi,
        token: String, trackedOnly: Boolean,
    ): EditableImportReview {
        val local = backup.editableImportRecords().associateBy { it.key }
        val remote = api.editableVersions(session, token).groupBy { it.key }
        val legacy = if (trackedOnly) emptySet() else api.journalEntries(session, token).map { EditableImportKey("journal", it.date.toString()) }.toSet()
        val serverKeys = remote.keys + legacy
        val known = editableBaselines.load(target)
        val matched = mutableMapOf<EditableImportKey, EditableSyncBaselines.Baseline>()
        val uploads = local.values.filter { !trackedOnly && it.key !in serverKeys }.toMutableList()
        val uploadParents = mutableMapOf<EditableImportKey, Uuid>()
        val habitChecks = mutableMapOf<EditableImportKey, List<HabitCompletion>>()
        val downloads = remote.filter { (key, variants) -> !trackedOnly && key !in local && variants.size == 1 }.values.map { it.single() }.toMutableList()
        val replacements = mutableMapOf<EditableImportKey, JournalSnapshot>()
        val overlaps = local.keys.filter { it in legacy }.toMutableList()
        val file = java.io.File.createTempFile("lifeapp-review-", ".zip", context.cacheDir)
        try {
            for ((key, record) in local) {
                val variants = remote[key] ?: continue
                val version = variants.singleOrNull() ?: continue
                val fingerprint = EditableSyncBaselines.fingerprint(record.snapshot)
                val base = known[key]
                if (trackedOnly && base == null) continue
                if (base?.fingerprint == fingerprint && base.version == version.id) continue
                // Confirm identical content before treating an older installation's copy as a shared base.
                if (base == null || base.fingerprint != fingerprint && version.id != base.version) {
                    api.downloadEditableVersion(session, token, version, file)
                    val source = BackupArchive.read(file).snapshot
                    require(source.editableImportRecords().single().key == key)
                    if (EditableSyncBaselines.fingerprint(source) == fingerprint) {
                        matched[key] = EditableSyncBaselines.Baseline(version.id, fingerprint)
                        continue
                    }
                }
                if (base == null) {
                    overlaps += key
                } else if (base.fingerprint == fingerprint) {
                    downloads += version
                    replacements[key] = record.snapshot
                } else {
                    uploads += record
                    uploadParents[key] = base.version
                    if (key.kind == "habits") {
                        api.downloadEditableArchive(session, token, base.version, file)
                        val previous = BackupArchive.read(file).snapshot.habits.single()
                        val latest = record.snapshot.habits.single()
                        val checks = changedHabitChecks(previous, latest)
                        if (checks.isNotEmpty()) habitChecks[key] = checks
                    }
                }
            }
            editableBaselines.record(target, matched)
        } finally {
            file.delete()
        }
        val conflicted = remote.filterValues { it.size > 1 }.keys.toList()
        return EditableImportReview(target, uploads, overlaps, serverKeys.size, downloads, conflicted, uploadParents, replacements, habitChecks)
    }

    private fun changedHabitChecks(before: MonthlyHabitLedger, after: MonthlyHabitLedger): List<HabitCompletion> {
        if (before.metadata.id != after.metadata.id || before.metadata.createdAt != after.metadata.createdAt ||
            before.month != after.month || before.habits != after.habits) return emptyList()
        val previous = before.completions.associateBy { it.habitId to it.date }
        val current = after.completions.associateBy { it.habitId to it.date }
        if (!current.keys.containsAll(previous.keys)) return emptyList()
        val changes = current.filter { (key, completion) -> previous[key] != completion }
        val active = after.activeHabits.map { it.metadata.id }.toSet()
        if (changes.any { (key, completion) ->
                val old = previous[key]
                completion.habitId !in active || (old != null && (old.metadata.id != completion.metadata.id ||
                    old.metadata.createdAt != completion.metadata.createdAt || old.metadata.revision >= completion.metadata.revision ||
                    old.metadata.updatedAt > completion.metadata.updatedAt || old.checked == completion.checked))
            }) return emptyList()
        return changes.values.sortedWith(compareBy({ it.date }, { it.habitId.toString() }))
    }

    private suspend fun downloadReviewed(
        review: EditableImportReview, credential: ServerCredential, session: ServerSession, api: ServerApi,
    ): List<EditableImportRecord> =
        withContext(Dispatchers.IO) {
            fun verifyReviewedVersions(current: List<EditableRemoteVersion>) {
                val versions = current.groupBy { it.key }
                check(review.downloadable.all { versions[it.key] == listOf(it) }) {
                    "The server versions changed. Create a fresh backup and review again."
                }
            }
            verifyReviewedVersions(api.editableVersions(session, credential.token))
            val file = java.io.File.createTempFile("lifeapp-import-", ".zip", context.cacheDir)
            try {
                var remaining = BackupArchive.MAX_TOTAL_BYTES
                val records = review.downloadable.map { version ->
                    api.downloadEditableVersion(session, credential.token, version, file)
                    java.util.zip.ZipFile(file).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val size = entries.nextElement().size
                            require(size in 0..remaining) { "The reviewed records exceed the 64 MiB expanded import limit." }
                            remaining -= size
                        }
                    }
                    val snapshot = BackupArchive.read(file).snapshot
                    val record = snapshot.editableImportRecords().single()
                    require(record.key == version.key && record.snapshot.canonical() == snapshot.canonical())
                    record
                }
                verifyReviewedVersions(api.editableVersions(session, credential.token))
                records
            } finally {
                file.delete()
            }
        }
    suspend fun resetEditableBaselines() = gate.withLock {
        withContext(Dispatchers.IO) {
            importQueue.invalidateBaselines()
            editableBaselines.clear()
        }
    }

    suspend fun applyReviewedDownloads(
        review: EditableImportReview, apply: suspend (List<EditableImportRecord>) -> Unit,
    ): Int = gate.withLock {
        val (credential, session, api) = currentSession()
        requireImportTarget(review.target, api, session)
        applyTrackedDownloads(review, credential, session, api, apply)
    }

    private suspend fun applyTrackedDownloads(
        review: EditableImportReview, credential: ServerCredential, session: ServerSession, api: ServerApi,
        apply: suspend (List<EditableImportRecord>) -> Unit,
    ): Int {
        val records = downloadReviewed(review, credential, session, api)
        apply(records)
        try {
            val versions = review.downloadable.associateBy { it.key }
            withContext(Dispatchers.IO) {
                editableBaselines.record(review.target, records.associate {
                    it.key to EditableSyncBaselines.Baseline(versions.getValue(it.key).id, EditableSyncBaselines.fingerprint(it.snapshot))
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw UserSyncError("Records were applied, but their shared versions could not be saved. Review again.")
        }
        return records.size
    }

    suspend fun queueEditableImport(review: EditableImportReview) = gate.withLock {
        val (_, session, api) = currentSession()
        requireImportTarget(review.target, api, session)
        withContext(Dispatchers.IO) { importQueue.enqueue(review.target, review.eligible, review.uploadParents, review.habitChecks) }
        refresh()
    }

    fun resumeEditableImport() = action {
        val (credential, session, api) = currentSession()
        try {
            val conflicts = sendEditableQueue(credential, session, api)
            if (conflicts == 0) message("Approved record uploads completed. Local records were kept.")
            else throw UserSyncError("$conflicts uploads need conflict review. Their local copies are still queued.")
        } finally {
            refresh()
        }
    }

    private suspend fun sendEditableQueue(credential: ServerCredential, session: ServerSession, api: ServerApi): Int =
        withContext(Dispatchers.IO) {
            val batch = importQueue.load() ?: return@withContext 0
            requireImportTarget(batch.target, api, session)
            var rejected = 0
            for (file in batch.files) {
                currentCoroutineContext().ensureActive()
                check(file.length() in 1..EditableImportQueue.MAX_ARCHIVE_BYTES)
                val digest = BackupArchive.sha256(file.readBytes())
                val ids = file.nameWithoutExtension.split('_').map(Uuid::parse)
                val version = ids.first()
                val parent = ids.getOrNull(1)
                val checks = importQueue.checks(file)
                try {
                    if (checks.isEmpty()) {
                        api.uploadEditableImport(session, credential.token, version, file, digest, parent)
                    } else {
                        for (check in checks) api.uploadHabitCheck(session, credential.token, requireNotNull(parent), check)
                    }
                } catch (failure: ServerHttpFailure) {
                    if (failure.code != "CONFLICT_CHANGED") throw failure
                    rejected++
                    continue
                }
                if (batch.recordsBaselines) {
                    val record = BackupArchive.read(file).snapshot.editableImportRecords().single()
                    editableBaselines.record(batch.target, mapOf(record.key to
                        EditableSyncBaselines.Baseline(if (checks.isEmpty()) version else requireNotNull(parent),
                            EditableSyncBaselines.fingerprint(record.snapshot))))
                }
                importQueue.acknowledge(file)
            }
            importQueue.finishIfEmpty()
            rejected
        }

    fun setAutomaticEditable(enabled: Boolean) = action {
        if (enabled) {
            val (_, session, api) = currentSession()
            val target = EditableImportQueue.Target(api.endpoint.origin, session.scope)
            check(withContext(Dispatchers.IO) { editableBaselines.load(target).isNotEmpty() }) {
                "Confirm a shared record through the backup-verified transfer first."
            }
            store.setEditableEnabled(target)
            val request = PeriodicWorkRequestBuilder<EditableSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(EDITABLE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            store.setEditableEnabled(null)
            WorkManager.getInstance(context).cancelUniqueWork(EDITABLE_WORK)
        }
        refresh()
        message(if (enabled) "Automatic sync enabled for tracked records." else "Automatic record sync paused.")
    }

    suspend fun automaticEditableEnabled(): Boolean = gate.withLock {
        val credential = store.load() ?: return@withLock false
        store.editableEnabled(EditableImportQueue.Target(credential.endpoint.origin, credential.session.scope))
    }

    suspend fun syncTrackedRecords(
        capture: suspend () -> JournalSnapshot,
        apply: (suspend (List<EditableImportRecord>, Map<EditableImportKey, JournalSnapshot>) -> Unit)? = null,
    ) = gate.withLock {
        val saved = store.load() ?: return@withLock
        val target = EditableImportQueue.Target(saved.endpoint.origin, saved.session.scope)
        if (!store.editableEnabled(target) || saved.session.expiresAt <= Clock.System.now()) return@withLock
        try {
            val (credential, session, api) = currentSession()
            requireImportTarget(target, api, session)
            if (sendEditableQueue(credential, session, api) > 0) {
                mutable.update { it.copy(editableStatus = "Queued record uploads need review.") }
                return@withLock
            }
            suspend fun plan(): EditableImportReview {
                val snapshot = capture()
                return withContext(Dispatchers.IO) {
                    planEditable(target, snapshot, session, api, credential.token, trackedOnly = true)
                }
            }
            var review = plan()
            if (review.eligible.isNotEmpty()) {
                withContext(Dispatchers.IO) { importQueue.enqueue(target, review.eligible, review.uploadParents, review.habitChecks) }
                if (sendEditableQueue(credential, session, api) > 0) {
                    mutable.update { it.copy(editableStatus = "Queued record uploads need review.") }
                    return@withLock
                }
                if (apply != null) review = plan()
            }
            if (apply != null && review.downloadable.isNotEmpty()) {
                applyTrackedDownloads(review, credential, session, api) { records -> apply(records, review.replaceExpected) }
            }
            mutable.update { it.copy(editableStatus = if (review.conflicted.isEmpty())
                "Tracked records checked." else "Competing server versions need review.") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(editableStatus = safeMessage(failure)) }
            throw failure
        } finally {
            refresh()
        }
    }

    fun discardEditableImport() = action {
        withContext(Dispatchers.IO) { importQueue.discard() }
        refresh()
        message("Queued upload copies cleared. Your backup and Android records were kept.")
    }

    private fun requireImportTarget(target: EditableImportQueue.Target, api: ServerApi, session: ServerSession) {
        if (target.origin != api.endpoint.origin || target.scope != session.scope) {
            throw UserSyncError("The upload belongs to a different HTTPS origin, dataset or recovery epoch. Local records were kept.")
        }
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
        store.setEditableEnabled(null)
        WorkManager.getInstance(context).cancelUniqueWork(EDITABLE_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        val credential = runCatching { store.load() }.getOrNull()
        store.clear()
        mutable.update { it.copy(preview = null) }
        refresh(null, load = false)
        val revoked = credential == null || try {
            apiFor(credential.endpoint).logout(credential.token)
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
        val api = apiFor(credential.endpoint)
        val session = api.session(credential.token)
        if (session.scope != credential.session.scope) throw UserSyncError("The server recovery epoch changed. Local records were kept.")
        return Triple(credential, session, api)
    }

    private suspend fun refresh(credential: ServerCredential? = null, load: Boolean = true) {
        val saved = credential ?: if (load) store.load() else null
        val checkpoint = repository.checkpoint()
        val pinned = repository.boundOrigin()
        val pendingImport = withContext(Dispatchers.IO) {
            try { importQueue.load()?.files?.size ?: 0 }
            catch (_: java.io.IOException) { null }
            catch (_: IllegalArgumentException) { null }
        }
        val conflicts = if (checkpoint == null) emptyList() else repository.allConflicts()
        mutable.update { it.copy(
            origin = pinned ?: saved?.endpoint?.origin ?: store.origin(),
            username = saved?.username,
            accountScope = saved?.session?.scope,
            bound = checkpoint,
            pinnedOrigin = pinned,
            lastSync = store.lastVerified(),
            pendingImport = pendingImport ?: 0,
            automaticEditable = saved?.let { store.editableEnabled(EditableImportQueue.Target(it.endpoint.origin, it.session.scope)) } == true,
            importQueueUnreadable = pendingImport == null,
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

    companion object {
        private const val WORK = "lifeapp-todo-server-sync"
        private const val EDITABLE_WORK = "lifeapp-editable-server-sync"
    }
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


class EditableSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as JournalApplication
        return try {
            app.serverSync.syncTrackedRecords(app.snapshots::capture)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ServerHttpFailure) {
            if (failure.retryable && runAttemptCount < 5) Result.retry() else Result.success()
        } catch (_: SSLException) {
            Result.success()
        } catch (_: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.success()
        } catch (_: IllegalStateException) {
            Result.success()
        } catch (_: IllegalArgumentException) {
            Result.success()
        }
    }
}
