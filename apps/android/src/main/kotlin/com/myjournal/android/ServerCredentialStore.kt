package com.myjournal.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.myjournal.domain.ServerEndpoint
import com.myjournal.domain.SyncScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Only the short-lived bearer token is retained. No password or token enters a backup or WorkManager input. */
internal class ServerCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("lifeapp-server", Context.MODE_PRIVATE)
    private val alias = "lifeapp-server-token-v1"

    suspend fun save(credential: ServerCredential) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(alias.encodeToByteArray())
        val plaintext = buildJsonObject {
            put("origin", credential.endpoint.origin)
            put("username", credential.username)
            put("token", credential.token)
            put("dataset", credential.session.scope.dataset.toString())
            put("epoch", credential.session.scope.epoch.toString())
            put("expires", credential.session.expiresAt.toString())
        }.toString().encodeToByteArray()
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        check(cipher.iv.size == 12)
        check(prefs.edit().putString("session", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
            .putString("origin", credential.endpoint.origin).remove("retry-at").commit()) { "Could not store server sign-in" }
    }

    suspend fun load(): ServerCredential? = withContext(Dispatchers.IO) {
        val saved = prefs.getString("session", null) ?: return@withContext null
        val bytes = Base64.decode(saved, Base64.NO_WRAP)
        require(bytes.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(alias.encodeToByteArray())
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        try {
            val json = Json.parseToJsonElement(plain.decodeToString()).jsonObject
            fun field(name: String) = json.getValue(name).jsonPrimitive.content
            ServerCredential(
                ServerEndpoint.parse(field("origin")), field("username"), field("token"),
                ServerSession(SyncScope(Uuid.parse(field("dataset")), Uuid.parse(field("epoch"))), Instant.parse(field("expires"))),
            )
        } finally {
            plain.fill(0)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove("session").remove("retry-at").commit()) { "Could not remove server sign-in" }
    }

    fun origin() = prefs.getString("origin", null)
    fun retryAt() = prefs.getLong("retry-at", 0)
    suspend fun saveRetryAt(value: Long) = withContext(Dispatchers.IO) {
        check(prefs.edit().putLong("retry-at", value).commit())
    }
    fun lastVerified() = prefs.getString("last-verified", null)
    suspend fun saveLastVerified(value: String) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString("last-verified", value).commit())
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "The saved sign-in key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}
