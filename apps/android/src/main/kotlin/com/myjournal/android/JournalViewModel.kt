package com.myjournal.android

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myjournal.domain.*
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

data class Viewport(val zoom: Float = 1f, val centerX: Float = 500f, val centerY: Float = 0f, val fitPage: Boolean = false)

class JournalViewModel(val editor: JournalEditor, private val saved: SavedStateHandle,
    val planner: PlannerEditor, val habits: HabitEditor, val notebooks: NotebookEditor) : ViewModel() {
    val navigation = AppNavigator(saved.get<ArrayList<String>>("routes")?.map(AppRoute::decode)
        ?: listOf(AppRoute(AppSection.JOURNAL, date = saved.get<String>("date")?.let(LocalDate::parse) ?: today())))
    private var plannerMonth = saved.get<String>("plannerMonth")?.let(JournalMonth::parse) ?: JournalMonth.from(today())
    private var habitMonth = saved.get<String>("habitMonth")?.let(JournalMonth::parse) ?: JournalMonth.from(today())
    private val viewports = mutableMapOf<Uuid, Viewport>()
    init {
        val date = saved.get<String>("date")?.let(LocalDate::parse) ?: today()
        val page = saved.get<String>("page")?.let(Uuid::parse)
        if (page != null) viewports[page] = Viewport(saved["zoom"] ?: 1f, saved["centerX"] ?: 500f, saved["centerY"] ?: 0f, saved["fitPage"] ?: false)
        val initial = navigation.current
        if (initial.section == AppSection.JOURNAL) editor.open(initial.date!!, page.takeIf { initial.date == date })
        else { editor.open(date, page); applyRoute(initial) }
        viewModelScope.launch { navigation.stack.collect { saved["routes"] = ArrayList(it.map(AppRoute::encode)) } }
        viewModelScope.launch {
            editor.state.collect {
                saved["date"] = it.date.toString()
                if (it.pageId != null) saved["page"] = it.pageId.toString()
            }
        }
    }
    private fun applyRoute(route: AppRoute) {
        when (route.section) {
            AppSection.JOURNAL -> editor.open(route.date!!)
            AppSection.PLANNER -> { plannerMonth = route.month!!; saved["plannerMonth"] = plannerMonth.toString(); planner.open(plannerMonth) }
            AppSection.HABITS -> { habitMonth = route.month!!; saved["habitMonth"] = habitMonth.toString(); habits.open(habitMonth) }
            AppSection.NOTEBOOK -> notebooks.open(saved.get<String>("notebook")?.let(Uuid::parse), saved.get<String>("notebookPage")?.let(Uuid::parse))
            else -> Unit
        }
    }
    fun selectSection(section: AppSection) {
        val route = when (section) {
            AppSection.JOURNAL -> AppRoute(section, date = editor.state.value.date)
            AppSection.PLANNER -> AppRoute(section, month = plannerMonth)
            AppSection.HABITS -> AppRoute(section, month = habitMonth)
            else -> AppRoute(section)
        }
        navigation.select(route); applyRoute(route)
    }
    fun openJournal(date: LocalDate) { val route = AppRoute(AppSection.JOURNAL, date = date); navigation.push(route); applyRoute(route) }
    fun viewMonthlyHabits(date: LocalDate) {
        val route = AppRoute(AppSection.HABITS, month = JournalMonth.from(date)); navigation.push(route); applyRoute(route)
    }
    fun changeJournalDate(date: LocalDate) {
        val route = AppRoute(AppSection.JOURNAL, date = date); navigation.replace(route); applyRoute(route)
    }
    fun changeMonth(section: AppSection, month: JournalMonth) {
        val route = AppRoute(section, month = month); navigation.replace(route); applyRoute(route)
    }
    fun back() { if (navigation.back()) applyRoute(navigation.current) }
    fun openNotebook(id: Uuid?) { saved["notebook"] = id?.toString(); notebooks.open(id) }
    fun rememberNotebookSelection(id: Uuid, page: Uuid?) { saved["notebook"] = id.toString(); saved["notebookPage"] = page?.toString() }
    fun habitScroll(month: JournalMonth): List<Int> = saved["habitScroll:$month"] ?: listOf(0, 0, 0)
    fun rememberHabitScroll(month: JournalMonth, row: Int, offset: Int, x: Int) { saved["habitScroll:$month"] = listOf(row, offset, x) }
    fun savedViewport(id: Uuid) = viewports[id]
    fun viewport(id: Uuid) = viewports[id] ?: Viewport()
    fun rememberViewport(id: Uuid, viewport: Viewport) {
        viewports[id] = viewport
        if (editor.state.value.pageId == id) {
            saved["fitPage"] = viewport.fitPage
            saved["zoom"] = viewport.zoom
            saved["centerX"] = viewport.centerX
            saved["centerY"] = viewport.centerY
        }
    }
}
