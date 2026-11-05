package app.letnievpn.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.letnievpn.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal class AppRow(val pkg: String, val label: String)

/** Список приложений и иконки грузим заранее, чтобы шторка открывалась без работы на главном потоке. */
internal object ExcludedAppsCache {
    @Volatile var apps: List<AppRow>? = null
    val icons = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(ctx: Context) = withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .filter { it.pkg != ctx.packageName }
        list.forEach { a ->
            if (!icons.containsKey(a.pkg)) {
                try {
                    icons[a.pkg] = pm.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap()
                } catch (_: Exception) {
                }
            }
        }
        apps = list
    }
}

private val RevealOpen = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val RevealShut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

// на конце дуги cos(π/2) в Float чуть меньше нуля, а дробная степень отрицательного числа — NaN, и контур пропадает целиком
private fun sn(t: Float, e: Float) = sin(t).coerceIn(0f, 1f).pow(e)
private fun cs(t: Float, e: Float) = cos(t).coerceIn(0f, 1f).pow(e)

/**
 * Контур со скруглением-суперэллипсом: при k = 1 это обычная дуга окружности (как у самого меню),
 * при k = 1.7 — «непрерывное» скругление как в iOS (как у окна). Между ними форма перетекает плавно.
 */
private fun squirclePath(l: Float, t: Float, rt: Float, b: Float, radius: Float, k: Float, bottomRadius: Float = radius): Path {
    val half = minOf((rt - l) / 2f, (b - t) / 2f)
    val r = minOf(radius, half).coerceAtLeast(0f)
    val rb = minOf(bottomRadius, half).coerceAtLeast(0f)
    val e = 2f / 2f.pow(k)
    val steps = 28
    val path = Path()
    path.moveTo(l + r, t)
    path.lineTo(rt - r, t)
    for (i in 1..steps) {
        val a = i / steps.toFloat() * (PI / 2).toFloat()
        path.lineTo(rt - r + r * sn(a, e), t + r - r * cs(a, e))
    }
    path.lineTo(rt, b - rb)
    for (i in 1..steps) {
        val a = i / steps.toFloat() * (PI / 2).toFloat()
        path.lineTo(rt - rb + rb * cs(a, e), b - rb + rb * sn(a, e))
    }
    path.lineTo(l + rb, b)
    for (i in 1..steps) {
        val a = i / steps.toFloat() * (PI / 2).toFloat()
        path.lineTo(l + rb - rb * sn(a, e), b - rb + rb * cs(a, e))
    }
    path.lineTo(l, t + r)
    for (i in 1..steps) {
        val a = i / steps.toFloat() * (PI / 2).toFloat()
        path.lineTo(l + r - r * cs(a, e), t + r - r * sn(a, e))
    }
    path.close()
    return path
}

private class MorphShape(
    private val rect: Rect,
    private val radius: Float,
    private val k: Float,
    private val bottomRadius: Float = radius,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(squirclePath(rect.left, rect.top, rect.right, rect.bottom, radius, k, bottomRadius))
}

private val MenuW = 236.dp
private val MenuH = 120.dp // 8 + два пункта по 52 + 8

/** Где стоят кнопка, меню и окно (в пикселях экрана) и как между ними переходит поверхность. */
private class MenuGeo(val button: Rect, val menu: Rect, val win: Rect, val screen: Size) {
    /** Окно, раскрытое на долю s (0..1): по бокам и снизу отступы уходят, верхний край остаётся на месте. */
    fun winAt(s: Float): Rect = Rect(
        left = win.left * (1f - s),
        top = win.top,
        right = win.right + (screen.width - win.right) * s,
        bottom = win.bottom + (screen.height - win.bottom) * s,
    )

    fun rect(t: Float, s: Float = 0f): Rect = if (t <= 1f) lerp(button, menu, t) else lerp(menu, winAt(s), t - 1f)
}

private fun menuGeo(d: Density, w: Dp, h: Dp, top: Dp, nav: Dp): MenuGeo = with(d) {
    val winH = h * 0.78f
    MenuGeo(
        button = Rect(Offset((w - 4.dp - 48.dp).toPx(), (top + 4.dp).toPx()), Size(48.dp.toPx(), 48.dp.toPx())),
        menu = Rect(Offset((w - 10.dp - MenuW).toPx(), (top + 8.dp).toPx()), Size(MenuW.toPx(), MenuH.toPx())),
        win = Rect(Offset(6.dp.toPx(), (h - nav - 8.dp - winH).toPx()), Size((w - 12.dp).toPx(), winH.toPx())),
        screen = Size(w.toPx(), h.toPx()),
    )
}

/**
 * Меню из трёх точек и окно исключений — одна и та же поверхность.
 * t = 0 — она сидит на кнопке, t = 1 — раскрыта меню (обычные скругления), t = 2 — перелетела вниз и стала окном
 * (скругление перетекает в непрерывное). Содержимое меню и окна просто проявляется и гаснет внутри неё.
 * mode: 0 — закрыто, 1 — меню, 2 — окно приложений. Кладётся внутрь корневого Box экрана, поверх остального.
 * Всё движение идёт через graphicsLayer — без перекомпоновки и пересчёта раскладки.
 * Изменения исключений вступают в силу при следующем подключении.
 */
@Composable
fun BoxScopeHeaderMenu(mode: Int, onMode: (Int) -> Unit, onAdd: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val d = LocalDensity.current

    val t = remember { Animatable(0f) }
    val shown by remember { derivedStateOf { t.value > 0.001f } }
    val winVisible by remember { derivedStateOf { t.value > 1.02f } }
    // затемнение живёт своей, более длинной и мягкой анимацией — иначе оно «выстреливает» вместе с быстрым началом перелёта окна
    val dim = remember { Animatable(0f) }
    // насколько окно раскрыто под экран (0 — с отступами, 1 — вровень с краями); ведёт его прокрутка списка
    val expand = remember { Animatable(0f) }
    LaunchedEffect(mode == 2) { if (mode != 2) expand.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
    LaunchedEffect(mode == 2) {
        dim.animateTo(if (mode == 2) 1f else 0f, tween(if (mode == 2) 700 else 350, easing = FastOutSlowInEasing))
    }

    // заранее готовим список приложений; после закрытия окна обновляем (могли поставить новое приложение)
    LaunchedEffect(mode == 2) { if (mode != 2 || ExcludedAppsCache.apps == null) ExcludedAppsCache.load(ctx) }

    LaunchedEffect(mode) {
        when (mode) {
            0 -> t.animateTo(0f, tween(420, easing = RevealShut))
            1 -> t.animateTo(1f, tween(520, easing = RevealOpen))
            else -> t.animateTo(2f, tween(620, easing = RevealOpen))
        }
    }

    BackHandler(enabled = mode != 0) { onMode(0) }

    if (shown || mode != 0) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val g = remember(maxWidth, maxHeight, top, nav, d) { menuGeo(d, maxWidth, maxHeight, top, nav) }
            val winW = maxWidth - 12.dp
            val winH = maxHeight * 0.78f

            // лёгкое затемнение фона, тап мимо закрывает
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dim.value } // затемняем только под окном, меню без затемнения
                    .background(Color.Black.copy(alpha = if (p.dark) 0.22f else 0.12f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = mode != 0,
                        onClick = { onMode(0) }
                    )
            )

            // сама поверхность: прямоугольник растёт и едет, углы меняются
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val tt = t.value
                        val w = (tt - 1f).coerceIn(0f, 1f)
                        val e = expand.value.coerceIn(0f, 1f)
                        val s = e * e * (3f - 2f * e)
                        val rad = (24f + (46f - 24f) * w).dp.toPx()
                        // нижние углы уходят в ноль: дальше их обрезает сам экран
                        shape = MorphShape(
                            g.rect(tt, s),
                            rad,
                            1f + 0.7f * w,
                            rad * (1f - s)
                        )
                        clip = true
                        alpha = (tt / 0.15f).coerceIn(0f, 1f)
                    }
                    .background(p.surface)
                    // тап по самой поверхности не должен уходить в затемнение
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                // пункты меню
                Column(
                    Modifier
                        .offset { IntOffset(g.menu.left.roundToInt(), g.menu.top.roundToInt()) }
                        .requiredSize(MenuW, MenuH)
                        .graphicsLayer {
                            val tt = t.value
                            alpha = if (tt <= 1f) ((tt - 0.45f) / 0.45f).coerceIn(0f, 1f)
                            else (1f - (tt - 1f) / 0.25f).coerceIn(0f, 1f)
                        }
                        .padding(8.dp)
                ) {
                    MenuItem("Добавить подписку", mode == 1, { PlusIcon(p.primary, 24.dp) }) {
                        onAdd()
                        onMode(0)
                    }
                    MenuItem("Исключения", mode == 1, { AppsIcon(p.primary, 24.dp) }) {
                        onMode(2)
                    }
                }

                // окно приложений
                if (winVisible || mode == 2) {
                    // содержимое сразу размером с раскрытое окно: при раскрытии растут только края, строки не двигаются
                    val winTopDp = with(d) { g.win.top.toDp() }
                    SheetContent(
                        Modifier
                            .offset { IntOffset(0, g.win.top.roundToInt()) }
                            .requiredSize(maxWidth, maxHeight - winTopDp)
                            .graphicsLayer { alpha = ((t.value - 1.45f) / 0.5f).coerceIn(0f, 1f) },
                        expand = expand,
                        active = mode == 2,
                        bottomPad = nav + 8.dp + 24.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuItem(title: String, enabled: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SheetContent(modifier: Modifier, expand: Animatable<Float, AnimationVector1D>, active: Boolean, bottomPad: Dp) {
    val p = LocalPalette.current
    val excluded by Repo.excluded.collectAsState()
    // порядок фиксируем при открытии: выбранные сверху, дальше по алфавиту — строки не прыгают при нажатии
    val startExcluded = remember { Repo.excluded.value }
    val apps by produceState<List<AppRow>?>(null) {
        while (ExcludedAppsCache.apps == null) delay(30)
        val base = ExcludedAppsCache.apps!!
        value = withContext(Dispatchers.Default) {
            base.sortedWith(compareByDescending<AppRow> { it.pkg in startExcluded }.thenBy { it.label.lowercase() })
        }
    }
    val listAlpha by animateFloatAsState(if (apps != null) 1f else 0f, tween(260), label = "list")

    val listState = rememberLazyListState()
    // прокрутка списка раскрывает окно: за 40 dp отступы по бокам и снизу плавно уходят, назад — возвращаются
    val reachPx = with(LocalDensity.current) { 40.dp.toPx() }
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / reachPx).coerceIn(0f, 1f)
        }.collectLatest { target ->
            // небольшое сглаживание, чтобы окно не дёргалось вслед за пальцем
            expand.animateTo(target, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium))
        }
    }
    val fade by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 60f).coerceIn(0f, 1f)
        }
    }
    Box(modifier) {
        val list = apps
        if (list != null) {
            LazyColumn(
                Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha }.topFade(96.dp) { fade },
                state = listState,
                contentPadding = PaddingValues(top = 68.dp, bottom = bottomPad)
            ) {
                items(list, key = { it.pkg }) { app ->
                    val on = app.pkg in excluded
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { Repo.toggleExcluded(app.pkg) }
                            .padding(horizontal = 30.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.pkg)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            app.label,
                            color = p.text,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        CheckBox(on)
                    }
                }
            }
        }

        Text(
            "Приложения без VPN",
            color = p.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp)
        )
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(ExcludedAppsCache.icons[pkg], pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
    }
}

@Composable
private fun CheckBox(on: Boolean) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(24.dp)
            .clip(shape)
            .background(if (on) p.primary else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.5.dp, if (on) p.primary else p.text2, shape),
        contentAlignment = Alignment.Center
    ) {
        if (on) {
            Canvas(Modifier.size(14.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.1f, size.height * 0.55f)
                    lineTo(size.width * 0.4f, size.height * 0.85f)
                    lineTo(size.width * 0.92f, size.height * 0.15f)
                }
                drawPath(path, p.onPrimary, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
