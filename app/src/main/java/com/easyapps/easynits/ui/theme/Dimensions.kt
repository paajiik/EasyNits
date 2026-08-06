package com.easyapps.easynits.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class EasyNitsDimensions(
    val none: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    val xxLarge: Dp = 32.dp,
    val xxxLarge: Dp = 40.dp,
    
    val screenMarginCompact: Dp = 16.dp,
    val screenMarginMedium: Dp = 24.dp,
    val screenMarginLarge: Dp = 32.dp
)

val LocalDimensions = staticCompositionLocalOf { EasyNitsDimensions() }
