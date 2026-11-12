package com.meowmba.shared.ui.flow

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.flow.FlowPalette

/**
 * Meow Flow visual tokens — docs/design/README.md is the source of truth.
 * 1 CSS px in the approved demo = 1 dp; font px = sp.
 *
 * Typography uses platform system fonts.
 */
object FlowFonts {
    val serif: FontFamily
        @androidx.compose.runtime.Composable get() = FontFamily.Serif
    val sans: FontFamily
        @androidx.compose.runtime.Composable get() = FontFamily.SansSerif
}

@Immutable
data class FlowColors(
    val paper: Color, val ink: Color, val ink2: Color, val ink3: Color,
    val rule: Color, val accent: Color, val onAccent: Color,
) {
    companion object {
        fun from(p: FlowPalette) = FlowColors(
            Color(p.paper), Color(p.ink), Color(p.ink2), Color(p.ink3), Color(p.rule), Color(p.accent), Color(p.onAccent),
        )
    }
}

val LocalFlowColors = staticCompositionLocalOf<FlowColors> { error("FlowColors not provided") }

internal object FlowType {
    val status @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    val dateline @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
        lineHeight = 20.sp, letterSpacing = (0.9 / 12).em)
    val label @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
        letterSpacing = (1.2 / 12).em)
    val headline @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 31.sp,
        lineHeight = 37.sp, letterSpacing = (-0.4 / 31).em)
    val headlineCompact @androidx.compose.runtime.Composable get() = headline.copy(fontSize = 25.sp, lineHeight = 31.sp)
    val because @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.serif, fontStyle = FontStyle.Italic, fontSize = 17.sp, lineHeight = 25.sp)
    val button @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    val link @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val itemTitle @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
    val itemDetail @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontSize = 13.5.sp, lineHeight = 19.sp)
    val itemAction @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    val held @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp,
        letterSpacing = (1.0 / 11.5).em)
    val ask @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.serif, fontStyle = FontStyle.Italic, fontSize = 17.sp)
    val bandHeadline @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.serif, fontWeight = FontWeight.SemiBold, fontSize = 27.sp,
        lineHeight = 33.sp, letterSpacing = (-0.3 / 27).em)
    val bandBody @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontSize = 15.sp, lineHeight = 22.sp)
    val bandVoice @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 21.sp)
    val distance @androidx.compose.runtime.Composable get() = TextStyle(fontFamily = FlowFonts.sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = (0.5 / 13).em)
}

internal object FlowDims {
    val statusHeight = 40.dp
    val sidePadStart = 28.dp
    val sidePadEnd = 26.dp
    val streamTop = 52.dp
    val streamBottom = 112.dp
    val askBottom = 34.dp
    val askHeight = 56.dp
    val micSize = 46.dp
    val buttonHeight = 46.dp
    val radius = 4.dp
    val dotSize = 7.dp
    /** Distance from the item's text edge to its leader-line dot. */
    val dotInset = 22.dp
    val bandTopPad = 52.dp
    val bandSidePad = 28.dp
    val bandBottomPad = 22.dp
    const val THEME_ANIM_MS = 900
    const val BAND_ANIM_MS = 450
    const val LINE_DRAW_MS = 800
    const val LINE_STAGGER_ITEM_MS = 120
    const val LINE_STAGGER_SOURCE_MS = 80
    const val TRAVEL_MS = 900
    const val ITEM_IN_MS = 600
    const val ITEM_STAGGER_MS = 100
    /** Dateline signals shrink from 12 sp in 0.5 sp steps to this minimum, then ellipsize. */
    const val DATELINE_MIN_SP = 10f
}
