package com.myjournal.data

import com.myjournal.domain.InkPoint

/** LJPT v1: little-endian header and fixed-size IEEE-754 point records. */
object StrokePointCodec {
    fun encode(points: List<InkPoint>): ByteArray {
        require(points.isNotEmpty() && points.size <= 1_000_000)
        require(points.zipWithNext().all { (a, b) -> b.elapsedMillis > a.elapsedMillis })
        val bytes = ByteArray(12 + points.size * 20)
        var offset = 0
        fun int(value: Int) { repeat(4) { bytes[offset++] = (value ushr (it * 8)).toByte() } }
        int(0x54504A4C); int(1); int(points.size)
        points.forEach {
            int(it.x.toRawBits()); int(it.y.toRawBits())
            int((it.pressure ?: -1f).toRawBits())
            int(it.elapsedMillis.toInt()); int((it.elapsedMillis ushr 32).toInt())
        }
        return bytes
    }
    fun decode(bytes: ByteArray): List<InkPoint> {
        require(bytes.size >= 12) { "Truncated stroke" }
        var offset = 0
        fun int(): Int {
            var value = 0
            repeat(4) { value = value or ((bytes[offset++].toInt() and 255) shl (it * 8)) }
            return value
        }
        require(int() == 0x54504A4C) { "Unknown stroke signature" }
        require(int() == 1) { "Unsupported point format" }
        val count = int()
        require(count in 1..1_000_000 && bytes.size == 12 + count * 20) { "Invalid stroke length" }
        return List(count) {
            val x = Float.fromBits(int()); val y = Float.fromBits(int())
            val pressure = Float.fromBits(int())
            val elapsed = (int().toLong() and 0xffffffffL) or (int().toLong() shl 32)
            InkPoint(x, y, elapsed, if (pressure == -1f) null else pressure)
        }.also { points -> require(points.zipWithNext().all { (a, b) -> b.elapsedMillis > a.elapsedMillis }) }
    }
}
