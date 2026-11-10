package com.myjournal.server

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object OwnerTotp {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val codePattern = Regex("[0-9]{6}")

    fun load(path: Path): ByteArray {
        val base32 = Files.readString(path).trim()
        require(base32.length == 32 && base32.all { it in alphabet }) { "Expected a 160-bit Base32 authenticator key." }
        return ByteArray(20) { byte ->
            var value = 0
            for (offset in 0..7) {
                val bit = byte * 8 + offset
                value = (value shl 1) or ((alphabet.indexOf(base32[bit / 5]) shr (4 - bit % 5)) and 1)
            }
            value.toByte()
        }
    }

    fun matchingStep(key: ByteArray, code: String, now: Instant, previous: Long): Long? {
        if (!codePattern.matches(code)) return null
        val step = now.epochSecond / 30
        return (step + 1 downTo maxOf(0, step - 1)).firstOrNull {
            it > previous && MessageDigest.isEqual(code.encodeToByteArray(), generate(key, it).encodeToByteArray())
        }
    }

    internal fun generate(key: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = digest.last().toInt() and 15
        val value = ByteBuffer.wrap(digest, offset, 4).int and Int.MAX_VALUE
        return (value % 1_000_000).toString().padStart(6, '0')
    }
}
