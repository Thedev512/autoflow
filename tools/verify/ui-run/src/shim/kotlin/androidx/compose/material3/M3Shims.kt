package androidx.compose.material3

// The only Compose Desktop build whose dependencies are all on Maven Central ships Material3 1.1,
// so this file back-fills the few Material3 1.2+ APIs the app uses. It exists only in this
// preview tool; the real app gets them from androidx Material3 1.3.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun ColorScheme.tone(f: Float) = lerp(surface, surfaceVariant, f)
val ColorScheme.surfaceContainerLowest: Color get() = lerp(surface, background, 0.5f).let { if (isDark()) lerp(it, Color.Black, 0.25f) else lerp(it, Color.White, 0.6f) }
val ColorScheme.surfaceContainerLow: Color get() = tone(0.18f)
val ColorScheme.surfaceContainer: Color get() = tone(0.28f)
val ColorScheme.surfaceContainerHigh: Color get() = tone(0.40f)
val ColorScheme.surfaceContainerHighest: Color get() = tone(0.55f)
private fun ColorScheme.isDark() = (surface.red + surface.green + surface.blue) < 1.5f

fun lightColorScheme(
    primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, secondaryContainer: Color, tertiary: Color, tertiaryContainer: Color,
    background: Color, surface: Color, surfaceVariant: Color,
    surfaceContainerLowest: Color, surfaceContainerLow: Color, surfaceContainer: Color,
    surfaceContainerHigh: Color, surfaceContainerHighest: Color,
): ColorScheme = lightColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, secondaryContainer = secondaryContainer, tertiary = tertiary, tertiaryContainer = tertiaryContainer,
    background = background, surface = surface, surfaceVariant = surfaceVariant,
)

fun darkColorScheme(
    primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, secondaryContainer: Color, tertiary: Color, tertiaryContainer: Color,
    background: Color, surface: Color, surfaceVariant: Color,
    surfaceContainerLowest: Color, surfaceContainerLow: Color, surfaceContainer: Color,
    surfaceContainerHigh: Color, surfaceContainerHighest: Color,
): ColorScheme = darkColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, secondaryContainer = secondaryContainer, tertiary = tertiary, tertiaryContainer = tertiaryContainer,
    background = background, surface = surface, surfaceVariant = surfaceVariant,
)

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = MaterialTheme.colorScheme.outlineVariant) =
    Divider(modifier, thickness, color)

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = MaterialTheme.colorScheme.outlineVariant) =
    Box(modifier.fillMaxHeight().width(thickness).background(color))

interface SingleChoiceSegmentedButtonRowScope : RowScope

private class SegScope(val row: RowScope) : SingleChoiceSegmentedButtonRowScope, RowScope by row

@Composable
fun SingleChoiceSegmentedButtonRow(modifier: Modifier = Modifier, content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit) {
    Row(modifier.height(40.dp)) { SegScope(this).content() }
}

object SegmentedButtonDefaults {
    fun itemShape(index: Int, count: Int): Shape = when {
        count == 1 -> RoundedCornerShape(50)
        index == 0 -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        index == count - 1 -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
        else -> RoundedCornerShape(0)
    }
}

@Composable
fun SingleChoiceSegmentedButtonRowScope.SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = modifier.weight(1f),
    ) { label() }
}
