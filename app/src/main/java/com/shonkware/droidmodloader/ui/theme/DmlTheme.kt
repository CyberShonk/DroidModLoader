package com.shonkware.droidmodloader.ui.theme

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

enum class DmlThemeId {
    CarbonRed,
    PipBoyGreen,
    SkyrimNordic,
    OblivionGold,
    FnvAmber
}

data class DmlPalette(
    val background: Color,
    val backgroundRaised: Color,

    val surface: Color,
    val surfaceRaised: Color,
    val surfacePanel: Color,
    val buttonSurface: Color,

    val borderDim: Color,
    val borderHot: Color,
    val glow: Color,

    val primary: Color,
    val primarySoft: Color,
    val primaryDark: Color,
    val primaryButton: Color,

    val amber: Color,
    val green: Color,

    val text: Color,
    val textMuted: Color,
    val textDim: Color
)

object DmlPalettes {
    val CarbonRed = DmlPalette(
        background = Color(0xFF050608),
        backgroundRaised = Color(0xFF0A0C10),

        surface = Color(0xFF101319),
        surfaceRaised = Color(0xFF171B23),
        surfacePanel = Color(0xFF202530),
        buttonSurface = Color(0xFF232934),

        borderDim = Color(0xFF343B49),
        borderHot = Color(0xFFD43A35),
        glow = Color(0xFFFF312A),

        primary = Color(0xFFE0413A),
        primarySoft = Color(0xFFB73531),
        primaryDark = Color(0xFF691819),
        primaryButton = Color(0xFF8F2020),

        amber = Color(0xFFE0A13A),
        green = Color(0xFF6EBB75),

        text = Color(0xFFF0E7D8),
        textMuted = Color(0xFFB8B0A2),
        textDim = Color(0xFF827B70)
    )

    val PipBoyGreen = DmlPalette(
        background = Color(0xFF020703),
        backgroundRaised = Color(0xFF071008),

        surface = Color(0xFF0B150D),
        surfaceRaised = Color(0xFF102014),
        surfacePanel = Color(0xFF162A1A),
        buttonSurface = Color(0xFF1B3420),

        borderDim = Color(0xFF304238),
        borderHot = Color(0xFF48D66B),
        glow = Color(0xFF61FF7D),

        primary = Color(0xFF65F07C),
        primarySoft = Color(0xFF45B95A),
        primaryDark = Color(0xFF174821),
        primaryButton = Color(0xFF216B31),

        amber = Color(0xFFD8C96B),
        green = Color(0xFF65F07C),

        text = Color(0xFFD9FFD7),
        textMuted = Color(0xFFA9C9A6),
        textDim = Color(0xFF6F846E)
    )

    val SkyrimNordic = DmlPalette(
        background = Color(0xFF05070A),
        backgroundRaised = Color(0xFF0B1015),

        surface = Color(0xFF111820),
        surfaceRaised = Color(0xFF18222D),
        surfacePanel = Color(0xFF22303C),
        buttonSurface = Color(0xFF263542),

        borderDim = Color(0xFF3A4856),
        borderHot = Color(0xFF8FB6D9),
        glow = Color(0xFFA7D8FF),

        primary = Color(0xFFAED2EC),
        primarySoft = Color(0xFF7EAACB),
        primaryDark = Color(0xFF1E3B52),
        primaryButton = Color(0xFF2B5878),

        amber = Color(0xFFD1B06A),
        green = Color(0xFF83C995),

        text = Color(0xFFE8EEF2),
        textMuted = Color(0xFFB4C0C8),
        textDim = Color(0xFF7D8A94)
    )

    val OblivionGold = DmlPalette(
        background = Color(0xFF080604),
        backgroundRaised = Color(0xFF120D08),

        surface = Color(0xFF17110A),
        surfaceRaised = Color(0xFF21180D),
        surfacePanel = Color(0xFF2B2113),
        buttonSurface = Color(0xFF352819),

        borderDim = Color(0xFF4B3B24),
        borderHot = Color(0xFFD9A943),
        glow = Color(0xFFFFC85A),

        primary = Color(0xFFE3B24E),
        primarySoft = Color(0xFFB88936),
        primaryDark = Color(0xFF604113),
        primaryButton = Color(0xFF8A5F1F),

        amber = Color(0xFFE3B24E),
        green = Color(0xFF90BD73),

        text = Color(0xFFF3E7D1),
        textMuted = Color(0xFFC7B99D),
        textDim = Color(0xFF8D806A)
    )

    val FnvAmber = DmlPalette(
        background = Color(0xFF070503),
        backgroundRaised = Color(0xFF100B05),

        surface = Color(0xFF15100A),
        surfaceRaised = Color(0xFF21170C),
        surfacePanel = Color(0xFF2B1F10),
        buttonSurface = Color(0xFF332612),

        borderDim = Color(0xFF4A3920),
        borderHot = Color(0xFFE08B2D),
        glow = Color(0xFFFF9C30),

        primary = Color(0xFFF1993A),
        primarySoft = Color(0xFFC77424),
        primaryDark = Color(0xFF5A2C0A),
        primaryButton = Color(0xFF8E4611),

        amber = Color(0xFFF1993A),
        green = Color(0xFF8CCB73),

        text = Color(0xFFF2DFC1),
        textMuted = Color(0xFFC2A984),
        textDim = Color(0xFF877459)
    )

    fun forTheme(themeId: DmlThemeId): DmlPalette {
        return when (themeId) {
            DmlThemeId.CarbonRed -> CarbonRed
            DmlThemeId.PipBoyGreen -> PipBoyGreen
            DmlThemeId.SkyrimNordic -> SkyrimNordic
            DmlThemeId.OblivionGold -> OblivionGold
            DmlThemeId.FnvAmber -> FnvAmber
        }
    }
}

val LocalDmlPalette = staticCompositionLocalOf {
    DmlPalettes.CarbonRed
}

object DmlColors {
    val Background: Color
        @Composable get() = LocalDmlPalette.current.background

    val BackgroundRaised: Color
        @Composable get() = LocalDmlPalette.current.backgroundRaised

    val Surface: Color
        @Composable get() = LocalDmlPalette.current.surface

    val SurfaceRaised: Color
        @Composable get() = LocalDmlPalette.current.surfaceRaised

    val SurfacePanel: Color
        @Composable get() = LocalDmlPalette.current.surfacePanel

    val ButtonSurface: Color
        @Composable get() = LocalDmlPalette.current.buttonSurface

    val BorderDim: Color
        @Composable get() = LocalDmlPalette.current.borderDim

    val BorderHot: Color
        @Composable get() = LocalDmlPalette.current.borderHot

    val Glow: Color
        @Composable get() = LocalDmlPalette.current.glow

    val Red: Color
        @Composable get() = LocalDmlPalette.current.primary

    val RedSoft: Color
        @Composable get() = LocalDmlPalette.current.primarySoft

    val RedDark: Color
        @Composable get() = LocalDmlPalette.current.primaryDark

    val RedButton: Color
        @Composable get() = LocalDmlPalette.current.primaryButton

    val Amber: Color
        @Composable get() = LocalDmlPalette.current.amber

    val Green: Color
        @Composable get() = LocalDmlPalette.current.green

    val Text: Color
        @Composable get() = LocalDmlPalette.current.text

    val TextMuted: Color
        @Composable get() = LocalDmlPalette.current.textMuted

    val TextDim: Color
        @Composable get() = LocalDmlPalette.current.textDim
}

private fun buildDmlColorScheme(palette: DmlPalette) = darkColorScheme(
    primary = palette.primary,
    onPrimary = Color.White,

    secondary = palette.amber,
    onSecondary = Color(0xFF1A1204),

    tertiary = palette.green,
    onTertiary = Color(0xFF061107),

    background = palette.background,
    onBackground = palette.text,

    surface = palette.surface,
    onSurface = palette.text,

    surfaceVariant = palette.surfaceRaised,
    onSurfaceVariant = palette.textMuted,

    outline = palette.borderHot,
    outlineVariant = palette.borderDim,

    error = Color(0xFFFF6B64),
    onError = Color(0xFF2A0303)
)

private val DmlShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun DmlTheme(
    themeId: DmlThemeId = DmlThemeId.CarbonRed,
    content: @Composable () -> Unit
) {
    val palette = DmlPalettes.forTheme(themeId)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = palette.background.toArgb()
        }
    }

    CompositionLocalProvider(
        LocalDmlPalette provides palette
    ) {
        MaterialTheme(
            colorScheme = buildDmlColorScheme(palette),
            typography = MaterialTheme.typography,
            shapes = DmlShapes,
            content = content
        )
    }
}

@Composable
fun DmlMatteBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = LocalDmlPalette.current
    val density = LocalDensity.current
    val speckleStep = with(density) { 11.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        palette.backgroundRaised,
                        palette.background,
                        Color.Black
                    )
                )
            )

            drawCircle(
                color = palette.glow.copy(alpha = 0.10f),
                radius = size.width * 0.55f,
                center = Offset(size.width * 0.18f, size.height * 0.08f)
            )

            drawCircle(
                color = palette.glow.copy(alpha = 0.05f),
                radius = size.width * 0.75f,
                center = Offset(size.width * 0.90f, size.height * 0.35f)
            )

            var y = 0f
            var row = 0

            while (y < size.height) {
                var x = 0f
                var col = 0

                while (x < size.width) {
                    val visible = ((row * 17 + col * 31) % 9) == 0

                    if (visible) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.018f),
                            radius = 0.75f,
                            center = Offset(x, y)
                        )
                    }

                    x += speckleStep
                    col++
                }

                y += speckleStep
                row++
            }
        }

        content()
    }
}

object DmlDefaults {
    @Composable
    fun panelCardColors() = CardDefaults.cardColors(
        containerColor = DmlColors.Surface,
        contentColor = DmlColors.Text
    )

    @Composable
    fun raisedCardColors() = CardDefaults.cardColors(
        containerColor = DmlColors.SurfaceRaised,
        contentColor = DmlColors.Text
    )
}

@Composable
fun DmlGlowPanel(
    modifier: Modifier = Modifier,
    hot: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = LocalDmlPalette.current
    val borderColor = if (hot) palette.borderHot else palette.borderDim
    val glowAlpha = if (hot) 0.26f else 0.08f

    Box(
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    color = palette.glow.copy(alpha = glowAlpha),
                    topLeft = Offset(-6f, -6f),
                    size = Size(size.width + 12f, size.height + 12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f),
                    style = Stroke(width = 10f)
                )
            }
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(
                containerColor = palette.surface,
                contentColor = palette.text
            ),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.025f),
                                    Color.Transparent
                                )
                            )
                        )
                    }
            ) {
                content()
            }
        }
    }
}
object DmlButtons {
    @Composable
    fun Primary(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true
    ) {
        val palette = LocalDmlPalette.current

        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .heightIn(min = 48.dp)
                .border(
                    width = 1.dp,
                    color = if (enabled) palette.borderHot else palette.borderDim,
                    shape = MaterialTheme.shapes.medium
                ),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) palette.primaryButton else palette.surfacePanel,
                contentColor = palette.text,
                disabledContainerColor = palette.surfacePanel,
                disabledContentColor = palette.textDim
            ),
            contentPadding = PaddingValues(
                horizontal = 14.dp,
                vertical = 10.dp
            )
        ) {
            Text(text)
        }
    }

    @Composable
    fun Secondary(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true
    ) {
        val palette = LocalDmlPalette.current

        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .heightIn(min = 46.dp)
                .drawBehind {
                    if (enabled) {
                        drawRoundRect(
                            color = palette.glow.copy(alpha = 0.08f),
                            topLeft = Offset(-3f, -3f),
                            size = Size(size.width + 6f, size.height + 6f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                            style = Stroke(width = 5f)
                        )
                    }
                },
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (enabled) palette.buttonSurface else palette.surfacePanel,
                contentColor = if (enabled) palette.text else palette.textDim,
                disabledContainerColor = palette.surfacePanel,
                disabledContentColor = palette.textDim
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (enabled) palette.borderDim else palette.surfacePanel
            ),
            contentPadding = PaddingValues(
                horizontal = 14.dp,
                vertical = 9.dp
            )
        ) {
            Text(text)
        }
    }

    @Composable
    fun Danger(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true
    ) {
        val palette = LocalDmlPalette.current

        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .heightIn(min = 48.dp)
                .border(
                    width = 1.dp,
                    color = if (enabled) Color(0xFFFF4A42) else palette.borderDim,
                    shape = MaterialTheme.shapes.medium
                ),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) palette.primaryDark else palette.surfacePanel,
                contentColor = palette.text,
                disabledContainerColor = palette.surfacePanel,
                disabledContentColor = palette.textDim
            ),
            contentPadding = PaddingValues(
                horizontal = 14.dp,
                vertical = 10.dp
            )
        ) {
            Text(text)
        }
    }
}