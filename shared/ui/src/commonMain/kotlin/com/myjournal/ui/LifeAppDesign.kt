package com.myjournal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.myjournal.domain.AppSection

@Composable
fun LifeAppMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(36.dp)) {
        val green = Color(0xFF144C3C)
        drawRoundRect(green, cornerRadius = CornerRadius(size.width * .23f))
        scale(size.width / 512f, size.height / 512f, Offset.Zero) {
            drawPath(Path().apply {
                moveTo(148f, 164f)
                quadraticTo(210f, 149f, 256f, 185f)
                quadraticTo(302f, 149f, 364f, 164f)
                lineTo(364f, 316f)
                quadraticTo(305f, 302f, 256f, 340f)
                quadraticTo(207f, 302f, 148f, 316f)
                close()
            }, Color(0xFFFAF4DE))
            drawLine(green, Offset(256f, 185f), Offset(256f, 330f), 8f)
            for (y in listOf(207f, 233f, 259f)) {
                drawPath(Path().apply {
                    moveTo(172f, y)
                    quadraticTo(212f, y - 6f, 235f, y + 11f)
                }, green, style = Stroke(8f))
            }
            val clay = Color(0xFFC57650)
            drawLine(clay, Offset(281f, 249f), Offset(302f, 269f), 12f, StrokeCap.Round)
            drawLine(clay, Offset(302f, 269f), Offset(341f, 222f), 12f, StrokeCap.Round)
        }
    }
}

@Composable
internal fun DestinationIcon(section: AppSection, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(23.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val outline = Stroke(1.65f)
            fun line(x: Int, y: Int, toX: Int, toY: Int) =
                drawLine(color, Offset(x.toFloat(), y.toFloat()), Offset(toX.toFloat(), toY.toFloat()), 1.65f, StrokeCap.Round)
            fun box(x: Int, y: Int, w: Int, h: Int) =
                drawRoundRect(color, Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()), CornerRadius(2f), style = outline)
            when (section) {
                AppSection.JOURNAL, AppSection.NOTEBOOK -> {
                    box(5, 3, 14, 18)
                    line(9, 3, 9, 21)
                    if (section == AppSection.JOURNAL) {
                        line(12, 9, 16, 9)
                        line(12, 13, 16, 13)
                    } else line(13, 3, 13, 10)
                }
                AppSection.PLANNER -> {
                    box(3, 5, 18, 16)
                    line(3, 10, 21, 10)
                    line(8, 3, 8, 7)
                    line(16, 3, 16, 7)
                    line(7, 15, 11, 15)
                }
                AppSection.HABITS -> {
                    drawCircle(color, 8f, Offset(12f, 12f), style = outline)
                    line(8, 12, 11, 15)
                    line(11, 15, 16, 9)
                }
                AppSection.TODO -> {
                    box(3, 4, 18, 16)
                    line(7, 11, 10, 14)
                    line(10, 14, 16, 8)
                }
                AppSection.WORKOUTS -> {
                    line(3, 9, 3, 15)
                    line(6, 6, 6, 18)
                    line(6, 12, 18, 12)
                    line(18, 6, 18, 18)
                    line(21, 9, 21, 15)
                }
                AppSection.EXPENSES -> {
                    box(3, 5, 18, 14)
                    box(14, 9, 7, 6)
                    drawCircle(color, .9f, Offset(17f, 12f))
                }
                AppSection.SETTINGS -> {
                    for (y in listOf(6, 12, 18)) line(4, y, 20, y)
                    for ((x, y) in listOf(8 to 6, 15 to 12, 10 to 18)) {
                        drawCircle(color, 2.5f, Offset(x.toFloat(), y.toFloat()))
                    }
                }
            }
        }
    }
}
