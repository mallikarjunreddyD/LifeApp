package com.myjournal.android

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.datetime.*
import java.util.GregorianCalendar
import kotlin.uuid.Uuid

enum class EditorTool { PEN, HIGHLIGHTER, ERASER }

@Composable
fun DailyJournalScreen(model: JournalViewModel) {
    val state by model.editor.state.collectAsState()
    val context = LocalContext.current
    var toolName by rememberSaveable { mutableStateOf(EditorTool.PEN.name) }
    var color by rememberSaveable { mutableLongStateOf(0xFF252A30L) }
    var penWidth by rememberSaveable { mutableFloatStateOf(2.8f) }
    var markerWidth by rememberSaveable { mutableFloatStateOf(18f) }
    var deleting by remember { mutableStateOf<Uuid?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val tool = EditorTool.valueOf(toolName)
    var canvas by remember { mutableStateOf<JournalCanvas?>(null) }
    fun open(date: LocalDate) { canvas?.finishInProgress(); model.changeJournalDate(date) }
    val date = state.date
    val page = state.page
    val pages = state.activePages
    val pageIndex = pages.indexOfFirst { it.metadata.id == state.pageId }.coerceAtLeast(0)
    DisposableEffect(Unit) { onDispose { canvas?.finishInProgress() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { open(date.minus(1, DateTimeUnit.DAY)) }) { Text("Previous day") }
            TextButton(onClick = { open(today()) }) { Text("Today") }
            TextButton(onClick = { open(date.plus(1, DateTimeUnit.DAY)) }) { Text("Next day") }
            TextButton(onClick = {
                canvas?.finishInProgress()
                DatePickerDialog(context, { _, year, month, day -> open(LocalDate(year, month + 1, day)) },
                    date.year, date.month.ordinal, date.day).apply {
                    datePicker.minDate = GregorianCalendar(1, 0, 1).timeInMillis
                    datePicker.maxDate = GregorianCalendar(9999, 11, 31).timeInMillis
                }.show()
            }) {
                Text("${date.day.toString().padStart(2, '0')} — ${date.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }}, " +
                    "${date.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${date.year}", style = MaterialTheme.typography.titleMedium)
            }
        }
        SharedInkTools(tool, color, { canvas?.finishInProgress(); toolName = it.name }, { canvas?.finishInProgress(); color = it },
            state.canUndo, state.canRedo, { canvas?.finishInProgress(); model.editor.undo() }, { canvas?.finishInProgress(); model.editor.redo() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (tool == EditorTool.ERASER) "Whole-stroke eraser" else "Thickness", Modifier.padding(top = 16.dp))
            if (tool != EditorTool.ERASER) Slider(value = if (tool == EditorTool.PEN) penWidth else markerWidth,
                onValueChange = { if (tool == EditorTool.PEN) penWidth = it else markerWidth = it },
                valueRange = if (tool == EditorTool.PEN) 1f..8f else 8f..40f, modifier = Modifier.width(120.dp))
            TextButton(onClick = { canvas?.resetViewport() }) { Text("Fit width") }
            TextButton(enabled = pageIndex > 0 && !state.loading, onClick = {
                canvas?.finishInProgress(); model.editor.selectPage(pages[pageIndex - 1].metadata.id)
            }) { Text("Previous page") }
            Text("Page ${pageIndex + 1} of ${pages.size.coerceAtLeast(1)}", Modifier.padding(top = 16.dp))
            TextButton(enabled = pageIndex + 1 < pages.size && !state.loading, onClick = {
                canvas?.finishInProgress(); model.editor.selectPage(pages[pageIndex + 1].metadata.id)
            }) { Text("Next page") }
            TextButton(enabled = page != null && !state.loading, onClick = { canvas?.finishInProgress(); model.editor.addPage() }) { Text("Add page") }
            TextButton(enabled = state.canDeletePage, onClick = { canvas?.finishInProgress(); deleting = page?.metadata?.id }) { Text("Delete page") }
            TextButton(enabled = !state.loading && state.archivedPages.isNotEmpty(), onClick = { canvas?.finishInProgress(); restoring = true }) { Text("Restore pages") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(when {
                    state.loading -> "Loading journal…"
                    state.error != null -> state.error!!
                    state.pending > 0 -> "Saving ${state.pending} change(s)…"
                    state.strokes.isEmpty() && state.entry?.metadata?.revision == 1L -> "Blank page · Ready for your stylus"
                    else -> "Journal saved locally"
                }, style = MaterialTheme.typography.bodySmall)
                if (!state.loading && pages.size == 1) Text("Delete disabled: keep at least one active page.", style = MaterialTheme.typography.labelSmall)
            }
            if (state.error != null) TextButton(onClick = model.editor::retry) { Text("Retry") }
            TextButton(onClick = { canvas?.finishInProgress(); model.viewMonthlyHabits(date) }) { Text("View monthly habits") }
        }
        if (page != null && !state.loading) key(page.metadata.id) {
            AndroidView(factory = { ctx -> model.editor.retainSurface(date, page.metadata.id); JournalCanvas(ctx).also { canvas = it } },
                onReset = null, onRelease = { view -> view.finishInProgress(); model.editor.releaseSurface(date, page.metadata.id) },
                modifier = Modifier.fillMaxWidth().weight(1f), update = { view ->
                    canvas = view
                    view.bind(page, state.strokes, tool, color, if (tool == EditorTool.PEN) penWidth else markerWidth,
                        model.viewport(page.metadata.id), onViewport = { model.rememberViewport(page.metadata.id, it) },
                        onStroke = { brush, argb, width, points -> model.editor.addStroke(brush, argb, width, points, date, page.metadata.id) },
                        onErase = { model.editor.erase(it, date, page.metadata.id) })
                })
        } else Spacer(Modifier.weight(1f))
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this page?") },
        text = { Text("Move this page to recoverable archived pages? Its handwriting, identity and original order are kept. Use Restore pages to bring it back. At least one active page must remain.") },
        confirmButton = { TextButton(enabled = state.canDeletePage, onClick = { canvas?.finishInProgress(); model.editor.setPageArchived(id, true); deleting = null }) { Text("Move to archive") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel deletion") } }) }
    if (restoring) AlertDialog(onDismissRequest = { restoring = false }, title = { Text("Archived journal pages") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(state.archivedPages, key = { it.metadata.id.toString() }) { archived ->
                TextButton(onClick = { model.editor.setPageArchived(archived.metadata.id, false) }) { Text("Restore page ${archived.order + 1}") }
            }
            if (state.archivedPages.isEmpty()) item { Text("All pages restored.") }
        }
    }, confirmButton = { TextButton(onClick = { restoring = false }) { Text("Done restoring") } })
}
