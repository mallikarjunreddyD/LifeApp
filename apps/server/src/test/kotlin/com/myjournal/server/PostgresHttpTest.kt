package com.myjournal.server

import com.myjournal.data.BackupArchive
import com.myjournal.data.JournalInkJson
import com.myjournal.data.SnapshotJson
import com.myjournal.data.StrokePointCodec
import com.myjournal.data.TodoOperationJson
import com.myjournal.domain.CalendarLayout
import com.myjournal.domain.JournalMonth
import com.myjournal.domain.MonthlyHabitLedger
import com.myjournal.domain.MonthlyPlanner
import com.myjournal.domain.Notebook
import com.myjournal.domain.NotebookPage
import com.myjournal.domain.BrushTool
import com.myjournal.domain.InkPoint
import com.myjournal.domain.InkStroke
import com.myjournal.domain.JournalEntry
import com.myjournal.domain.JournalPage
import com.myjournal.domain.JournalSnapshot
import com.myjournal.domain.newRecordMetadata
import kotlinx.datetime.LocalDate
import java.security.MessageDigest
import java.util.HexFormat
import com.myjournal.data.TodoServerJson
import com.myjournal.domain.SyncCheckpoint
import com.myjournal.domain.SyncScope
import com.myjournal.domain.TodoOperation
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.uuid.Uuid

class PostgresHttpTest {
    private val origin = "https://lifeapp.example.test"
    private val password = "disposable test password"
    private val user = "owner-${Uuid.random()}"
    private val clock = TestClock()

    private val database = Database(PGSimpleDataSource().apply {
        setURL(requireNotNull(System.getenv("LIFEAPP_TEST_DB_URL")))
        user = System.getenv("LIFEAPP_TEST_DB_USER") ?: "postgres"
        password = System.getenv("LIFEAPP_TEST_DB_PASSWORD")
    }).also { it.migrate() }
    private val auth = Authentication(database, clock)
    private val scope = auth.initializeOwner(user, password.toCharArray(), requireEmpty = false)
    private val fixture = TodoOperationJson.decode(File("../../contracts/fixtures/todo-operation.json").readText())

    @Test fun browserConsentCsrfExpiryAndRevocation() = testApplication {
        application { lifeApp(database, auth, origin) }
        assertEquals(200, client.get("/health").status.value)
        assertEquals(200, client.get("/ready").status.value)
        for (path in listOf("/v1/session", "/v1/bootstrap", "/v1/todos", "/v1/exercises", "/v1/workouts",
            "/v1/backups/status", "/v1/conflicts", "/v1/sync/pull")) {
            assertEquals(401, client.get(path).status.value, path)
        }
        assertEquals(403, client.login(user, browser = true, suppliedOrigin = "https://other.example.test").status.value)
        val login = client.login(user, browser = true, suppliedOrigin = origin)
        assertEquals(200, login.status.value)
        val cookieHeader = requireNotNull(login.headers[HttpHeaders.SetCookie])
        assertTrue(listOf("Secure", "HttpOnly", "SameSite=Strict", "Path=/").all { it in cookieHeader })
        val cookie = cookieHeader.substringBefore(';')
        val session = login.json()
        assertNull(session["accessToken"])
        val csrf = session.getValue("csrfToken").jsonPrimitive.content
        assertEquals(403, client.delete("/v1/session") { header(HttpHeaders.Cookie, cookie) }.status.value)
        assertEquals(403, client.delete("/v1/session") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", "invalid")
        }.status.value)
        assertEquals(204, client.delete("/v1/session") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
        }.status.value)
        assertEquals(401, client.get("/v1/session") { header(HttpHeaders.Cookie, cookie) }.status.value)
        val token = client.login(user).token()
        clock.now = clock.now.plusSeconds(3_601)
        assertEquals(401, client.get("/v1/session") { bearerAuth(token) }.status.value)
    }

    @Test fun ownerRenameEnforcesAuthenticatorAndConsumesRecoveryCode() = testApplication {
        val seed = kotlin.io.path.createTempFile("lifeapp-test-totp")
        try {
            java.nio.file.Files.writeString(seed, "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            val key = OwnerTotp.load(seed)
            assertEquals("287082", OwnerTotp.generate(key, 1))
            val protectedAuth = Authentication(database, clock, seed)
            application { lifeApp(database, protectedAuth, origin) }
            val oldToken = client.login(user).token()
            val owner = "$user-renamed"
            val enrollmentCode = OwnerTotp.generate(key, clock.instant().epochSecond / 30)
            val recovery = protectedAuth.enrollOwner(user, owner, enrollmentCode)
            assertEquals(401, client.get("/v1/session") { bearerAuth(oldToken) }.status.value)
            assertEquals(401, client.login(user, secondFactor = recovery.first()).status.value)
            assertEquals(401, client.login(owner).status.value)
            assertFailsWith<java.sql.SQLException> {
                database.transaction {
                    execute(
                        "INSERT INTO sessions SELECT ?, id, now() + interval '1 hour', NULL FROM accounts WHERE username = ?",
                        Credentials.tokenHash(Credentials.token()), owner,
                    )
                }
            }
            assertEquals(401, client.login(owner, secondFactor = enrollmentCode).status.value)

            clock.now = clock.now.plusSeconds(60)
            val code = OwnerTotp.generate(key, clock.instant().epochSecond / 30)
            val login = client.login(owner, browser = true, suppliedOrigin = origin, secondFactor = code)
            assertEquals(200, login.status.value)
            assertEquals(scope.dataset.toString(), login.json()["scope"]!!.jsonObject["dataset"]!!.jsonPrimitive.content)
            assertEquals(scope.epoch.toString(), login.json()["scope"]!!.jsonObject["epoch"]!!.jsonPrimitive.content)
            assertEquals(401, client.login(owner, secondFactor = code).status.value)
            assertEquals(200, client.login(owner, secondFactor = recovery.first()).status.value)
            assertEquals(401, client.login(owner, secondFactor = recovery.first()).status.value)
            clock.now = clock.now.plusSeconds(60)
            val replacement = protectedAuth.enrollOwner(owner, owner,
                OwnerTotp.generate(key, clock.instant().epochSecond / 30), rotate = true)
            assertEquals(401, client.login(owner, secondFactor = recovery[1]).status.value)
            assertEquals(200, client.login(owner, secondFactor = replacement.first()).status.value)
        } finally {
            java.nio.file.Files.deleteIfExists(seed)
        }
    }

    @Test fun inkStagingPreservesExactBytesAndEnforcesScopeAndRetryIdentity() = testApplication {
        application { lifeApp(database, auth, origin) }
        val id = Uuid.random()
        val bytes = StrokePointCodec.encode(listOf(InkPoint(-0.0f, 2f, 0, null)))
        fun digest(content: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        val token = client.login(user).token()
        suspend fun upload(
            accessToken: String? = token,
            epoch: Uuid = scope.epoch,
            content: ByteArray = bytes,
            checksum: String = digest(content),
        ) = client.put("/v1/ink/staged/$id") {
            accessToken?.let { bearerAuth(it) }
            header("X-LifeApp-Dataset", scope.dataset.toString())
            header("X-LifeApp-Epoch", epoch.toString())
            header("X-Content-SHA256", checksum)
            contentType(ContentType.Application.OctetStream)
            setBody(content)
        }
        assertEquals(401, upload(accessToken = null).status.value)
        val webLogin = client.login(user, browser = true, suppliedOrigin = origin)
        val cookie = requireNotNull(webLogin.headers[HttpHeaders.SetCookie]).substringBefore(';')
        assertEquals(403, client.put("/v1/ink/staged/$id") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }.status.value)
        assertEquals(409, upload(epoch = Uuid.random()).status.value)
        assertEquals(400, upload(checksum = "0".repeat(64)).status.value)
        assertEquals(400, upload(content = ByteArray(32)).status.value)
        val other = "other-${Uuid.random()}"
        auth.initializeOwner(other, password.toCharArray(), requireEmpty = false)
        assertEquals(409, upload(accessToken = client.login(other).token()).status.value)
        assertEquals(204, upload().status.value)
        assertEquals(204, upload().status.value)
        val different = StrokePointCodec.encode(listOf(InkPoint(1f, 2f, 0, null)))
        assertEquals(409, upload(content = different).status.value)
        val saved = database.transaction {
            query("SELECT bytes FROM staged_ink WHERE dataset_id = ? AND id = ?", scope.dataset.sql(), id.sql()) {
                it.getBytes(1)
            }.single()
        }
        assertContentEquals(bytes, saved)
        database.transaction {
            execute("UPDATE staged_ink SET expires_at = now() WHERE dataset_id = ? AND id = ?", scope.dataset.sql(), id.sql())
        }
        assertEquals(204, upload(content = different).status.value)
    }

    @Test fun journalInkCommitIsAtomicAndReturnsOriginalBytesOnlyToItsOwner() = testApplication {
        application { lifeApp(database, auth, origin) }
        val token = client.login(user).token()
        val entry = newRecordMetadata()
        val page = JournalPage(newRecordMetadata(), entry.id, 0)
        val journal = JournalEntry(entry, LocalDate(2026, 11, 9), listOf(page))
        fun entryJson(value: JournalEntry) = Json.parseToJsonElement(SnapshotJson.encode(JournalSnapshot(journals = listOf(value))))
            .jsonObject.getValue("journals").jsonArray.single().jsonObject
        val stroke = InkStroke(newRecordMetadata(), page.metadata.id, 0, BrushTool.PEN, 0xFF000000, 3f,
            listOf(InkPoint(-0.0f, 2f, 0, null)))
        val bytes = StrokePointCodec.encode(stroke.points)
        val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val upload = Uuid.random()
        val url = "/v1/journal/ink/${stroke.metadata.id}"
        suspend fun commit(value: JournalEntry = journal, header: JsonObject = JournalInkJson.strokeHeader(stroke),
                           epoch: Uuid = scope.epoch, uploadId: Uuid = upload) = client.post("/v1/journal/ink/commit") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("scope", buildJsonObject { put("dataset", scope.dataset.toString()); put("epoch", epoch.toString()) })
                put("entry", entryJson(value))
                put("stroke", header)
                put("uploadId", uploadId.toString())
                put("sha256", checksum)
            }.toString())
        }
        suspend fun stage(uploadId: Uuid) = client.put("/v1/ink/staged/$uploadId") {
            bearerAuth(token)
            header("X-LifeApp-Dataset", scope.dataset.toString())
            header("X-LifeApp-Epoch", scope.epoch.toString())
            header("X-Content-SHA256", checksum)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        assertEquals(401, client.get(url).status.value)
        assertEquals(409, commit().status.value)
        assertEquals(204, stage(upload).status.value)
        assertEquals(404, client.get(url) { bearerAuth(token) }.status.value)
        assertEquals(409, commit(epoch = Uuid.random()).status.value)
        assertEquals(400, commit(journal.copy(pages = listOf(page.copy(height = 1f)))).status.value)
        val entryCount = database.transaction {
            query("SELECT count(*) FROM journal_ink_entries WHERE dataset_id = ?", scope.dataset.sql()) { it.getInt(1) }.single()
        }
        assertEquals(0, entryCount)
        assertEquals(204, commit().status.value)
        assertEquals(204, commit().status.value)
        assertEquals(409, commit(header = JournalInkJson.strokeHeader(stroke.copy(width = 4f))).status.value)
        val other = "other-${Uuid.random()}"
        auth.initializeOwner(other, password.toCharArray(), requireEmpty = false)
        val otherToken = client.login(other).token()
        assertEquals(404, client.get(url) { bearerAuth(otherToken) }.status.value)
        val entriesUrl = "/v1/journal/entries"
        val strokesUrl = "/v1/journal/pages/${page.metadata.id}/strokes"
        suspend fun readJournal(path: String, bearer: String = token, requested: SyncScope = scope) = client.get(path) {
            bearerAuth(bearer)
            header("X-LifeApp-Dataset", requested.dataset.toString())
            header("X-LifeApp-Epoch", requested.epoch.toString())
        }
        assertEquals(401, client.get(entriesUrl).status.value)
        assertEquals(400, client.get(entriesUrl) { bearerAuth(token) }.status.value)
        assertEquals(409, readJournal(entriesUrl, otherToken).status.value)
        assertEquals(409, readJournal(url, requested = scope.copy(epoch = Uuid.random())).status.value)
        val otherFields = client.get("/v1/session") { bearerAuth(otherToken) }.json().getValue("scope").jsonObject
        val otherScope = SyncScope(Uuid.parse(otherFields.getValue("dataset").jsonPrimitive.content),
            Uuid.parse(otherFields.getValue("epoch").jsonPrimitive.content))
        assertEquals(404, readJournal(strokesUrl, otherToken, otherScope).status.value)
        assertEquals(0, readJournal(entriesUrl, otherToken, otherScope).json().getValue("items").jsonArray.size)
        assertEquals(400, readJournal("$strokesUrl?cursor=${Uuid.random()}").status.value)
        val listed = readJournal(entriesUrl)
        assertEquals("no-store", listed.headers["Cache-Control"])
        assertEquals(entryJson(journal), listed.json().getValue("items").jsonArray.single())
        val second = stroke.copy(metadata = newRecordMetadata(), order = Long.MAX_VALUE)
        val secondUpload = Uuid.random()
        assertEquals(204, stage(secondUpload).status.value)
        assertEquals(204, commit(header = JournalInkJson.strokeHeader(second), uploadId = secondUpload).status.value)
        val firstPage = readJournal("$strokesUrl?limit=1").json()
        val cursor = firstPage.getValue("next").jsonPrimitive.content
        assertEquals(stroke.metadata.id.toString(), cursor)
        val listedStroke = firstPage.getValue("items").jsonArray.single().jsonObject
        assertEquals(checksum, listedStroke.getValue("sha256").jsonPrimitive.content)
        assertEquals(JournalInkJson.strokeHeader(stroke), listedStroke.getValue("stroke"))
        val lastPage = readJournal("$strokesUrl?limit=1&cursor=$cursor").json()
        assertEquals(JsonNull, lastPage.getValue("next"))
        assertEquals(JournalInkJson.strokeHeader(second), lastPage.getValue("items").jsonArray.single().jsonObject.getValue("stroke"))
        val result = client.get(url) { bearerAuth(token) }
        assertEquals(200, result.status.value)
        assertEquals(checksum, result.headers["X-Content-SHA256"])
        assertContentEquals(bytes, result.body<ByteArray>())
        val remaining = database.transaction {
            query("SELECT count(*) FROM staged_ink WHERE dataset_id = ?", scope.dataset.sql()) { it.getInt(1) }.single()
        }
        assertEquals(0, remaining)
    }

    @Test fun editableRecordArchivesPreserveVariantsAndOriginalBytes() = testApplication {
        application { lifeApp(database, auth, origin) }
        val token = client.login(user).token()
        val date = LocalDate(2026, 11, 9)
        val month = JournalMonth.from(date)
        fun journal(): JournalSnapshot {
            val entry = newRecordMetadata()
            val first = JournalPage(newRecordMetadata(), entry.id, 0)
            val archived = JournalPage(newRecordMetadata(), entry.id, 1, archived = true)
            val stroke = InkStroke(newRecordMetadata(), archived.metadata.id, 0, BrushTool.PEN,
                0xff000000, 3f, listOf(InkPoint(-0.0f, 5f, 0)))
            return JournalSnapshot(journals = listOf(JournalEntry(entry, date, listOf(first, archived))),
                strokes = listOf(stroke))
        }
        fun archive(snapshot: JournalSnapshot): ByteArray {
            val file = kotlin.io.path.createTempFile().toFile()
            try {
                BackupArchive.write(file, snapshot)
                return file.readBytes()
            } finally {
                file.delete()
            }
        }
        fun digest(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun HttpRequestBuilder.scoped(bearer: String = token, requested: SyncScope = scope) {
            bearerAuth(bearer)
            header("X-LifeApp-Dataset", requested.dataset.toString())
            header("X-LifeApp-Epoch", requested.epoch.toString())
        }
        suspend fun stage(id: Uuid, bytes: ByteArray, sha: String = digest(bytes)) =
            client.put("/v1/editable/staged/$id") {
                scoped()
                header("X-Content-SHA256", sha)
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }.status.value
        suspend fun commit(id: Uuid, bytes: ByteArray, vararg parents: Uuid, requireEmpty: Boolean = false) =
            client.post("/v1/editable/versions/$id/commit") {
                scoped()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("sha256", digest(bytes))
                    put("parents", JsonArray(parents.map { JsonPrimitive(it.toString()) }))
                    put("requireEmpty", requireEmpty)
                }.toString())
            }.status.value
        suspend fun list(path: String = "/v1/editable/versions") =
            client.get(path) { scoped() }.json().getValue("items").jsonArray.map { it.jsonObject }
        fun List<JsonObject>.heads(kind: String) = filter {
            it.getValue("current").jsonPrimitive.boolean && it.getValue("kind").jsonPrimitive.content == kind
        }.map { Uuid.parse(it.getValue("id").jsonPrimitive.content) }.toSet()

        val left = Uuid.random()
        val right = Uuid.random()
        val original = journal()
        val leftBytes = archive(original)
        val rightBytes = archive(journal())
        assertEquals(401, client.get("/v1/editable/versions").status.value)
        assertEquals(409, commit(left, leftBytes))
        assertEquals(400, stage(left, leftBytes, "0".repeat(64)))
        assertEquals(400, stage(left, byteArrayOf(42)))
        assertEquals(204, stage(left, leftBytes))
        assertEquals(404, client.get("/v1/editable/versions/$left") { scoped() }.status.value)
        assertTrue(list().isEmpty())
        assertEquals(204, commit(left, leftBytes, requireEmpty = true))
        assertEquals(204, commit(left, leftBytes, requireEmpty = true))
        assertEquals(409, commit(left, rightBytes))
        assertEquals(204, stage(right, rightBytes))
        assertEquals(409, commit(right, rightBytes, requireEmpty = true))
        assertEquals(setOf(left), list().heads("journal"))
        assertEquals(204, commit(right, rightBytes))
        assertEquals(setOf(left, right), list().heads("journal"))

        val resolved = Uuid.random()
        assertEquals(204, stage(resolved, leftBytes))
        assertEquals(204, commit(resolved, leftBytes, left, right))
        assertEquals(setOf(resolved), list().heads("journal"))
        val recovered = client.get("/v1/editable/versions/$left") { scoped() }
        assertEquals(digest(leftBytes), recovered.headers["X-Content-SHA256"])
        assertContentEquals(leftBytes, recovered.body<ByteArray>())
        assertEquals(409, client.get("/v1/editable/versions/$left") {
            scoped(requested = scope.copy(epoch = Uuid.random()))
        }.status.value)

        val changed = Uuid.random()
        val invalidInk = archive(original.copy(strokes = original.strokes.map { it.copy(width = 4f) }))
        assertEquals(204, stage(changed, invalidInk))
        assertEquals(409, commit(changed, invalidInk, resolved))
        assertEquals(setOf(resolved), list().heads("journal"))

        val book = Notebook(newRecordMetadata(), "Existing notebook")
        val fixtures = mapOf(
            "planner" to JournalSnapshot(planners = listOf(MonthlyPlanner(newRecordMetadata(), CalendarLayout(month)))),
            "habits" to JournalSnapshot(habits = listOf(MonthlyHabitLedger(newRecordMetadata(), month))),
            "notebook" to JournalSnapshot(notebooks = listOf(book),
                notebookPages = listOf(NotebookPage(newRecordMetadata(), book.metadata.id, 0))),
        )
        for ((kind, snapshot) in fixtures) {
            val id = Uuid.random()
            val bytes = archive(snapshot)
            assertEquals(204, stage(id, bytes), kind)
            assertEquals(204, commit(id, bytes), kind)
            assertEquals(setOf(id), list().heads(kind))
        }
        val first = list("/v1/editable/versions?limit=1").single()
        val cursor = first.getValue("sequence").jsonPrimitive.content
        assertEquals(5, list("/v1/editable/versions?after=$cursor").size)
        val competingPlanner = Uuid.random()
        val plannerBytes = archive(JournalSnapshot(planners = listOf(MonthlyPlanner(newRecordMetadata(), CalendarLayout(month)))))
        assertEquals(204, stage(competingPlanner, plannerBytes))
        assertEquals(204, commit(competingPlanner, plannerBytes))
        assertEquals(2, list().heads("planner").size)

        val other = "other-${Uuid.random()}"
        val otherScope = auth.initializeOwner(other, password.toCharArray(), requireEmpty = false)
        val otherToken = client.login(other).token()
        assertEquals(404, client.get("/v1/editable/versions/$left") { scoped(otherToken, otherScope) }.status.value)
        assertEquals(404, client.get("/v1/editable/versions/$left/preview") { scoped(otherToken, otherScope) }.status.value)

        database.transaction {
            execute("UPDATE editable_record_staging SET expires_at = now() WHERE dataset_id = ?", scope.dataset.sql())
        }
        EditableRecordStore(database).purgeExpiredUploads()
        assertEquals(409, commit(changed, invalidInk, resolved))
        assertContentEquals(leftBytes, client.get("/v1/editable/versions/$left") { scoped() }.body<ByteArray>())
    }

    @Test fun concurrentCommitsPreserveBothHeadsAndIdempotencyAcrossApplicationRestart() {
        lateinit var token: String
        val left = fixture.copy(id = Uuid.random(), deviceId = Uuid.random())
        val right = left.copy(id = Uuid.random(), deviceId = Uuid.random(), record = left.record.copy(title = "Other device"))
        testApplication {
            application { lifeApp(database, auth, origin) }
            token = client.login(user).token()
            val wrong = left.copy(parents = setOf(Uuid.random()))
            val missing = client.push(token, wrong)
            assertEquals(409, missing.status.value)
            assertEquals("MISSING_DEPENDENCY", missing.json()["code"]?.jsonPrimitive?.content)
            val commits = coroutineScope {
                val first = async { TodoServerJson.decodeAcknowledgment(client.push(token, left).bodyAsText()) }
                val second = async { TodoServerJson.decodeAcknowledgment(client.push(token, right).bodyAsText()) }
                listOf(first.await(), second.await())
            }
            assertEquals(setOf(1L, 2L), commits.map { it.commit.sequence }.toSet())
            assertEquals(409, client.push(token, left.copy(record = left.record.copy(title = "Different payload"))).status.value)
            val conflict = client.get("/v1/conflicts") { bearerAuth(token) }.json()
            assertEquals(2, conflict["items"]!!.jsonArray.single().jsonObject["heads"]!!.jsonArray.size)
        }
        testApplication {
            application { lifeApp(database, Authentication(database, clock), origin) }
            val receipt = client.push(token, left)
            receipt.record("Acknowledgment")
            val repeated = TodoServerJson.decodeAcknowledgment(receipt.bodyAsText())
            val response = client.pull(token, 0)
            response.record("SyncPage")
            val firstPage = TodoServerJson.decodePage(response.bodyAsText())
            assertEquals(2L, firstPage.advance(SyncCheckpoint(scope)).sequence)
            assertEquals(repeated.commit, firstPage.entries.single { it.operation == left })
            val resolution = left.copy(id = Uuid.random(), sequence = left.sequence + 1, parents = setOf(left.id, right.id))
            assertEquals(200, client.push(token, resolution, resolve = true).status.value)
            assertEquals(409, client.push(token, resolution.copy(id = Uuid.random(), sequence = left.sequence + 2),
                resolve = true).status.value)
            val tail = TodoServerJson.decodePage(client.pull(token, 2).bodyAsText())
            assertEquals(listOf(3L), tail.entries.map { it.sequence })
            assertEquals(410, client.pull(token, 1000).status.value)
        }
    }

    @Test fun deleteVersusEditAndAccountScopeAreEnforced() = testApplication {
        application { lifeApp(database, auth, origin) }
        val token = client.login(user).token()
        val initial = fixture
        assertEquals(200, client.push(token, initial).status.value)
        val deletion = initial.copy(id = Uuid.random(), sequence = initial.sequence + 1, parents = setOf(initial.id),
            record = initial.record.copy(deleted = true))
        assertEquals(200, client.push(token, deletion).status.value)
        val edit = initial.copy(id = Uuid.random(), deviceId = Uuid.random(), parents = setOf(initial.id),
            record = initial.record.copy(title = "Offline edit"))
        assertEquals(200, client.push(token, edit).status.value)
        assertEquals(404, client.get("/v1/todos/${initial.record.metadata.id}") { bearerAuth(token) }.status.value)
        assertEquals(409, client.push(token, edit.copy(id = Uuid.random(), sequence = edit.sequence + 1,
            parents = setOf(deletion.id))).status.value)
        val other = "other-${Uuid.random()}"
        auth.initializeOwner(other, password.toCharArray(), requireEmpty = false)
        val otherToken = client.login(other).token()
        assertEquals(404, client.get("/v1/todos/${initial.record.metadata.id}") { bearerAuth(otherToken) }.status.value)
        assertEquals(409, client.push(otherToken, initial).status.value)
        assertEquals(409, client.pull(otherToken, 0).status.value)
    }

    @Test fun malformedOversizedAndFailedLoginsReturnStructuredErrors() = testApplication {
        application { lifeApp(database, auth, origin) }
        val token = client.login(user).token()
        for ((body, expected) in listOf("{" to 400, " ".repeat(16_385) to 413, "[]" to 400)) {
            val result = client.post("/v1/sync/push") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(expected, result.status.value)
            assertEquals(result.headers["X-Request-ID"], result.json().getValue("requestId").jsonPrimitive.content)
        }
        repeat(4) { assertEquals(401, client.login(user, secret = "bad").status.value) }
        val limited = client.login(user, secret = "bad")
        assertEquals(429, limited.status.value)
        limited.record("Error")
        assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        assertEquals(401, client.login("' OR 1=1 --").status.value)
    }

    @Test fun failedCounterUpdateRollsBackOperationAndEpochChangesRejectOldCursors() = testApplication {
        application { lifeApp(database, auth, origin) }
        val token = client.login(user).token()
        database.transaction {
            // Fixture UUID is generated by the test server. The constraint fails after the operation insert.
            execute("ALTER TABLE recovery_epochs ADD CONSTRAINT test_counter_rollback " +
                "CHECK (id <> '${scope.epoch}'::uuid OR last_commit <> 1) NOT VALID")
        }
        try {
            assertEquals(503, client.push(token, fixture).status.value)
            assertTrue(TodoServerJson.decodePage(client.pull(token, 0).bodyAsText()).entries.isEmpty())
            assertEquals(0, database.transaction {
                query("SELECT count(*) FROM devices WHERE dataset_id = ?", scope.dataset.sql()) { it.getInt(1) }.single()
            })
        } finally {
            database.transaction { execute("ALTER TABLE recovery_epochs DROP CONSTRAINT test_counter_rollback") }
        }
        assertEquals(1L, TodoServerJson.decodeAcknowledgment(client.push(token, fixture).bodyAsText()).commit.sequence)
        database.transaction {
            execute("UPDATE recovery_epochs SET active = false WHERE dataset_id = ? AND id = ?", scope.dataset.sql(), scope.epoch.sql())
            execute("INSERT INTO recovery_epochs(dataset_id, id) VALUES (?, ?)", scope.dataset.sql(), Uuid.random().sql())
        }
        assertEquals(409, client.pull(token, 0).status.value)
        assertEquals(409, client.push(token, fixture).status.value)
    }

    @Test fun readsStoredWorkoutAndRejectsAccessFromAnotherAccount() = testApplication {
        application { lifeApp(database, auth, origin) }
        val token = client.login(user).token()
        val workout = Json.parseToJsonElement(File("../../contracts/fixtures/workout.json").readText()).jsonObject
        val metadata = workout["metadata"]!!.jsonObject
        val id = Uuid.parse(metadata["id"]!!.jsonPrimitive.content)
        val entry = workout["entries"]!!.jsonArray.single().jsonObject
        val entryId = Uuid.parse(entry["metadata"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val exerciseId = Uuid.parse(entry["exerciseId"]!!.jsonPrimitive.content)
        val setId = Uuid.parse(entry["sets"]!!.jsonArray.single().jsonObject["metadata"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val created = metadata["createdAt"]!!.jsonPrimitive.content
        val updated = metadata["updatedAt"]!!.jsonPrimitive.content
        database.transaction {
            val dataset = scope.dataset.sql()
            execute("INSERT INTO exercises VALUES (?, ?, ?, ?, 1, 'Squat', NULL, NULL, true)",
                dataset, exerciseId.sql(), created, updated)
            execute("INSERT INTO workout_sessions VALUES (?, ?, ?, ?, 1, DATE '2026-11-09', ?, ?, 1500, 110, NULL, true)",
                dataset, id.sql(), created, updated, created, updated)
            execute("INSERT INTO workout_exercise_entries VALUES (?, ?, ?, ?, 1, ?, ?, 0, 1)",
                dataset, entryId.sql(), created, updated, id.sql(), exerciseId.sql())
            execute("INSERT INTO recorded_sets VALUES (?, ?, ?, ?, 1, ?, 1, 20, 'KG', 8, NULL, 60, true)",
                dataset, setId.sql(), created, updated, entryId.sql())
        }
        val response = client.get("/v1/workouts/$id") { bearerAuth(token) }
        assertEquals(200, response.status.value)
        response.record("Workout")
        val received = response.json()
        assertEquals(workout["metadata"], received["metadata"])
        assertEquals(workout["date"], received["date"])
        val savedSet = received["entries"]!!.jsonArray.single().jsonObject["sets"]!!.jsonArray.single().jsonObject
        assertEquals("KG", savedSet["weight"]!!.jsonObject["unit"]!!.jsonPrimitive.content)
        assertEquals(8, savedSet["repetitions"]!!.jsonPrimitive.int)
        assertEquals(1, client.get("/v1/workouts") { bearerAuth(token) }.json()["items"]!!.jsonArray.size)
        assertTrue(client.get("/v1/exercises") { bearerAuth(token) }.json()["items"]!!.jsonArray
            .single().jsonObject["archived"]!!.jsonPrimitive.boolean)
        val other = "workout-other-${Uuid.random()}"
        auth.initializeOwner(other, password.toCharArray(), requireEmpty = false)
        val otherToken = client.login(other).token()
        assertEquals(404, client.get("/v1/workouts/$id") { bearerAuth(otherToken) }.status.value)
    }

    private suspend fun HttpResponse.record(name: String) {
        File("build/contract-responses").apply { mkdirs() }.resolve("$name.json").writeText(bodyAsText())
    }

    private suspend fun HttpClient.login(name: String, browser: Boolean = false, suppliedOrigin: String? = null,
        secret: String = password, secondFactor: String? = null) = post("/v1/session") {
        contentType(ContentType.Application.Json)
        suppliedOrigin?.let { header(HttpHeaders.Origin, it) }
        setBody(buildJsonObject {
            put("username", name); put("password", secret); put("client", if (browser) "browser" else "android")
            secondFactor?.let { put("secondFactor", it) }
        }.toString())
    }

    private suspend fun HttpClient.push(token: String, operation: TodoOperation, resolve: Boolean = false) =
        post(if (resolve) "/v1/conflicts/${operation.record.metadata.id}/resolve" else "/v1/sync/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("scope", buildJsonObject { put("dataset", scope.dataset.toString()); put("epoch", scope.epoch.toString()) })
                put("operation", Json.parseToJsonElement(TodoOperationJson.encode(operation)))
            }.toString())
        }

    private suspend fun HttpClient.pull(token: String, after: Long) = get("/v1/sync/pull") {
        bearerAuth(token)
        parameter("epoch", scope.epoch)
        parameter("after", after)
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.token() = json().getValue("accessToken").jsonPrimitive.content

    private class TestClock : Clock() {
        @Volatile var now: Instant = Instant.parse("2026-11-09T10:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
