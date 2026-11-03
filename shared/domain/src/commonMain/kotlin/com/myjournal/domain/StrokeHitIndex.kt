package com.myjournal.domain

import kotlin.math.*
import kotlin.uuid.Uuid

/** Logical-space buckets. Only added/removed strokes change the index. */
class StrokeHitIndex(strokes: List<InkStroke>) {
    private val strokesById = mutableMapOf<Uuid, InkStroke>()
    private val cells = mutableMapOf<Pair<Int, Int>, MutableSet<Uuid>>()
    private val strokeCells = mutableMapOf<Uuid, List<Pair<Int, Int>>>()
    init { update(strokes) }
    fun update(strokes: List<InkStroke>) {
        val ids = strokes.map { it.metadata.id }.toSet()
        require(ids.size == strokes.size)
        for (id in strokesById.keys.toList()) if (id !in ids) {
            strokeCells.remove(id)?.forEach { key ->
                cells[key]?.remove(id)
                if (cells[key]?.isEmpty() == true) cells.remove(key)
            }
            strokesById.remove(id)
        }
        for (stroke in strokes) {
            val id = stroke.metadata.id
            if (id in strokesById) { require(strokesById[id] == stroke); continue }
            val pad = stroke.width / 2
            val minX = floor((stroke.points.minOf { it.x } - pad) / 64).toInt()
            val maxX = floor((stroke.points.maxOf { it.x } + pad) / 64).toInt()
            val minY = floor((stroke.points.minOf { it.y } - pad) / 64).toInt()
            val maxY = floor((stroke.points.maxOf { it.y } + pad) / 64).toInt()
            require((maxX.toLong() - minX + 1) * (maxY.toLong() - minY + 1) <= 100_000)
            val keys = mutableListOf<Pair<Int, Int>>()
            for (x in minX..maxX) for (y in minY..maxY) {
                val key = x to y
                cells.getOrPut(key) { mutableSetOf() }.add(id)
                keys.add(key)
            }
            strokeCells[id] = keys
            strokesById[id] = stroke
        }
    }
    fun hit(x: Float, y: Float, radius: Float): Set<Uuid> {
        require(radius.isFinite() && radius > 0 && x.isFinite() && y.isFinite())
        val candidates = mutableSetOf<Uuid>()
        for (cx in floor((x - radius) / 64).toInt()..floor((x + radius) / 64).toInt())
            for (cy in floor((y - radius) / 64).toInt()..floor((y + radius) / 64).toInt())
                cells[cx to cy]?.let(candidates::addAll)
        return candidates.filterTo(mutableSetOf()) { id -> intersects(strokesById.getValue(id), x, y, radius) }
    }
    private fun intersects(stroke: InkStroke, x: Float, y: Float, radius: Float): Boolean {
        val limit = radius + stroke.width / 2
        val points = stroke.points
        if (points.size == 1) return hypot(x - points[0].x, y - points[0].y) <= limit
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dx = b.x - a.x; val dy = b.y - a.y
            val length = dx * dx + dy * dy
            val t = if (length == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / length).coerceIn(0f, 1f)
            if (hypot(x - a.x - t * dx, y - a.y - t * dy) <= limit) return true
        }
        return false
    }
}
