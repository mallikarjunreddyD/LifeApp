package com.myjournal.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Version 1 uses PBKDF2-HMAC-SHA256 and AES-256-GCM with an authenticated header. */
object CloudBackupEnvelope {
    const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    private val magic = "LIFEBAK1".encodeToByteArray()
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val HEADER_SIZE = 8 + SALT_SIZE + NONCE_SIZE
    private const val TAG_SIZE = 16
    const val MAX_ENVELOPE_BYTES = MAX_ARCHIVE_BYTES + HEADER_SIZE + TAG_SIZE
    private val random = SecureRandom()

    fun seal(archive: ByteArray, passphrase: CharArray): ByteArray {
        require(archive.size in 1..MAX_ARCHIVE_BYTES) { "Cloud backup exceeds the 16 MiB limit" }
        val header = magic + ByteArray(SALT_SIZE + NONCE_SIZE).also(random::nextBytes)
        return header + cipher(Cipher.ENCRYPT_MODE, header, passphrase).doFinal(archive)
    }

    fun open(envelope: ByteArray, passphrase: CharArray): ByteArray {
        require(envelope.size in (HEADER_SIZE + TAG_SIZE + 1)..MAX_ENVELOPE_BYTES) { "Invalid encrypted backup size" }
        val header = envelope.copyOfRange(0, HEADER_SIZE)
        require(header.copyOfRange(0, magic.size).contentEquals(magic)) { "Unknown encrypted backup format" }
        return cipher(Cipher.DECRYPT_MODE, header, passphrase)
            .doFinal(envelope, HEADER_SIZE, envelope.size - HEADER_SIZE)
    }

    private fun cipher(mode: Int, header: ByteArray, passphrase: CharArray): Cipher {
        require(passphrase.size in 12..1024) { "Use a passphrase of 12 to 1024 characters" }
        val salt = header.copyOfRange(magic.size, magic.size + SALT_SIZE)
        val nonce = header.copyOfRange(magic.size + SALT_SIZE, HEADER_SIZE)
        val password = PBEKeySpec(passphrase, salt, 600_000, 256)
        val material = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(password).encoded
        } finally {
            password.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(material, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
            }
        } finally {
            material.fill(0)
        }
    }
}
