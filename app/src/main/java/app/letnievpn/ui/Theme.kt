package app.letnievpn.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.letnievpn.data.Repo

/** Палитра 1 в 1 из прототипа letnievpn.html (светлая и тёмная). */
data class Palette(
    val dark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val bg: Color,
    val surface: Color,
    val text: Color,
    val text2: Color,
    val ga: Color,
    val gb: Color,
    val rimHi: Color,
    val rimLo: Color,
)

val LightPalette = Palette(
    dark = false,
    primary = Color(0xFFD4527F),
    onPrimary = Color(0xFFFFFFFF),
    bg = Color(0xFFFBF4F5),
    surface = Color(0xFFF3E8EB),
    text = Color(0xFF25181C),
    text2 = Color(0xFF85727A),
    ga = Color(0xFFF2E2E7),
    gb = Color(0xFFFFFDFD),
    rimHi = Color(0xFFFFFFFF),
    rimLo = Color(0x99FFFFFF),
)

val DarkPalette = Palette(
    dark = true,
    primary = Color(0xFFF4A6C0),
    onPrimary = Color(0xFF2A0E18),
    bg = Color(0xFF111111),
    surface = Color(0xFF1C1C1C),
    text = Color(0xFFEDEDED),
    text2 = Color(0xFF9A9A9A),
    ga = Color(0xFF2E2E2E),
    gb = Color(0xFF191919),
    rimHi = Color(0x1FFFFFFF),
    rimLo = Color(0x05FFFFFF),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun LetnieTheme(content: @Composable () -> Unit) {
    val mode by Repo.themeMode.collectAsState()
    val sysDark = isSystemInDarkTheme()
    // -1 — как в системе, 0 — светлая, любое другое (в том числе старая «ночная») — тёмная
    val dark = when (mode) {
        -1 -> sysDark
        0 -> false
        else -> true
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.primary, background = p.bg, surface = p.surface, onSurface = p.text)
    } else {
        lightColorScheme(primary = p.primary, background = p.bg, surface = p.surface, onSurface = p.text)
    }
    // значки статус-бара и навигации читаются в обеих темах, даже если тема выбрана вручную
    val view = LocalView.current
    SideEffect {
        var c: Context? = view.context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window?.let { w ->
            val ctl = WindowCompat.getInsetsController(w, view)
            ctl.isAppearanceLightStatusBars = !p.dark
            ctl.isAppearanceLightNavigationBars = !p.dark
        }
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
