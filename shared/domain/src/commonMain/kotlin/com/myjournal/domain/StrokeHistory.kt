package com.myjournal.domain

import kotlin.uuid.Uuid

data class StrokeChange(val added: List<InkStroke>, val removed: List<InkStroke>) {
    fun inverse() = StrokeChange(removed, added)
}

/** Whole strokes are retained verbatim for eraser undo; history is per page, in memory. */
class StrokeHistory(initial: List<InkStroke>) {
    private val strokes = initial.associateBy { it.metadata.id }.toMutableMap()
    private val undo = ArrayDeque<StrokeChange>()
    private val redo = ArrayDeque<StrokeChange>()
    init {
        require(strokes.size == initial.size)
        require(initial.map { it.order }.distinct().size == initial.size)
        require(initial.map { it.pageId }.distinct().size <= 1)
    }
    val current: List<InkStroke> get() = strokes.values.sortedBy { it.order }
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun add(stroke: InkStroke): StrokeChange {
        require(stroke.metadata.id !in strokes)
        require(strokes.values.none { it.order == stroke.order || it.pageId != stroke.pageId })
        return record(StrokeChange(listOf(stroke), emptyList()))
    }
    fun erase(ids: Set<Uuid>): StrokeChange? {
        val removed = current.filter { it.metadata.id in ids }
        return if (removed.isEmpty()) null else record(StrokeChange(emptyList(), removed))
    }
    fun undo(): StrokeChange? {
        val change = undo.removeLastOrNull() ?: return null
        redo.addLast(change)
        return change.inverse().also(::apply)
    }
    fun redo(): StrokeChange? {
        val change = redo.removeLastOrNull() ?: return null
        undo.addLast(change)
        return change.also(::apply)
    }
    private fun record(change: StrokeChange): StrokeChange {
        apply(change)
        undo.addLast(change)
        redo.clear()
        return change
    }
    private fun apply(change: StrokeChange) {
        change.removed.forEach { strokes.remove(it.metadata.id) }
        change.added.forEach { strokes[it.metadata.id] = it }
    }
}
