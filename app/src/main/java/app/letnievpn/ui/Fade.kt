package app.letnievpn.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Мягкое растворение содержимого у верхнего края. Ничего не закрашивает сверху, а делает сам контент
 * прозрачнее (маска), поэтому цвет подложки не важен и светлой полосы на границе не бывает.
 * У самого края контент виден примерно на 18%, дальше плавно (smoothstep) проявляется до 100%.
 * strength — 0..1, насколько фейд включён (читается при отрисовке, без перекомпоновки).
 */
fun Modifier.topFade(height: Dp, strength: () -> Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val s = strength().coerceIn(0f, 1f)
        if (s > 0f) {
            val h = height.toPx()
            val n = 8
            val stops = Array(n + 1) { i ->
                val t = i / n.toFloat()
                val sm = t * t * (3f - 2f * t)
                val vis = 0.18f + 0.82f * sm
                t to Color.Black.copy(alpha = 1f - s * (1f - vis))
            }
            drawRect(
                Brush.verticalGradient(*stops, startY = 0f, endY = h),
                size = Size(size.width, h),
                blendMode = BlendMode.DstIn
            )
        }
    }
