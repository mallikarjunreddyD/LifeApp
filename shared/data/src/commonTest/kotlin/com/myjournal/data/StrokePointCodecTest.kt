package com.myjournal.data

import com.myjournal.domain.InkPoint
import kotlin.test.*

class StrokePointCodecTest {
    private val points = listOf(InkPoint(12.25f, 900.5f, 0, null), InkPoint(888.125f, 30.25f, 60, 0.75f))
    @Test fun preservesPointOrderFloatBitsTimingAndOptionalPressure() {
        assertEquals(points, StrokePointCodec.decode(StrokePointCodec.encode(points)))
    }
    @Test fun rejectsTruncatedUnknownAndOversizedFormats() {
        val bytes = StrokePointCodec.encode(points)
        assertFailsWith<IllegalArgumentException> { StrokePointCodec.decode(bytes.copyOf(10)) }
        assertFailsWith<IllegalArgumentException> { StrokePointCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<IllegalArgumentException> { StrokePointCodec.decode(bytes.copyOf(bytes.size + 1)) }
        assertFailsWith<IllegalArgumentException> { StrokePointCodec.decode(bytes.copyOf().also { it[4] = 2 }) }
        assertFailsWith<IllegalArgumentException> { StrokePointCodec.decode(bytes.copyOf().also { it[0] = 0 }) }
        assertFailsWith<IllegalArgumentException> { StrokePointCodec.decode(bytes.copyOf().also { for (i in 8..11) it[i] = 127 }) }
    }
}
