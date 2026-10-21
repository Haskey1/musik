package app.musik.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role

/** Warm dark palette of the web UI (style.css): bg #100c0a, accent #e07a3a, teal #3d8f7a. */
object MusikColors {
    val background = Color(0xFF100C0A)
    val surface = Color(0xFF1C1512)
    val surfaceHigh = Color(0xFF271D18)
    val accent = Color(0xFFE07A3A)
    val accentBright = Color(0xFFF29A55)
    val teal = Color(0xFF3D8F7A)
    val muted = Color(0xFFA89C94)

    fun placeholder(seed: Int): List<Color> {
        val palettes = listOf(
            listOf(Color(0xFF6B3A1F), Color(0xFF2A1810)),
            listOf(Color(0xFF2F5C50), Color(0xFF14221E)),
            listOf(Color(0xFF7A4A2A), Color(0xFF3D2215)),
            listOf(Color(0xFF3B3050), Color(0xFF1A1524)),
        )
        return palettes[Math.floorMod(seed, palettes.size)]
    }
}

private val scheme = darkColorScheme(
    primary = MusikColors.accent,
    onPrimary = Color.Black,
    secondary = MusikColors.teal,
    background = MusikColors.background,
    surface = MusikColors.surface,
    surfaceVariant = MusikColors.surfaceHigh,
    surfaceContainer = MusikColors.surface,
    surfaceContainerHigh = MusikColors.surfaceHigh,
    surfaceContainerHighest = MusikColors.surfaceHigh,
    onSurface = Color.White,
    onSurfaceVariant = MusikColors.muted,
    onBackground = Color.White,
)

@Composable
fun MusikTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        // No Surface wraps the app (the backdrop is drawn by hand), so text would default to black.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

fun displayStyle(size: Int) = TextStyle(fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)

/** The app background: dark with warm and teal glows, as on the web. */
fun Modifier.backdrop(): Modifier = this
    .background(MusikColors.background)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(MusikColors.accent.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width, 0f), radius = 420.dp.toPx(),
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(MusikColors.teal.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(0f, size.height), radius = 480.dp.toPx(),
            ),
        )
    }

@Composable
fun Backdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().backdrop()) { content() }
}

/** Capsule button with the accent gradient (the main action). */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    PillButton(
        onClick, modifier, enabled,
        background = Brush.linearGradient(listOf(MusikColors.accentBright, MusikColors.accent)),
        border = null, contentColor = Color.Black.copy(alpha = 0.85f), content = content,
    )
}

/** Dark capsule chip; `active` lights it in the accent colour. */
@Composable
fun ChipButton(onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    PillButton(
        onClick, modifier, enabled,
        background = Brush.linearGradient(
            listOf(
                if (active) MusikColors.accent.copy(alpha = 0.22f) else MusikColors.surfaceHigh,
                if (active) MusikColors.accent.copy(alpha = 0.22f) else MusikColors.surfaceHigh,
            ),
        ),
        border = if (active) MusikColors.accent else Color.White.copy(alpha = 0.08f),
        contentColor = if (active) MusikColors.accentBright else Color.White, content = content,
    )
}

/** Frosted white chip for the cover-coloured player background. */
@Composable
fun GlassChip(onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val fill = Color.White.copy(alpha = if (active) 0.3f else 0.12f)
    PillButton(
        onClick, modifier, enabled,
        background = Brush.linearGradient(listOf(fill, fill)),
        border = Color.White.copy(alpha = if (active) 0.55f else 0.14f),
        contentColor = Color.White, content = content,
    )
}

@Composable
private fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    background: Brush,
    border: Color?,
    contentColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .scale(if (pressed) 0.97f else 1f)
            .alpha(if (!enabled) 0.45f else if (pressed) 0.8f else 1f)
            .background(background, CircleShape)
            .let { if (border != null) it.border(1.dp, border, CircleShape) else it }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) {
                content()
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MusikColors.muted, style = MaterialTheme.typography.bodyMedium)
}


/** Tab screens scroll under a translucent strip, so content doesn't run under the clock. */
@Composable
fun StatusBarScrim() {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(MusikColors.background.copy(alpha = 0.92f)),
    )
}
