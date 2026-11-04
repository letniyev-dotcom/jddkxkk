package app.letnievpn.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import androidx.core.view.drawToBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import kotlin.math.hypot
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.abs
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.letnievpn.R
import app.letnievpn.data.Repo
import app.letnievpn.data.Server
import app.letnievpn.data.Sub
import app.letnievpn.data.VpnStatus
import kotlinx.coroutines.launch

@Composable
fun MainScreen(onPower: () -> Unit, onReconnect: () -> Unit, onPickAvatar: (String) -> Unit) {
    // размер шрифта не раздуваем системным масштабом — иначе блоки выходят громоздкими
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = minOf(d.fontScale, 1f))) {
        LetnieTheme { Content(onPower, onReconnect, onPickAvatar) }
    }
}

// ── стекло: объёмная заливка в светлой теме, плоская в тёмной ─────────────

private fun Modifier.glass(
    p: Palette,
    shape: Shape,
    a: Color,
    b: Color,
    elevation: Dp,
    shadowColor: Color,
    flatColor: Color,
): Modifier = this
    .shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor)
    .background(Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset.Infinite), shape)
    .border(
        1.5.dp,
        Brush.linearGradient(
            listOf(p.rimHi, p.rimLo, p.rimLo, p.rimHi),
            start = Offset.Zero,
            end = Offset.Infinite
        ),
        shape
    )

/** Кнопка включения: стеклянный круг. */
private fun Modifier.btnSurface(p: Palette, a: Color, b: Color, shadow: Color, flat: Color): Modifier =
    this.glass(p, CircleShape, a, b, 14.dp, shadow, flat)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(
    onPower: () -> Unit,
    onReconnect: () -> Unit,
    onPickAvatar: (String) -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current

    val subs by Repo.subs.collectAsState()
    val smart by Repo.smart.collectAsState()
    val selected by Repo.selected.collectAsState()
    val pings by Repo.pings.collectAsState()
    val status by Repo.vpn.collectAsState()
    val vpnServer by Repo.vpnServer.collectAsState()
    val connError by Repo.connError.collectAsState()
    val tunnelMs by Repo.tunnelDelay.collectAsState()

    // Отступы под системные панели берём «независимо от видимости» и запоминаем наибольшие:
    // при смахивании в панель задач система на миг обнуляет статус-бар и навигацию,
    // и без этого весь интерфейс подпрыгивал бы вверх.
    val topNow = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val bottomNow = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    val orientation = LocalConfiguration.current.orientation
    val keep = remember(orientation) { floatArrayOf(0f, 0f) }
    keep[0] = maxOf(keep[0], topNow.value)
    keep[1] = maxOf(keep[1], bottomNow.value)
    val top = keep[0].dp
    val bottom = keep[1].dp

    // Читаем состояния, чтобы экран перерисовывался при их смене
    @Suppress("UNUSED_VARIABLE") val touch = listOf(smart, selected, pings, subs)
    val best = Repo.best()
    val current = Repo.current()
    val shown: Server? = if (status == VpnStatus.OFF) current
    else subs.flatMap { it.servers }.firstOrNull { it.id == vpnServer } ?: current

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    // смена темы «волной» из кнопки: снимок старого экрана поверх, в нём растёт (или сжимается) круг
    var reveal by remember { mutableStateOf<Reveal?>(null) }
    val revealT = remember { Animatable(0f) }
    var themeBtn by remember { mutableStateOf(Offset.Zero) }
    // 0 — меню закрыто, 1 — меню из трёх точек, 2 — окно исключений
    var menuMode by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .topFade(top + 96.dp) { scroll.value / 90f }
                .verticalScroll(scroll)
                .padding(top = top + 56.dp, bottom = bottom + 28.dp, start = 12.dp, end = 12.dp)
        ) {
            PowerBlock(status, shown, smart, tunnelMs, connError, onPower)
            SmartCard(smart) {
                if (smart) {
                    Repo.setSmart(false)
                    if (Repo.selected.value == null) Repo.selected.value = best?.id
                } else {
                    Repo.setSmart(true)
                }
                onReconnect()
            }
            Spacer(Modifier.height(10.dp))
            SubsCard(subs, best, shown, pings, if (status != VpnStatus.OFF) shown?.id else null, scroll, top, bottom, onPickAvatar) { id ->
                Repo.select(id)
                onReconnect()
            }
        }

        // шапка: слева смена темы, логотип и заголовок по центру, справа три точки с меню
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = top)
                .height(56.dp)
        ) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(p.primary),
                    modifier = Modifier.size(22.dp, 24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.app_name),
                    color = p.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.1.sp
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(48.dp)
                    .onGloballyPositioned { themeBtn = it.positionInWindow() + Offset(it.size.width / 2f, it.size.height / 2f) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (reveal == null) {
                            val toDark = !p.dark
                            val from = themeBtn
                            captureWindow(view) { bmp ->
                                if (bmp == null) {
                                    Repo.setThemeDark(toDark)
                                } else {
                                    reveal = Reveal(bmp.asImageBitmap(), from, toDark)
                                    Repo.setThemeDark(toDark)
                                    scope.launch {
                                        revealT.snapTo(0f)
                                        revealT.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
                                        reveal = null
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // в тёмной теме показываем солнце (переключить на светлую), в светлой — луну
                ThemeIcon(p.dark, p.primary, 24.dp)
            }
            val dotsAlpha by animateFloatAsState(
                if (menuMode == 0) 1f else 0f,
                tween(150, delayMillis = if (menuMode == 0) 280 else 0),
                label = "dots"
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(48.dp)
                    .graphicsLayer { alpha = dotsAlpha }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = menuMode == 0
                    ) { menuMode = 1 },
                contentAlignment = Alignment.Center
            ) {
                DotsIcon(p.primary, 24.dp)
            }
        }

        BoxScopeHeaderMenu(menuMode, { menuMode = it }) {
            // «Добавить подписку»: молча берём из буфера обмена, без сообщений и всплывашек
            val text = readClipboard(ctx)
            if (text.isNotBlank()) Repo.scope.launch { Repo.addFromText(text) }
        }

        // снимок старой темы поверх всего: в тёмную — из кнопки растёт «дыра» с новой темой,
        // в светлую — старая тёмная тема сжимается обратно в кнопку
        reveal?.let { r ->
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { } } // пока идёт анимация, нажатия не проходят
            ) {
                val maxR = maxOf(
                    hypot(r.center.x, r.center.y),
                    hypot(size.width - r.center.x, r.center.y),
                    hypot(r.center.x, size.height - r.center.y),
                    hypot(size.width - r.center.x, size.height - r.center.y),
                )
                val k = revealT.value
                val radius = if (r.toDark) maxR * k else maxR * (1f - k)
                // мягкий край из прототипа: 900 px на ширине 360 px = 2,5 ширины экрана.
                // Снимок старой темы живёт на слое, край срезан радиальной маской с
                // smootherstep-альфой (нулевые производные на краях) — ступеньки не видно.
                val feather = size.width * 2.5f
                val inner = maxOf(0f, radius - feather)
                val from = if (radius > 0.5f) inner / radius else 0f
                val stops = (0..32).map { i ->
                    val u = i / 32f
                    val a = u * u * u * (u * (u * 6 - 15) + 10)
                    val alpha = if (r.toDark) a else 1f - a
                    Color.Black.copy(alpha = alpha) to (from + (1f - from) * u)
                }
                // Низкоуровневый Canvas через нативный android.graphics.Canvas:
                // в новых версиях Compose старый интерфейс androidx.compose.ui.graphics.Canvas
                // (drawIntoCanvas/saveLayer/drawImageRect) удалён, а этот вариант стабилен.
                val android = drawContext.canvas.nativeCanvas
                val layer = android.saveLayer(0f, 0f, size.width, size.height, null)
                android.drawBitmap(
                    r.shot.asAndroidBitmap(),
                    0f, 0f,
                    android.graphics.Paint().apply {
                        isAntiAlias = true
                        isFilterBitmap = true
                    }
                )
                val gradientPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    shader = android.graphics.RadialGradient(
                        r.center.x,
                        r.center.y,
                        radius.coerceAtLeast(1f),
                        IntArray(33) { i ->
                            val u = i / 32f
                            val a = u * u * u * (u * (u * 6 - 15) + 10)
                            val alpha = if (r.toDark) a else 1f - a
                            android.graphics.Color.argb(
                                (alpha * 255).roundToInt().coerceIn(0, 255), 0, 0, 0
                            )
                        },
                        FloatArray(33) { i -> from + (1f - from) * (i / 32f) },
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    xfermode = android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.DST_IN
                    )
                }
                android.drawRect(0f, 0f, size.width, size.height, gradientPaint)
                android.restoreToCount(layer)
            }
        }
    }
}

private class Reveal(val shot: ImageBitmap, val center: Offset, val toDark: Boolean)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    return c as? Activity
}

/** Снимок окна приложения (PixelCopy, на старых Android — через draw). null — снять не вышло. */
private fun captureWindow(view: View, done: (Bitmap?) -> Unit) {
    val win = view.context.findActivity()?.window
    val root = win?.decorView
    if (win == null || root == null || root.width <= 0 || root.height <= 0) { done(null); return }
    try {
        if (Build.VERSION.SDK_INT >= 26) {
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(win, bmp, { res ->
                if (res == PixelCopy.SUCCESS) done(bmp) else { bmp.recycle(); done(null) }
            }, Handler(Looper.getMainLooper()))
        } else {
            done(root.drawToBitmap())
        }
    } catch (e: Throwable) {
        done(null)
    }
}

private fun readClipboard(ctx: Context): String = try {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = cm.primaryClip
    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
} catch (e: Exception) {
    ""
}

// ── кнопка включения ─────────────────────────────────────────────────────

@Composable
private fun PowerBlock(
    status: VpnStatus,
    shown: Server?,
    smart: Boolean,
    tunnelMs: Int,
    connError: String,
    onPower: () -> Unit,
) {
    // пинг текущего сервера читаем из его собственного потока: шапка не реагирует
    // на пакеты замеров всей сотни серверов
    val shownPing = if (shown != null) Repo.pingFlow(shown.id).collectAsState().value else null
    val p = LocalPalette.current
    val on = status == VpnStatus.ON
    val active = status != VpnStatus.OFF

    val iconColor by animateColorAsState(if (active) p.primary else p.text2, tween(500), label = "icon")
    val a by animateColorAsState(
        if (on) lerp(p.ga, p.primary, 0.26f) else p.ga, tween(500), label = "a"
    )
    val b by animateColorAsState(
        if (on) lerp(p.gb, p.primary, 0.06f) else p.gb, tween(500), label = "b"
    )
    val flat by animateColorAsState(
        if (on) lerp(p.surface, p.primary, 0.20f) else p.surface, tween(500), label = "flat"
    )
    val shadow by animateColorAsState(
        if (on) p.primary.copy(alpha = 0.45f) else if (p.dark) Color(0x99000000) else Color(0x33783C50), tween(500), label = "shadow"
    )
    val ringAlpha by animateFloatAsState(
        if (status == VpnStatus.CONNECTING) 1f else 0f, tween(450), label = "ring"
    )

    Column(
        Modifier.fillMaxWidth().animateContentSize(tween(300, easing = FastOutSlowInEasing)).padding(top = 28.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(132.dp)
                    .btnSurface(p, a, b, shadow, flat)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPower
                    ),
                contentAlignment = Alignment.Center
            ) {
                PowerIcon(iconColor, 50.dp)
            }
            // кольцо загрузки: плавно появляется вокруг кнопки и исчезает после подключения
            if (ringAlpha > 0.01f) Ring(p.primary, ringAlpha)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(
                when (status) {
                    VpnStatus.OFF -> R.string.status_off
                    VpnStatus.CONNECTING -> R.string.status_connecting
                    VpnStatus.ON -> R.string.status_on
                }
            ),
            color = if (on) p.primary else p.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
        val sub = when {
            status == VpnStatus.OFF && connError.isNotBlank() -> "Не подключилось: $connError"
            shown == null -> stringResource(R.string.hint_tap)
            status == VpnStatus.OFF && smart -> stringResource(R.string.smart_prefix, shown.name)
            else -> shown.name
        }
        // реальная задержка через туннель, пока она замерена; иначе пинг до сервера
        val liveMs = if (status == VpnStatus.ON && shown != null)
            (tunnelMs.takeIf { it > 0 } ?: shownPing?.takeIf { it > 0 }) else null
        if (liveMs != null && liveMs > 0 && shown != null) {
            Row(
                Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    shown.name + " · ",
                    color = p.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                RollingNumber(liveMs, p.text2, 13.sp, FontWeight.Normal)
                Text(" мс", color = p.text2, fontSize = 13.sp, maxLines = 1)
            }
        } else Text(
            sub,
            color = if (status == VpnStatus.OFF && connError.isNotBlank()) p.primary else p.text2,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Ring(color: Color, alpha: Float) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    Canvas(Modifier.requiredSize(140.dp).graphicsLayer { this.alpha = alpha }) {
        val r = 68.dp.toPx()
        val c = center
        rotateScope(angle, c) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 98f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
internal fun PowerIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val w = 2.2f * s
        drawLine(color, Offset(12f * s, 3f * s), Offset(12f * s, 11f * s), strokeWidth = w, cap = StrokeCap.Round)
        // дуга «кнопки питания»: центр (12; 12.61), радиус 8, разрыв сверху
        drawArc(
            color = color,
            startAngle = 225.6f,
            sweepAngle = -271.2f,
            useCenter = false,
            topLeft = Offset(4f * s, 4.61f * s),
            size = Size(16f * s, 16f * s),
            style = Stroke(w, cap = StrokeCap.Round)
        )
    }
}

// ── умное подключение ────────────────────────────────────────────────────

@Composable
private fun SmartCard(smart: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(p, shape, p.ga, p.gb, 8.dp, if (p.dark) Color(0x99000000) else Color(0x26783C50), p.surface)
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.smart_title),
                color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
            Text(
                stringResource(R.string.smart_desc),
                color = p.text2, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Switch(smart, onToggle)
    }
}

@Composable
private fun Switch(on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val track by animateColorAsState(if (on) p.primary else p.surface, tween(300), label = "track")
    val thumb by animateColorAsState(if (on) p.onPrimary else p.text2, tween(300), label = "thumb")
    val x by animateDpAsState(if (on) 22.dp else 4.dp, tween(300), label = "x")
    Box(
        Modifier
            .size(46.dp, 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
    ) {
        Box(
            Modifier
                .offset(x = x, y = 4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

// ── подписки и серверы ───────────────────────────────────────────────────

@Composable
private fun SubsCard(
    subs: List<Sub>,
    best: Server?,
    current: Server?,
    pings: Map<String, Int>,
    /** Сервер, к которому сейчас подключены: остаётся в списке, даже если пинг не прошёл. */
    keepId: String?,
    scroll: ScrollState,
    top: Dp,
    bottom: Dp,
    onAvatar: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    val p = LocalPalette.current
    val opened by Repo.opened.collectAsState()
    val res = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dens = LocalDensity.current
    val view = LocalView.current
    val gapPx = with(dens) { 10.dp.toPx() }
    // зоны у краёв экрана: если держать подписку там, список сам прокручивается
    val zonePx = with(dens) { 72.dp.toPx() }
    val topLimitPx = with(dens) { (top + 64.dp).toPx() }
    val bottomInsetPx = with(dens) { bottom.toPx() }
    // обычные карты, не состояния: их запись не должна перекомпоновывать экран
    val tops = remember { HashMap<String, Float>() }
    val heights = remember { HashMap<String, Int>() }
    var pointerY by remember { mutableStateOf(0f) }

    // порядок в раскладке НЕ меняется, пока тянем: меняется только при отпускании.
    // Остальные подписки лишь плавно сдвигаются (translationY), поэтому ничего не прыгает.
    val ids = subs.map { it.id }
    val order = remember(ids) { mutableStateListOf<String>().apply { addAll(ids) } }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    // >= 0 — подписка отпущена и доезжает до этой позиции
    var lockTo by remember { mutableStateOf(-1) }
    // момент последнего долгого нажатия/перетаскивания: отпускание пальца после него не должно раскрывать подписку
    val dragStamp = remember { longArrayOf(0L) }

    // куда сейчас встала бы поднятая подписка: сравниваем её середину с серединами соседей
    fun calcTarget(): Int {
        val cur = dragId ?: return -1
        if (lockTo >= 0) return lockTo
        val from = order.indexOf(cur)
        if (from < 0) return -1
        val hd = heights[cur] ?: return from
        var y = 0f
        val t = FloatArray(order.size)
        order.forEachIndexed { i, id ->
            t[i] = y
            y += (heights[id] ?: 0) + gapPx
        }
        val c = t[from] + dragDy + hd / 2f
        var to = from
        for (j in from + 1 until order.size) {
            if (c > t[j] + (heights[order[j]] ?: 0) / 2f) to = j else break
        }
        for (j in from - 1 downTo 0) {
            if (c < t[j] + (heights[order[j]] ?: 0) / 2f) to = j else break
        }
        return to
    }

    val target = remember(order, gapPx) { derivedStateOf { calcTarget() } }

    // на сколько сдвинуть подписку id, пока другую тянут; null — ничего не тянут
    fun shiftFor(id: String): Float? {
        val cur = dragId ?: return null
        if (cur == id) return 0f
        val from = order.indexOf(cur)
        val i = order.indexOf(id)
        val to = target.value
        if (from < 0 || i < 0 || to < 0) return 0f
        val hd = (heights[cur] ?: 0) + gapPx
        return when {
            i in (from + 1)..to -> -hd
            i in to until from -> hd
            else -> 0f
        }
    }

    // смещение поднятой подписки, когда она встала на новое место
    fun finalOffset(from: Int, to: Int): Float {
        var s = 0f
        if (to > from) {
            for (j in from + 1..to) s += (heights[order[j]] ?: 0) + gapPx
        } else if (to < from) {
            for (j in to until from) s -= (heights[order[j]] ?: 0) + gapPx
        }
        return s
    }

    fun drop() {
        val cur = dragId ?: return
        if (lockTo >= 0) return
        val from = order.indexOf(cur)
        val to = target.value.takeIf { it >= 0 } ?: from
        lockTo = to
        val endY = finalOffset(from, to)
        scope.launch {
            animate(dragDy, endY, animationSpec = tween(180, easing = FastOutSlowInEasing)) { v, _ -> dragDy = v }
            // порядок и сброс сдвигов — в одном кадре, поэтому скачка нет
            if (from >= 0 && to != from) {
                order.removeAt(from)
                order.add(to, cur)
            }
            dragId = null
            dragDy = 0f
            lockTo = -1
            Repo.reorderSubs(order.toList())
        }
    }

    // пока тянем — не обновляем пинги на экране, иначе он перерисовывается и дёргается
    val dragging = dragId != null
    LaunchedEffect(dragging) {
        Repo.holdPings = dragging
        if (!dragging) Repo.flushPings()
    }

    LaunchedEffect(dragId) {
        if (dragId == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (lockTo >= 0) continue
            val y = pointerY
            val upEdge = topLimitPx + zonePx
            val downEdge = view.height - bottomInsetPx - zonePx
            val v = when {
                y < upEdge -> -((upEdge - y) / zonePx).coerceIn(0f, 1f)
                y > downEdge -> ((y - downEdge) / zonePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (v != 0f) {
                val moved = scroll.scrollBy(v * 16f)
                // список поехал под пальцем — подписка должна остаться под ним
                if (moved != 0f) dragDy += moved
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (subs.isEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)) {
                Text(
                    stringResource(R.string.empty_subs),
                    color = p.text2, fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp)
                )
            }
        }
        order.toList().forEach { id ->
            val sub = subs.firstOrNull { it.id == id } ?: return@forEach
            key(id) {
                // сдвиг этой подписки, пока тянут другую: плавно едет к нужному месту
                val shift = remember { Animatable(0f) }
                LaunchedEffect(id, order) {
                    snapshotFlow { shiftFor(id) }.collect { v ->
                        if (v == null) shift.snapTo(0f)
                        else shift.animateTo(v, tween(200, easing = FastOutSlowInEasing))
                    }
                }
                val lifted = dragId == id
                val lift by animateFloatAsState(if (lifted) 1.02f else 1f, tween(160), label = "lift")
                // недоступные серверы (пинг не прошёл) скрываются сами и возвращаются, когда снова отвечают
                val shownCount = sub.servers.count { pings[it.id] != -1 || it.id == keepId }
                val closed = sub.id !in opened
                // состояние читаем только в graphicsLayer: во время анимации экран не перекомпонуется
                val rot = animateFloatAsState(if (closed) -90f else 0f, tween(320, easing = FastOutSlowInEasing), label = "chev")
                Column(
                    Modifier
                        .fillMaxWidth()
                        // жест ловим ДО сдвига (graphicsLayer): позиция пальца не зависит от смещения подписки
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    if (dragId == null) {
                                        dragStamp[0] = SystemClock.uptimeMillis()
                                        dragId = id
                                        dragDy = 0f
                                        pointerY = (tops[id] ?: 0f) + start.y
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, amount ->
                                    if (dragId == id && lockTo < 0) {
                                        change.consume()
                                        dragDy += amount.y
                                        pointerY = (tops[id] ?: 0f) + change.position.y
                                    }
                                },
                                onDragEnd = { dragStamp[0] = SystemClock.uptimeMillis(); if (dragId == id) drop() },
                                onDragCancel = { dragStamp[0] = SystemClock.uptimeMillis(); if (dragId == id) drop() },
                            )
                        }
                        .zIndex(if (lifted) 1f else 0f)
                        .onSizeChanged { heights[id] = it.height }
                        .onGloballyPositioned { tops[id] = it.positionInWindow().y }
                        .graphicsLayer {
                            translationY = when {
                                lifted -> dragDy
                                dragId == null -> 0f
                                else -> shift.value
                            }
                            scaleX = lift
                            scaleY = lift
                        }
                        .clip(RoundedCornerShape(20.dp))
                        .background(p.surface)
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // долгое нажатие под перетаскивание и его отпускание — не нажатие на подписку
                                val justDragged = SystemClock.uptimeMillis() - dragStamp[0] < 500
                                if (dragId == null && !justDragged) Repo.toggleOpen(sub.id)
                            }
                            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(sub) { onAvatar(sub.id) }
                        Spacer(Modifier.width(2.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                sub.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            val n = res.getQuantityString(R.plurals.servers, shownCount, shownCount)
                            Text(
                                if (sub.info.isBlank()) n else "$n · ${sub.info}",
                                color = p.text2, fontSize = 12.sp, lineHeight = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(Modifier.graphicsLayer { rotationZ = rot.value }) { PolyIcon(CHEVRON, p.text2, 22.dp) }
                    }
                    Collapsible(expanded = !closed) {
                        Column {
                            // серверы без ответа уходят и возвращаются плавно, а не рывком
                            sub.servers.forEach { s ->
                                key(s.id) {
                                    // лямбда одна на сервер: пока приходят пачки пингов соседей,
                                    // эта строка пропускает рекомпозицию целиком
                                    val pick = remember(s.id) { { onPick(s.id) } }
                                    ServerSlot(
                                        s, s.id == current?.id, s.id == best?.id,
                                        pings[s.id] != -1 || s.id == keepId, pick
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Плавное раскрытие и сворачивание списка. Высота, прозрачность и небольшой сдвиг идут от одного
 * прогресса, поэтому содержимое не пропадает раньше, чем уходит место под ним. Прогресс читается
 * только на этапах измерения и отрисовки: во время анимации ничего не перекомпонуется.
 */
@Composable
private fun Collapsible(expanded: Boolean, content: @Composable () -> Unit) {
    val progress = animateFloatAsState(
        if (expanded) 1f else 0f,
        tween(340, easing = FastOutSlowInEasing),
        label = "collapse"
    )
    val alive by remember { derivedStateOf { progress.value > 0.001f } }
    if (!expanded && !alive) return
    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val k = progress.value
                val h = (placeable.height * k).roundToInt()
                val shift = ((k - 1f) * placeable.height * 0.2f).roundToInt()
                layout(placeable.width, h) { placeable.place(0, shift) }
            }
            .clipToBounds()
            .graphicsLayer {
                alpha = progress.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
    ) { content() }
}

/**
 * Маленькая круглая аватарка рядом с названием подписки. Пока картинки нет — кружок с «+»,
 * нажатие открывает галерею. Область нажатия 44 dp, чтобы попадать пальцем.
 */
@Composable
private fun Avatar(sub: Sub, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bmp by produceState<ImageBitmap?>(null, sub.avatar) {
        value = sub.avatar?.let { name -> withContext(Dispatchers.IO) { Repo.loadAvatar(name) } }
    }
    Box(
        Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (b == null) p.primary.copy(alpha = 0.14f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (b != null) {
                Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                PolyIcon(PLUS, p.primary, 18.dp)
            }
        }
    }
}

/** Строка сервера, которая плавно сворачивается, когда сервер не отвечает, и так же плавно возвращается. */
@Composable
private fun ServerSlot(s: Server, selected: Boolean, best: Boolean, keepVisible: Boolean, onClick: () -> Unit) {
    // пинг из собственного потока сервера: строка пересобирается, только когда изменилось её число,
    // а не все 100 строк дважды в секунду — прокрутка перестаёт лагать
    val ping by Repo.pingFlow(s.id).collectAsState()
    // пока строка уходит, оставляем последний рабочий пинг, а не «—»
    val last = remember { arrayOf(ping) }
    if (ping != null && ping != -1) last[0] = ping
    Collapsible(expanded = keepVisible) {
        ServerRow(s, selected, best, if (ping == -1) last[0] else ping, onClick)
    }
}

@Composable
private fun ServerRow(s: Server, selected: Boolean, best: Boolean, ping: Int?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val nameColor = if (selected) p.primary else p.text
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            s.cc,
            color = if (selected) p.primary else p.text2,
            fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
            modifier = Modifier.width(32.dp)
        )
        Text(
            s.name,
            color = nameColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val pingColor = if (best && (ping ?: -1) > 0) p.primary else p.text2
        if (ping != null && ping > 0) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RollingNumber(ping, pingColor, 12.sp, FontWeight.Medium)
                Text(" мс", color = pingColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        } else {
            Text(
                if (ping == null) "…" else "—",
                color = pingColor,
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) PolyIcon(CHECK, p.primary, 20.dp)
        }
    }
}

// ── иконки: просто знак, без подложек ───────────────────────────────────

private val PLUS = listOf(
    19f to 13f, 13f to 13f, 13f to 19f, 11f to 19f, 11f to 13f, 5f to 13f,
    5f to 11f, 11f to 11f, 11f to 5f, 13f to 5f, 13f to 11f, 19f to 11f
)
private val CHEVRON = listOf(
    7.41f to 8.59f, 12f to 13.17f, 16.59f to 8.59f, 18f to 10f, 12f to 16f, 6f to 10f
)
private val CHECK = listOf(
    9f to 16.17f, 4.83f to 12f, 3.41f to 13.41f, 9f to 19f, 21f to 7f, 19.59f to 5.59f
)

@Composable
private fun PolyIcon(points: List<Pair<Float, Float>>, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val path = Path()
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x * k, y * k) else path.lineTo(x * k, y * k)
        }
        path.close()
        drawPath(path, color)
    }
}


// ── цифры, которые «перекатываются», как в Telegram ─────────────────────

/**
 * Число, у которого меняются только те цифры, что изменились: старая улетает вверх, новая выезжает снизу,
 * когда число растёт; когда уменьшается — наоборот (старая вниз, новая сверху).
 * Разряды считаем справа, поэтому «99 → 100» плавно добавляет сотни слева.
 */
@Composable
private fun RollingNumber(value: Int, color: Color, fontSize: TextUnit, weight: FontWeight) {
    val text = value.toString()
    val last = remember { intArrayOf(value) }
    // направление считаем тут, в момент смены числа; 0 (число то же) цифры не трогает
    val dir = when {
        value > last[0] -> 1
        value < last[0] -> -1
        else -> 0
    }
    SideEffect { last[0] = value }
    val initial = remember { text.length }
    val maxLen = remember { intArrayOf(text.length) }
    if (text.length > maxLen[0]) maxLen[0] = text.length
    val slots = maxLen[0]
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (j in slots - 1 downTo 0) {
            key(j) {
                RollingChar(text.getOrNull(text.length - 1 - j), dir, j >= initial, color, fontSize, weight)
            }
        }
    }
}

@Composable
private fun RollingChar(ch: Char?, dir: Int, enter: Boolean, color: Color, fontSize: TextUnit, weight: FontWeight) {
    val style = TextStyle(color = color, fontSize = fontSize, fontWeight = weight, fontFeatureSettings = "tnum")
    // последняя цифра в слоте: когда разряд исчезает, она ещё тает, а не пропадает рывком
    var shown by remember { mutableStateOf(ch ?: '0') }
    if (ch != null) shown = ch
    val start = (ch ?: '0') - '0'
    // позиция барабана в «развёрнутых» цифрах (7 → 8 → 9 → 10 → 11): так цифры идут по одной, как в одометре
    val pos = remember { Animatable(start.toFloat()) }
    var lo by remember { mutableIntStateOf(start) }
    var hi by remember { mutableIntStateOf(start) }
    // ширина и прозрачность разряда: новый разряд проявляется, лишний тает
    val vis = remember { Animatable(if (ch == null || enter) 0f else 1f) }

    LaunchedEffect(ch == null) {
        vis.animateTo(if (ch == null) 0f else 1f, tween(220, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(ch) {
        if (ch == null) return@LaunchedEffect
        val d = ch - '0'
        val base = pos.value.roundToInt()
        val curDigit = ((base % 10) + 10) % 10
        val delta = when {
            d == curDigit -> 0
            dir >= 0 -> (d - curDigit + 10) % 10          // число растёт — крутим вверх через соседние цифры
            else -> -((curDigit - d + 10) % 10)           // число падает — вниз
        }
        val target = base + delta
        lo = minOf(kotlin.math.floor(pos.value).toInt(), target)
        hi = maxOf(kotlin.math.ceil(pos.value).toInt(), target)
        pos.animateTo(
            target.toFloat(),
            tween((200 + 60 * abs(delta)).coerceAtMost(480), easing = FastOutSlowInEasing)
        )
        // анимация дошла до конца — сворачиваем барабан обратно к одной цифре
        val norm = ((target % 10) + 10) % 10
        pos.snapTo(norm.toFloat())
        lo = norm
        hi = norm
    }

    Box(
        Modifier
            .layout { m, c ->
                val pl = m.measure(c)
                val w = (pl.width * vis.value).roundToInt()
                layout(w, pl.height) { pl.placeRelative(0, 0) }
            }
            .graphicsLayer { alpha = vis.value },
        contentAlignment = Alignment.Center
    ) {
        // невидимая «0» задаёт размер слота
        Text("0", style = style.copy(color = Color.Transparent), maxLines = 1)
        if (ch == null) {
            Text(shown.toString(), style = style, maxLines = 1)
        } else {
            for (k in lo..hi) {
                val digit = (((k % 10) + 10) % 10).toString()
                Text(
                    digit, style = style, maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        val off = k - pos.value
                        translationY = off * size.height
                        // соседние цифры плавно гаснут, ничего не обрезается
                        alpha = (1f - abs(off)).coerceIn(0f, 1f)
                    }
                )
            }
        }
    }
}
