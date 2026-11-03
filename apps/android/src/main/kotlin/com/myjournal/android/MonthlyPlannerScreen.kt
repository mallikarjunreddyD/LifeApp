package com.myjournal.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.myjournal.domain.*

@Composable
fun MonthlyPlannerScreen(model: JournalViewModel) {
    val state by model.planner.state.collectAsState()
    var toolName by rememberSaveable { mutableStateOf(EditorTool.PEN.name) }
    var color by rememberSaveable { mutableLongStateOf(0xFF252A30L) }
    var penWidth by rememberSaveable { mutableFloatStateOf(2.8f) }
    var markerWidth by rememberSaveable { mutableFloatStateOf(18f) }
    val tool = EditorTool.valueOf(toolName)
    var canvas by remember { mutableStateOf<JournalCanvas?>(null) }
    val month = state.month
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        MonthControls(month, { model.changeMonth(AppSection.PLANNER, it) }, { canvas?.finishInProgress() })
        SharedInkTools(tool, color, { canvas?.finishInProgress(); toolName = it.name }, { canvas?.finishInProgress(); color = it },
            state.canUndo, state.canRedo, { canvas?.finishInProgress(); model.planner.undo() }, { canvas?.finishInProgress(); model.planner.redo() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(if (tool == EditorTool.ERASER) "Whole-stroke eraser" else "Thickness", Modifier.padding(top = 16.dp))
            if (tool != EditorTool.ERASER) Slider(value = if (tool == EditorTool.PEN) penWidth else markerWidth,
                onValueChange = { if (tool == EditorTool.PEN) penWidth = it else markerWidth = it },
                valueRange = if (tool == EditorTool.PEN) 1f..8f else 8f..40f, modifier = Modifier.width(144.dp))
            TextButton(onClick = { canvas?.resetViewport() }) { Text("Fit calendar") }
            Text("Stylus writes · Finger taps dates · Pinch to zoom", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 18.dp, start = 8.dp))
        }
        Row {
            Text(when {
                state.loading -> "Loading planner…"
                state.error != null -> state.error!!
                state.pending > 0 -> "Saving planner…"
                state.strokes.isEmpty() -> "Blank calendar · Ready for your stylus"
                else -> "Planner saved locally"
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (state.error != null) TextButton(onClick = model.planner::retry) { Text("Retry planner") }
        }
        val planner = state.planner
        if (!state.loading && planner != null) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = isLandscape
            val initial = Viewport(if (landscape) 1f else maxOf(1f, 1000f / maxWidth.value).coerceAtMost(4f), 0f, 0f)
            key(planner.metadata.id) {
                AndroidView(factory = { context ->
                    model.planner.retain(month)
                    JournalCanvas(context).also { canvas = it }
                }, onReset = null, onRelease = { it.finishInProgress(); model.planner.release(month) },
                    modifier = Modifier.fillMaxSize(), update = { view ->
                        canvas = view
                        view.bindSurface(planner.surface, state.strokes, tool, color, if (tool == EditorTool.PEN) penWidth else markerWidth,
                            model.savedViewport(planner.metadata.id) ?: initial,
                            { model.rememberViewport(planner.metadata.id, it) },
                            { brush, argb, width, points -> model.planner.addStroke(brush, argb, width, points, month) },
                            { model.planner.erase(it, month) }, fitPage = landscape,
                            onDateTap = { view.finishInProgress(); model.openJournal(it) })
                    })
            }
        } else Spacer(Modifier.weight(1f))
    }
}
