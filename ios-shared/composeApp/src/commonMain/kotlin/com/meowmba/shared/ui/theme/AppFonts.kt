package com.meowmba.shared.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Use platform font families so the iOS release does not need bundled fonts. */
@Composable
internal fun rememberAndroidSans(): FontFamily = FontFamily.SansSerif

@Composable
internal fun rememberAndroidSerif(): FontFamily = FontFamily.Serif

/** [Typography] with system families; Professional's large headlines use serif. */
@Composable
internal fun rememberAppTypography(professional: Boolean): Typography {
    val sans = rememberAndroidSans()
    val serif = rememberAndroidSerif()
    return remember(sans, serif, professional) {
        fun TextStyle.sans() = if (fontFamily == FontFamily.SansSerif || fontFamily == null) copy(fontFamily = sans) else this
        val base = Typography.run {
            copy(
                displayLarge = displayLarge.sans(), displayMedium = displayMedium.sans(), displaySmall = displaySmall.sans(),
                headlineLarge = headlineLarge.sans(), headlineMedium = headlineMedium.sans(), headlineSmall = headlineSmall.sans(),
                titleLarge = titleLarge.sans(), titleMedium = titleMedium.sans(), titleSmall = titleSmall.sans(),
                bodyLarge = bodyLarge.sans(), bodyMedium = bodyMedium.sans(), bodySmall = bodySmall.sans(),
                labelLarge = labelLarge.sans(), labelMedium = labelMedium.sans(), labelSmall = labelSmall.sans(),
            )
        }
        if (professional) base.copy(
            headlineLarge = base.headlineLarge.copy(fontFamily = serif),
            headlineMedium = base.headlineMedium.copy(fontFamily = serif),
        ) else base
    }
}
