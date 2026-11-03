package com.myjournal.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myjournal.domain.*
import java.text.DateFormatSymbols

fun JournalMonth.title() = "${DateFormatSymbols().months[month - 1]} $year"

@Composable
fun MonthControls(month: JournalMonth, onMonth: (JournalMonth) -> Unit, beforeChange: () -> Unit = {}) {
    var picker by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TextButton(enabled = month != JournalMonth(1, 1), onClick = { beforeChange(); onMonth(month.previous()) }) { Text("Previous month") }
        TextButton(onClick = { beforeChange(); picker = true }) { Text(month.title(), style = MaterialTheme.typography.titleLarge) }
        TextButton(onClick = { beforeChange(); onMonth(JournalMonth.from(today())) }) { Text("Current month") }
        TextButton(enabled = month != JournalMonth(9999, 12), onClick = { beforeChange(); onMonth(month.next()) }) { Text("Next month") }
    }
    if (picker) {
        var year by remember { mutableStateOf(month.year.toString()) }
        var number by remember { mutableStateOf(month.month.toString()) }
        val valid = year.toIntOrNull()?.let { it in 1..9999 } == true && number.toIntOrNull()?.let { it in 1..12 } == true
        AlertDialog(onDismissRequest = { picker = false }, title = { Text("Choose month and year") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text("Month (1–12)") }, singleLine = true)
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = { onMonth(JournalMonth(year.toInt(), number.toInt())); picker = false }) { Text("Open month") } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Cancel") } })
    }
}

@Composable
fun SharedInkTools(tool: EditorTool, color: Long, onTool: (EditorTool) -> Unit, onColor: (Long) -> Unit,
    canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EditorTool.entries.forEach { item -> FilterChip(selected = item == tool, onClick = { onTool(item) }, label = {
            Text(when (item) { EditorTool.PEN -> "Pen"; EditorTool.HIGHLIGHTER -> "Highlighter"; EditorTool.ERASER -> "Eraser" }) }) }
        TextButton(enabled = canUndo, onClick = undo) { Text("Undo") }; TextButton(enabled = canRedo, onClick = redo) { Text("Redo") }
        listOf("Black" to 0xFF252A30L, "Blue" to 0xFF2859A8L, "Red" to 0xFFB43F3FL, "Green" to 0xFF3F704CL).forEach { (name, argb) ->
            FilterChip(selected = color == argb, onClick = { onColor(argb) }, label = { Text(name) })
        }
    }
}
