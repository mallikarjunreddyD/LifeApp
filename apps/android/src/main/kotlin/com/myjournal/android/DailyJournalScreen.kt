package com.myjournal.android

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.myjournal.domain.BrushTool
import kotlinx.datetime.*
import java.util.GregorianCalendar

enum class EditorTool { PEN, HIGHLIGHTER, ERASER }

@Composable
fun DailyJournalScreen(model: JournalViewModel) {
    val state by model.editor.state.collectAsState()
    val context = LocalContext.current
    var toolName by rememberSaveable { mutableStateOf(EditorTool.PEN.name) }
    var color by rememberSaveable { mutableLongStateOf(0xFF252A30L) }
    var penWidth by rememberSaveable { mutableFloatStateOf(2.8f) }
    var markerWidth by rememberSaveable { mutableFloatStateOf(18f) }
    val tool = EditorTool.valueOf(toolName)
    var canvas by remember { mutableStateOf<JournalCanvas?>(null) }
    fun open(date: LocalDate) { canvas?.finishInProgress(); model.editor.open(date) }
    val date = state.date
    val page = state.page
    val pageIndex = state.entry?.pages?.indexOfFirst { it.metadata.id == state.pageId } ?: 0
    DisposableEffect(Unit) { onDispose { canvas?.finishInProgress() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { open(date.minus(1, DateTimeUnit.DAY)) }) { Text("Previous day") }
            TextButton(onClick = { open(today()) }) { Text("Today") }
            TextButton(onClick = { open(date.plus(1, DateTimeUnit.DAY)) }) { Text("Next day") }
        }
        TextButton(onClick = {
            canvas?.finishInProgress()
            DatePickerDialog(context, { _, year, month, day -> open(LocalDate(year, month + 1, day)) },
                date.year, date.month.ordinal, date.day).apply {
                datePicker.minDate = GregorianCalendar(1, 0, 1).timeInMillis
                datePicker.maxDate = GregorianCalendar(9999, 11, 31).timeInMillis
            }.show()
        }) {
            Text("${date.day.toString().padStart(2, '0')} — ${date.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }}, " +
                "${date.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${date.year}",
                style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorTool.entries.forEach { item ->
                FilterChip(selected = item == tool, onClick = { canvas?.finishInProgress(); toolName = item.name },
                    label = { Text(when (item) { EditorTool.PEN -> "Pen"; EditorTool.HIGHLIGHTER -> "Highlighter"; EditorTool.ERASER -> "Eraser" }) })
            }
            TextButton(enabled = state.canUndo, onClick = { canvas?.finishInProgress(); model.editor.undo() }) { Text("Undo") }
            TextButton(enabled = state.canRedo, onClick = { canvas?.finishInProgress(); model.editor.redo() }) { Text("Redo") }
            listOf("Black" to 0xFF252A30L, "Blue" to 0xFF2859A8L, "Red" to 0xFFB43F3FL, "Green" to 0xFF3F704CL).forEach { (name, argb) ->
                FilterChip(selected = color == argb, onClick = { canvas?.finishInProgress(); color = argb }, label = { Text(name) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (tool == EditorTool.ERASER) "Whole-stroke eraser" else "Thickness", Modifier.padding(top = 16.dp))
            if (tool != EditorTool.ERASER) Slider(
                value = if (tool == EditorTool.PEN) penWidth else markerWidth,
                onValueChange = { if (tool == EditorTool.PEN) penWidth = it else markerWidth = it },
                valueRange = if (tool == EditorTool.PEN) 1f..8f else 8f..40f, modifier = Modifier.width(144.dp))
            TextButton(onClick = { canvas?.resetViewport() }) { Text("Fit width") }
            TextButton(enabled = pageIndex > 0 && !state.loading, onClick = {
                canvas?.finishInProgress(); model.editor.selectPage(state.entry!!.pages[pageIndex - 1].metadata.id)
            }) { Text("Previous page") }
            Text("Page ${pageIndex + 1} of ${state.entry?.pages?.size ?: 1}", Modifier.padding(top = 16.dp))
            TextButton(enabled = pageIndex + 1 < (state.entry?.pages?.size ?: 0) && !state.loading, onClick = {
                canvas?.finishInProgress(); model.editor.selectPage(state.entry!!.pages[pageIndex + 1].metadata.id)
            }) { Text("Next page") }
            TextButton(enabled = page != null && !state.loading, onClick = {
                canvas?.finishInProgress(); model.editor.addPage()
            }) { Text("Add page") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                state.loading -> "Loading journal…"
                state.error != null -> state.error!!
                state.pending > 0 -> "Saving ${state.pending} change(s)…"
                state.strokes.isEmpty() && state.entry?.metadata?.revision == 1L -> "Blank page · Ready for your stylus"
                else -> "All changes saved locally"
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (state.error != null) TextButton(onClick = { model.editor.retry() }) { Text("Retry") }
        }
        if (page != null && !state.loading) {
            key(page.metadata.id) {
                AndroidView(factory = { ctx -> JournalCanvas(ctx).also { canvas = it } },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    update = { view ->
                        canvas = view
                        view.bind(page, state.strokes, tool, color,
                            if (tool == EditorTool.PEN) penWidth else markerWidth,
                            model.viewport(page.metadata.id),
                            onViewport = { model.rememberViewport(page.metadata.id, it) },
                            onStroke = { brush, argb, width, points ->
                                model.editor.addStroke(brush, argb, width, points, date, page.metadata.id)
                            },
                            onErase = { model.editor.erase(it, date, page.metadata.id) })
                    })
            }
        } else Spacer(Modifier.weight(1f))
        Text("Stylus writes · Finger pans · Pinch to zoom", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 4.dp))
    }
}
