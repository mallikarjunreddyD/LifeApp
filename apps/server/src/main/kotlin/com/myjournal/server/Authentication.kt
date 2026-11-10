package com.myjournal.server

import com.myjournal.domain.SyncScope
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.uuid.Uuid

private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

internal object Credentials {
    private const val HASH_PREFIX = "pbkdf2-sha256:600000:"
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun token() = encoder.encodeToString(bytes(32))
    fun tokenHash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())

    fun passwordHash(password: CharArray): String {
        val salt = bytes(16)
        return HASH_PREFIX + encoder.encodeToString(salt) + "." + encoder.encodeToString(derive(password, salt))
    }

    fun matches(password: CharArray, stored: String): Boolean {
        if (!stored.startsWith(HASH_PREFIX)) return false
        val parts = stored.removePrefix(HASH_PREFIX).split('.')
        if (parts.size != 2) return false
        return MessageDigest.isEqual(derive(password, decoder.decode(parts[0])), decoder.decode(parts[1]))
    }

    private fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

internal data class Session(
    val scope: SyncScope,
    val expiresAt: Instant,
    val csrfToken: String?,
)

internal class Authentication(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val totpPath: Path? = null,
) {
    private data class Account(val id: Uuid, val password: String)

    private val dummy = Credentials.passwordHash(Credentials.token().toCharArray())
    private val limiter = LoginLimiter(clock)

    fun initializeOwner(username: String, password: CharArray, requireEmpty: Boolean = true): SyncScope {
        val user = normalize(username)
        require(usernamePattern.matches(user) && password.size in 12..1024) { "Invalid owner name or password length." }
        val passwordHash = Credentials.passwordHash(password)
        return database.transaction {
            execute("LOCK TABLE accounts IN SHARE ROW EXCLUSIVE MODE")
            check(!requireEmpty || query("SELECT 1 FROM accounts LIMIT 1") { true }.isEmpty()) {
                "An owner already exists."
            }
            val owner = Uuid.random()
            val scope = SyncScope(Uuid.random(), Uuid.random())
            execute("INSERT INTO accounts (id, username, password_hash) VALUES (?, ?, ?)", owner.sql(), user, passwordHash)
            execute("INSERT INTO datasets VALUES (?, ?)", scope.dataset.sql(), owner.sql())
            execute("INSERT INTO recovery_epochs(dataset_id, id) VALUES (?, ?)", scope.dataset.sql(), scope.epoch.sql())
            scope
        }
    }

    fun enrollOwner(currentUsername: String, username: String, code: String, rotate: Boolean = false): List<String> {
        val user = normalize(username)
        require(usernamePattern.matches(user)) { "Invalid owner username." }
        val step = OwnerTotp.matchingStep(totpKey(), code, clock.instant(), -1)
            ?: error("The authenticator code did not match.")
        val recovery = List(8) { Credentials.token() }
        database.transaction {
            val (owner, enrolled) = query(
                "SELECT id, last_totp_step IS NOT NULL FROM accounts WHERE username = ? FOR UPDATE",
                normalize(currentUsername),
            ) { it.uuid(1) to it.getBoolean(2) }.single()
            check(enrolled == rotate) { "Use the enrollment command for a new authenticator or rotation for an existing one." }
            execute("UPDATE accounts SET username = ?, last_totp_step = ? WHERE id = ?", user, step, owner.sql())
            execute("DELETE FROM sessions WHERE owner_id = ?", owner.sql())
            execute("DELETE FROM owner_recovery_codes WHERE owner_id = ?", owner.sql())
            recovery.forEach {
                execute("INSERT INTO owner_recovery_codes VALUES (?, ?)", owner.sql(), Credentials.tokenHash(it))
            }
        }
        return recovery
    }

    fun login(username: String, password: CharArray, browser: Boolean, secondFactor: String? = null): Pair<String, Session> {
        val user = normalize(username)
        limiter.attempt(user)
        val account = database.transaction {
            query("SELECT id, password_hash FROM accounts WHERE username = ?", user) {
                Account(it.uuid(1), it.getString(2))
            }.singleOrNull()
        }
        if (!Credentials.matches(password, account?.password ?: dummy) || account == null) unauthorized()
        val token = Credentials.token()
        val csrf = if (browser) Credentials.token() else null
        val now = clock.instant()
        val expiry = now.plusSeconds(if (browser) 43_200 else 3_600)
        database.transaction {
            val previous = query("SELECT coalesce(last_totp_step, -1) FROM accounts WHERE id = ? AND username = ? FOR UPDATE",
                account.id.sql(), user) { it.getLong(1) }
                .singleOrNull() ?: unauthorized()
            if (previous >= 0) {
                val factor = secondFactor ?: unauthorized()
                val step = if (factor.length == 6) OwnerTotp.matchingStep(totpKey(), factor, now, previous) else null
                if (step != null) {
                    execute("UPDATE accounts SET last_totp_step = ? WHERE id = ?", step, account.id.sql())
                } else if (!tokenPattern.matches(factor) || execute(
                        "DELETE FROM owner_recovery_codes WHERE owner_id = ? AND code_hash = ?",
                        account.id.sql(), Credentials.tokenHash(factor),
                    ) != 1) unauthorized()
            }
            execute("DELETE FROM sessions WHERE expires_at <= ?", now.utc())
            execute(
                "INSERT INTO sessions (token_hash, owner_id, expires_at, csrf_token, factor_verified) VALUES (?, ?, ?, ?, ?)",
                Credentials.tokenHash(token), account.id.sql(), expiry.utc(), csrf, previous >= 0,
            )
        }
        return token to session(token, browser)
    }

    private fun totpKey() = OwnerTotp.load(requireNotNull(totpPath) { "Owner authenticator file is unconfigured." })

    fun session(token: String, browser: Boolean): Session {
        if (!tokenPattern.matches(token)) unauthorized()
        return database.transaction {
            query(
                """SELECT d.id, e.id, s.expires_at, s.csrf_token FROM sessions s
                    JOIN datasets d ON d.owner_id = s.owner_id
                    JOIN recovery_epochs e ON e.dataset_id = d.id AND e.active
                    WHERE s.token_hash = ? AND s.expires_at > ? AND (s.csrf_token IS NOT NULL) = ?""",
                Credentials.tokenHash(token), clock.instant().utc(), browser,
            ) { Session(SyncScope(it.uuid(1), it.uuid(2)), it.getObject(3, OffsetDateTime::class.java).toInstant(), it.getString(4)) }
                .singleOrNull() ?: unauthorized()
        }
    }

    fun revoke(token: String) {
        database.transaction { execute("DELETE FROM sessions WHERE token_hash = ?", Credentials.tokenHash(token)) }
    }

    private fun normalize(username: String) = username.lowercase(Locale.ROOT)
    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    companion object {
        private val usernamePattern = Regex("[a-z0-9@._+-]{1,254}")
    }
}

internal fun unauthorized(): Nothing = throw ApiFailure(401, "UNAUTHENTICATED", "Sign-in failed or the session expired.")

/** A bounded process-local throttle for one server replica. Does not trust forwarded IPs. */
private class LoginLimiter(private val clock: Clock) {
    private var window = Long.MIN_VALUE
    private var total = 0
    private val attempts = mutableMapOf<String, Int>()

    @Synchronized
    fun attempt(user: String) {
        val minute = clock.instant().epochSecond / 60
        if (minute != window) {
            window = minute
            total = 0
            attempts.clear()
        }
        val count = attempts.getOrDefault(user, 0)
        if (total >= 30 || count >= 5) throw ApiFailure(429, "RATE_LIMITED", "Wait before trying again.")
        attempts[user] = count + 1
        total++
    }
}
