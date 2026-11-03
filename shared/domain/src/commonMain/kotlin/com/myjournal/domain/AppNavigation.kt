package com.myjournal.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate

enum class AppSection { JOURNAL, PLANNER, HABITS, NOTEBOOK, SETTINGS }

data class AppRoute(val section: AppSection, val date: LocalDate? = null, val month: JournalMonth? = null) {
    init {
        require(section != AppSection.JOURNAL || date != null)
        require(section !in listOf(AppSection.PLANNER, AppSection.HABITS) || month != null)
    }
    fun encode() = "${section.name}|${date ?: month ?: ""}"
    companion object {
        fun decode(value: String): AppRoute {
            val parts = value.split('|', limit = 2)
            val section = AppSection.valueOf(parts[0])
            return when (section) {
                AppSection.JOURNAL -> AppRoute(section, date = LocalDate.parse(parts[1]))
                AppSection.PLANNER, AppSection.HABITS -> AppRoute(section, month = JournalMonth.parse(parts[1]))
                else -> AppRoute(section)
            }
        }
    }
}

class AppNavigator(initial: List<AppRoute>) {
    init { require(initial.isNotEmpty()) }
    private val mutable = MutableStateFlow(initial.toList())
    val stack = mutable.asStateFlow()
    val current get() = mutable.value.last()
    val canGoBack get() = mutable.value.size > 1
    fun push(route: AppRoute) { if (route != current) mutable.value = (mutable.value + route).takeLast(32) }
    fun replace(route: AppRoute) { mutable.value = mutable.value.dropLast(1) + route }
    fun select(route: AppRoute) { mutable.value = listOf(route) }
    fun back(): Boolean {
        if (!canGoBack) return false
        mutable.value = mutable.value.dropLast(1)
        return true
    }
}
