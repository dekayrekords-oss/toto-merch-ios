package com.meowmba.shared.ui.theme
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
val ProfessionalDark = darkColorScheme(
    primary = Color(0xFF8AD2CB), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF174F4D), onPrimaryContainer = Color(0xFFC1F2EB),
    secondary = Color(0xFFD9C7AC), onSecondary = Color(0xFF302A20),
    secondaryContainer = Color(0xFF443C30), onSecondaryContainer = Color(0xFFF2DFC1),
    tertiary = Color(0xFFB2C7A3), onTertiary = Color(0xFF23341D),
    tertiaryContainer = Color(0xFF35472D), onTertiaryContainer = Color(0xFFD2E6C3),
    background = Color(0xFF101719), onBackground = Color(0xFFE3E9E7),
    surface = Color(0xFF151E20), onSurface = Color(0xFFE3E9E7),
    surfaceContainerLowest = Color(0xFF0C1214), surfaceContainerLow = Color(0xFF151E20),
    surfaceContainer = Color(0xFF1A2426), surfaceContainerHigh = Color(0xFF233033),
    surfaceContainerHighest = Color(0xFF2D3C3E),
    surfaceVariant = Color(0xFF233033), onSurfaceVariant = Color(0xFFB8C8C7),
    outline = Color(0xFF849795), outlineVariant = Color(0xFF374A4C),
)
val ProfessionalLight = lightColorScheme(
    primary = Color(0xFF205D60), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEDE9), onPrimaryContainer = Color(0xFF163F41),
    secondary = Color(0xFF705E43), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E1CB), onSecondaryContainer = Color(0xFF302719),
    tertiary = Color(0xFF4B6741), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFECD4), onTertiaryContainer = Color(0xFF203319),
    background = Color(0xFFF8F6F2), onBackground = Color(0xFF202A2B),
    surface = Color(0xFFFFFDFA), onSurface = Color(0xFF202A2B),
    surfaceContainerLowest = Color(0xFFFFFDFA), surfaceContainerLow = Color(0xFFF5F3EE),
    surfaceContainer = Color(0xFFEFEFE8), surfaceContainerHigh = Color(0xFFE7EBE5),
    surfaceContainerHighest = Color(0xFFDFE5DF),
    surfaceVariant = Color(0xFFECEEE9), onSurfaceVariant = Color(0xFF4F6263),
    outline = Color(0xFF738485), outlineVariant = Color(0xFFD4DDD8),
)

// Cartoon console: deep ink, warm paper, lime selections and aqua/amber accents.
// On-colors are explicit so the same native controls work in dark and light modes.
val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFC6EB78), onPrimary = Color(0xFF20351C),
    primaryContainer = Color(0xFF354E36), onPrimaryContainer = Color(0xFFE1F8BA),
    secondary = Color(0xFF81D8CE), onSecondary = Color(0xFF123C3C),
    secondaryContainer = Color(0xFF264F55), onSecondaryContainer = Color(0xFFC7F4ED),
    tertiary = Color(0xFFFFC56F), onTertiary = Color(0xFF422D15),
    tertiaryContainer = Color(0xFF55412C), onTertiaryContainer = Color(0xFFFFE2B5),
    background = Color(0xFF182A32), onBackground = Color(0xFFF6F0DF),
    surface = Color(0xFF213740), onSurface = Color(0xFFF6F0DF),
    surfaceContainerLowest = Color(0xFF14252B), surfaceContainerLow = Color(0xFF263E47),
    surfaceContainer = Color(0xFF2B444C), surfaceContainerHigh = Color(0xFF324E55),
    surfaceContainerHighest = Color(0xFF3B5960),
    surfaceVariant = Color(0xFF2B444C), onSurfaceVariant = Color(0xFFBED0CD),
    outline = Color(0xFF9CB9B4), outlineVariant = Color(0xFF718E91),
    inverseSurface = Color(0xFFF4EEDB), inverseOnSurface = CartoonTokens.ink,
    inversePrimary = Color(0xFF315A38), surfaceTint = Color.Transparent,
)

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF315A38), onPrimary = Color(0xFFFAF9EC),
    primaryContainer = Color(0xFFD3EAA7), onPrimaryContainer = Color(0xFF213A26),
    secondary = Color(0xFF226268), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4E7E1), onSecondaryContainer = Color(0xFF193C42),
    tertiary = Color(0xFF86501C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD699), onTertiaryContainer = Color(0xFF4D3019),
    background = Color(0xFFF3EEDC), onBackground = CartoonTokens.ink,
    surface = Color(0xFFFFFAEB), onSurface = CartoonTokens.ink,
    surfaceContainerLowest = Color(0xFFFFFCF3), surfaceContainerLow = Color(0xFFF9F4E5),
    surfaceContainer = Color(0xFFEAE8D8), surfaceContainerHigh = Color(0xFFDFE4D6),
    surfaceContainerHighest = Color(0xFFD2DECE),
    surfaceVariant = Color(0xFFE7EAD9), onSurfaceVariant = Color(0xFF475D60),
    outline = Color(0xFF587174), outlineVariant = Color(0xFF334C50),
    inverseSurface = Color(0xFF243C44), inverseOnSurface = Color(0xFFF8F0DD),
    inversePrimary = Color(0xFFC6EB78), surfaceTint = Color.Transparent,
)

val WorkbenchShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)


val MeowColors = DarkColorScheme
