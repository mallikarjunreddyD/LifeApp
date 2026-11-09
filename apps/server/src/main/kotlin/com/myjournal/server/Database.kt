package com.myjournal.server

import com.myjournal.domain.SyncScope
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.uuid.Uuid
import java.util.UUID

internal class Database(private val source: DataSource) {
    private val flyway = Flyway.configure().dataSource(source).cleanDisabled(true).load()

    fun migrate() { flyway.migrate() }

    fun ready(): Boolean = flyway.validateWithResult().validationSuccessful &&
        flyway.info().pending().isEmpty() &&
        source.connection.use { it.query("SELECT 1 FROM recovery_epochs LIMIT 1") { true }; true }

    fun <T> transaction(block: Connection.() -> T): T = source.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.block().also { connection.commit() }
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }
}

internal fun Connection.execute(sql: String, vararg values: Any?): Int =
    prepareStatement(sql).use { statement ->
        statement.queryTimeout = 10
        values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
        statement.executeUpdate()
    }

internal fun <T> Connection.query(sql: String, vararg values: Any?, read: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.queryTimeout = 10
        values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }

internal fun Uuid.sql() = UUID.fromString(toString())
internal fun ResultSet.uuid(column: Int) = Uuid.parse(getString(column))

internal class ApiFailure(val status: Int, val code: String, override val message: String) : RuntimeException(message)
internal fun conflict(code: String, message: String): Nothing = throw ApiFailure(409, code, message)

internal fun Connection.lockEpoch(scope: SyncScope, write: Boolean = false): Long {
    val mode = if (write) "UPDATE" else "SHARE"
    return query("SELECT last_commit FROM recovery_epochs WHERE dataset_id = ? AND id = ? AND active FOR $mode",
        scope.dataset.sql(), scope.epoch.sql()) { it.getLong(1) }.singleOrNull()
        ?: conflict("SCOPE_MISMATCH", "The dataset or recovery epoch changed.")
}
