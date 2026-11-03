package com.myjournal.android

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myjournal.domain.JournalEditor
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

data class Viewport(val zoom: Float = 1f, val centerX: Float = 500f, val centerY: Float = 0f)

class JournalViewModel(val editor: JournalEditor, private val saved: SavedStateHandle) : ViewModel() {
    private val viewports = mutableMapOf<Uuid, Viewport>()
    init {
        val date = saved.get<String>("date")?.let(LocalDate::parse) ?: today()
        val page = saved.get<String>("page")?.let(Uuid::parse)
        if (page != null) viewports[page] = Viewport(saved["zoom"] ?: 1f, saved["centerX"] ?: 500f, saved["centerY"] ?: 0f)
        editor.open(date, page)
        viewModelScope.launch {
            editor.state.collect {
                saved["date"] = it.date.toString()
                if (it.pageId != null) saved["page"] = it.pageId.toString()
            }
        }
    }
    fun viewport(id: Uuid) = viewports[id] ?: Viewport()
    fun rememberViewport(id: Uuid, viewport: Viewport) {
        viewports[id] = viewport
        if (editor.state.value.pageId == id) {
            saved["zoom"] = viewport.zoom
            saved["centerX"] = viewport.centerX
            saved["centerY"] = viewport.centerY
        }
    }
}
