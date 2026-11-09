package com.myjournal.data

import javax.crypto.AEADBadTagException
import kotlin.test.*

class CloudBackupEnvelopeTest {
    @Test fun authenticatedBackupsUseRandomnessAndRejectChangedContentsAndWrongPassphrases() {
        val secret = "correct horse battery staple".toCharArray()
        val source = "A personal archive containing editable vectors".encodeToByteArray()
        val first = CloudBackupEnvelope.seal(source, secret)
        val second = CloudBackupEnvelope.seal(source, secret)
        assertFalse(first.contentEquals(second))
        assertContentEquals(source, CloudBackupEnvelope.open(first, secret))
        assertContentEquals(source, CloudBackupEnvelope.open(second, secret))
        assertFailsWith<AEADBadTagException> {
            CloudBackupEnvelope.open(first, "another different passphrase".toCharArray())
        }
        for (offset in listOf(8, 24, 37, first.lastIndex)) {
            val changed = first.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFailsWith<AEADBadTagException> { CloudBackupEnvelope.open(changed, secret) }
        }
        assertFailsWith<IllegalArgumentException> { CloudBackupEnvelope.open(first.copyOf(20), secret) }
        assertFailsWith<IllegalArgumentException> { CloudBackupEnvelope.seal(source, charArrayOf('x')) }
    }
}
