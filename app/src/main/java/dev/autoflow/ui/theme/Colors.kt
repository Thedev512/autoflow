package dev.autoflow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.autoflow.core.blocks.BlockSpec
import dev.autoflow.core.blocks.Category

/** Fixed category colors, tuned to read on both light and dark surfaces. */
object BlockColors {
    private val triggerLight = Color(0xFFE08600)
    private val triggerDark = Color(0xFFFFB648)
    private val actionLight = Color(0xFF2F6FE4)
    private val actionDark = Color(0xFF8AB4FF)
    private val logicLight = Color(0xFF7A4FD6)
    private val logicDark = Color(0xFFC0A6FF)
    private val flowLight = Color(0xFF1E9E57)
    private val flowDark = Color(0xFF6FDC9C)

    @Composable
    @ReadOnlyComposable
    fun isDark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    @Composable
    @ReadOnlyComposable
    fun of(category: Category): Color {
        val dark = isDark()
        return when (category) {
            Category.TRIGGER -> if (dark) triggerDark else triggerLight
            Category.ACTION -> if (dark) actionDark else actionLight
            Category.LOGIC -> if (dark) logicDark else logicLight
        }
    }

    @Composable
    @ReadOnlyComposable
    fun of(spec: BlockSpec?): Color {
        if (spec == null) return MaterialTheme.colorScheme.error
        if (spec.type == "flow.start" || spec.type == "flow.stop") return if (isDark()) flowDark else flowLight
        return of(spec.category)
    }

    @Composable
    @ReadOnlyComposable
    fun port(portId: String, fallback: Color): Color {
        val dark = isDark()
        return when (portId) {
            "yes", "ok" -> if (dark) flowDark else flowLight
            "no", "fail", "cancel" -> if (dark) Color(0xFFFF8A80) else Color(0xFFD32F2F)
            else -> fallback
        }
    }
}

/** Used when dynamic color isn't available (Android 11 and older, and the desktop preview). */
val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF4A5BD4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF00105B),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFF77536D),
    tertiaryContainer = Color(0xFFFFD7F1),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE3E1EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF1A2A94),
    primaryContainer = Color(0xFF3242BB),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF434659),
    tertiary = Color(0xFFE6BAD7),
    tertiaryContainer = Color(0xFF5D3C55),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF29292F),
    surfaceContainerHighest = Color(0xFF34343A),
)
