package com.myjournal.android

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalTime

@Composable
fun TodoReminderSettings() {
    val context = LocalContext.current
    val scheduler = remember(context) { TodoReminderScheduler(context.applicationContext) }
    var settings by remember { mutableStateOf(scheduler.read()) }
    var permitted by remember { mutableStateOf(scheduler.permitted()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = scheduler.permitted()
    }
    fun choose(current: LocalTime, update: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, hour, minute -> update(LocalTime(hour, minute)) },
            current.hour, current.minute, DateFormat.is24HourFormat(context)).show()
    }
    Text("To-do reminders", style = MaterialTheme.typography.titleLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(settings.enabled, onCheckedChange = { enabled ->
            settings = settings.copy(enabled = enabled)
            scheduler.save(settings)
        })
        Text("Enable reminders", Modifier.padding(start = 12.dp))
    }
    TextButton(onClick = {
        choose(settings.morning) { settings = settings.copy(morning = it); scheduler.save(settings) }
    }) { Text("Morning: ${settings.morning}") }
    TextButton(onClick = {
        choose(settings.evening) { settings = settings.copy(evening = it); scheduler.save(settings) }
    }) { Text("Evening: ${settings.evening}") }
    Text(if (permitted) "Notifications allowed" else "Notifications blocked")
    if (!permitted && Build.VERSION.SDK_INT >= 33) TextButton(onClick = {
        request.launch(Manifest.permission.POST_NOTIFICATIONS)
    }) { Text("Allow notifications") }
    Text("Reminders use this device’s time zone. Android may delay delivery.")
}
