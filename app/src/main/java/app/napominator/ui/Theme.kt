package app.napominator.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Дополнительные цвета дизайна, которых нет в Material 3. */
data class Ext(
    val accentSoft: Color, val accentInk: Color,
    val ok: Color, val okSoft: Color,
    val miss: Color, val missSoft: Color,
    val warn: Color, val warnSoft: Color,
    val line: Color, val surface2: Color, val muted: Color,
    val crit: Color,
)

val LocalExt = staticCompositionLocalOf<Ext> { error("no theme") }
val ext: Ext @Composable get() = LocalExt.current

private val lightExt = Ext(
    accentSoft = Color(0xFFD3EAE5), accentInk = Color(0xFF0A4D47),
    ok = Color(0xFF1D6F3B), okSoft = Color(0xFFD8EDDD),
    miss = Color(0xFF9C3526), missSoft = Color(0xFFF5DBD5),
    warn = Color(0xFF7A4F00), warnSoft = Color(0xFFF7E7BE),
    line = Color(0xFFDAD6CB), surface2 = Color(0xFFEEEBE3), muted = Color(0xFF59615E),
    crit = Color(0xFFB93C0B),
)
private val darkExt = Ext(
    accentSoft = Color(0xFF1C3A36), accentInk = Color(0xFFA8EBE1),
    ok = Color(0xFF78D698), okSoft = Color(0xFF1B3626),
    miss = Color(0xFFF2A394), missSoft = Color(0xFF43241E),
    warn = Color(0xFFF0CB77), warnSoft = Color(0xFF3F3213),
    line = Color(0xFF2F3835), surface2 = Color(0xFF242B28), muted = Color(0xFFA5AFAB),
    crit = Color(0xFFFF8F5E),
)

private val light: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F6E66), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EAE5), onPrimaryContainer = Color(0xFF0A4D47),
    secondaryContainer = Color(0xFFD3EAE5), onSecondaryContainer = Color(0xFF0A4D47),
    background = Color(0xFFF6F4EF), onBackground = Color(0xFF1B1F1E),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1B1F1E),
    surfaceVariant = Color(0xFFEEEBE3), onSurfaceVariant = Color(0xFF59615E),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerLowest = Color(0xFFF6F4EF),
    outline = Color(0xFF8A908D), outlineVariant = Color(0xFFDAD6CB),
    error = Color(0xFF9C3526), onError = Color.White,
)
private val dark: ColorScheme = darkColorScheme(
    primary = Color(0xFF5CCFC0), onPrimary = Color(0xFF06201C),
    primaryContainer = Color(0xFF1C3A36), onPrimaryContainer = Color(0xFFA8EBE1),
    secondaryContainer = Color(0xFF1C3A36), onSecondaryContainer = Color(0xFFA8EBE1),
    background = Color(0xFF101412), onBackground = Color(0xFFECEFED),
    surface = Color(0xFF1A201E), onSurface = Color(0xFFECEFED),
    surfaceVariant = Color(0xFF242B28), onSurfaceVariant = Color(0xFFA5AFAB),
    surfaceContainer = Color(0xFF1A201E), surfaceContainerHigh = Color(0xFF242B28), surfaceContainerLowest = Color(0xFF101412),
    outline = Color(0xFF7B8581), outlineVariant = Color(0xFF2F3835),
    error = Color(0xFFF2A394), onError = Color(0xFF3B0D04),
)

@Composable
fun NapominatorTheme(mode: String, content: @Composable () -> Unit) {
    val isDark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var c = view.context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !isDark; isAppearanceLightNavigationBars = !isDark }
        }
    }
    CompositionLocalProvider(LocalExt provides if (isDark) darkExt else lightExt) {
        MaterialTheme(colorScheme = if (isDark) dark else light, content = content)
    }
}
