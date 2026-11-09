package com.myjournal.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.myjournal.data.CloudBackupEnvelope
import com.myjournal.domain.TodoReminderPreferences
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

class TodoAlarmTest {
    @Test fun reschedulingReplacesTheSlotAndIgnoresDuplicateDelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("todo-reminders", Context.MODE_PRIVATE)
        var now = Instant.parse("2100-01-01T08:00:00Z")
        val scheduler = TodoReminderScheduler(context, object : Clock { override fun now() = now }) { TimeZone.UTC }
        val original = scheduler.read()
        try {
            scheduler.save(TodoReminderPreferences(enabled = true))
            val oldMorning = prefs.getLong("scheduled-0", 0)
            assertTrue(oldMorning > 0)
            assertTrue(prefs.getLong("scheduled-1", 0) > oldMorning)
            scheduler.save(scheduler.read().copy(morning = LocalTime(10, 0)))
            val morning = prefs.getLong("scheduled-0", 0)
            assertTrue(morning > oldMorning)
            now = Instant.fromEpochMilliseconds(morning)
            assertFalse(scheduler.consume(0, oldMorning))
            assertTrue(scheduler.consume(0, morning))
            assertFalse(scheduler.consume(0, morning))
            scheduler.save(scheduler.read().copy(morning = LocalTime(19, 0)))
            assertFalse(prefs.contains("scheduled-1"))
            scheduler.save(scheduler.read().copy(enabled = false))
            assertFalse(prefs.contains("scheduled-0"))
            assertFalse(scheduler.consume(0, morning))
        } finally {
            TodoReminderScheduler(context).save(original)
        }
    }

    @Test fun androidCryptoProviderCanEncryptAndDecryptPortableBackups() {
        val data = "Encrypted on Android".encodeToByteArray()
        val password = "portable phrase 植物".toCharArray()
        assertArrayEquals(data, CloudBackupEnvelope.open(CloudBackupEnvelope.seal(data, password), password))
    }
}
