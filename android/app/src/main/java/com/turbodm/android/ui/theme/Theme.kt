package com.turbodm.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Exact TurboDM Fedora Native Design System palette (shared across Desktop, Web, and Android). */
object TurboColors {
    val Surface = Color(0xFF0E1117)
    val SurfaceDim = Color(0xFF090C10)
    val SurfaceContainer = Color(0xFF161B26)
    val SurfaceContainerHigh = Color(0xFF1D2433)
    val Outline = Color(0xFF262E3F)
    val OutlineSubtle = Color(0xFF1E2534)

    val OnSurface = Color(0xFFF3F6FA)
    val OnSurfaceVariant = Color(0xFF94A3B8)

    val AdwaitaBlue = Color(0xFF3584E4)
    val Primary = Color(0xFF1A67C2)
    val PrimaryContainer = Color(0xFF003061)
    val OnPrimaryContainer = Color(0xFFD5E3FF)

    val EmeraldGreen = Color(0xFF2EC27E)
    val SuccessContainer = Color(0xFF003920)
    val OnSuccessContainer = Color(0xFF73FCB2)

    val AmberWarning = Color(0xFFE5A50A)
    val WarningContainer = Color(0xFF422D00)
    val OnWarningContainer = Color(0xFFFFDEA8)

    val CyanTip = Color(0xFF38BDF8)
    val ErrorRed = Color(0xFFDE3730)
}

private val TurboDarkScheme = darkColorScheme(
    primary = TurboColors.Primary,
    onPrimary = Color.White,
    primaryContainer = TurboColors.PrimaryContainer,
    onPrimaryContainer = TurboColors.OnPrimaryContainer,
    secondary = TurboColors.AdwaitaBlue,
    onSecondary = Color.White,
    tertiary = TurboColors.EmeraldGreen,
    background = TurboColors.Surface,
    onBackground = TurboColors.OnSurface,
    surface = TurboColors.Surface,
    onSurface = TurboColors.OnSurface,
    surfaceVariant = TurboColors.SurfaceContainer,
    onSurfaceVariant = TurboColors.OnSurfaceVariant,
    outline = TurboColors.Outline,
    outlineVariant = TurboColors.OutlineSubtle,
    error = TurboColors.ErrorRed,
)

private val TurboTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun TurboDMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TurboDarkScheme,
        typography = TurboTypography,
        content = content
    )
}
