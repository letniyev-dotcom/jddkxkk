package app.letnievpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Линейные иконки сетки 24×24 с круглыми концами: солнце (в тёмной теме) и луна (в светлой). */
@Composable
fun ThemeIcon(dark: Boolean, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        if (dark) {
            drawCircle(color, radius = 4.5f * k, center = Offset(12f * k, 12f * k), style = Stroke(2f * k, cap = StrokeCap.Round))
            for (i in 0 until 8) {
                val a = Math.PI / 4 * i
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                drawLine(
                    color,
                    Offset((12f + 8f * c) * k, (12f + 8f * s) * k),
                    Offset((12f + 10.5f * c) * k, (12f + 10.5f * s) * k),
                    strokeWidth = 2f * k,
                    cap = StrokeCap.Round
                )
            }
        } else {
            val path = PathParser().parsePathString("M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z").toPath()
            withTransform({ scale(k, k, pivot = Offset.Zero) }) {
                drawPath(path, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Четыре скруглённых квадрата — список приложений. */
@Composable
fun AppsIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val st = Stroke(2f * k, join = StrokeJoin.Round)
        for ((x, y) in listOf(3f to 3f, 14f to 3f, 3f to 14f, 14f to 14f)) {
            drawRoundRect(color, Offset(x * k, y * k), Size(7f * k, 7f * k), CornerRadius(2f * k), st)
        }
    }
}

@Composable
fun PlusIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        drawLine(color, Offset(12f * k, 5f * k), Offset(12f * k, 19f * k), 2f * k, StrokeCap.Round)
        drawLine(color, Offset(5f * k, 12f * k), Offset(19f * k, 12f * k), 2f * k, StrokeCap.Round)
    }
}

/** Три точки по вертикали — кнопка меню в шапке. */
@Composable
fun DotsIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        for (y in listOf(5f, 12f, 19f)) drawCircle(color, radius = 2f * k, center = Offset(12f * k, y * k))
    }
}
