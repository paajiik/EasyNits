package com.easyapps.easynits.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight
)

@Composable
fun EasyNitsTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = true,
    customPrimary: Long? = null,
    customBackground: Long? = null,
    customText: Long? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val finalColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorScheme
    } else {
        colorScheme.run {
            copy(
                primary = customPrimary?.let { Color(it) } ?: primary,
                onPrimary = customBackground?.let { Color(it) } ?: onPrimary,
                primaryContainer = customPrimary?.let { Color(it).copy(alpha = 0.22f) } ?: primaryContainer,
                onPrimaryContainer = customPrimary?.let { Color(it) } ?: onPrimaryContainer,

                secondary = customPrimary?.let { Color(it).copy(alpha = 0.8f) } ?: secondary,
                onSecondary = customBackground?.let { Color(it) } ?: onSecondary,
                secondaryContainer = customPrimary?.let { Color(it).copy(alpha = 0.18f) } ?: secondaryContainer,
                onSecondaryContainer = customPrimary?.let { Color(it) } ?: onSecondaryContainer,

                tertiary = customPrimary?.let { Color(it).copy(alpha = 0.7f) } ?: tertiary,
                onTertiary = customBackground?.let { Color(it) } ?: onTertiary,
                tertiaryContainer = customPrimary?.let { Color(it).copy(alpha = 0.25f) } ?: tertiaryContainer,
                onTertiaryContainer = customPrimary?.let { Color(it) } ?: onTertiaryContainer,

                background = customBackground?.let { Color(it) } ?: background,
                onBackground = customText?.let { Color(it) } ?: onBackground,

                surface = customBackground?.let { Color(it) } ?: surface,
                onSurface = customText?.let { Color(it) } ?: onSurface,

                surfaceVariant = customBackground?.let { Color(it).copy(alpha = 0.95f) } ?: surfaceVariant,
                onSurfaceVariant = customText?.let { Color(it).copy(alpha = 0.8f) } ?: onSurfaceVariant,

                outline = customText?.let { Color(it).copy(alpha = 0.5f) } ?: outline,
                outlineVariant = customText?.let { Color(it).copy(alpha = 0.2f) } ?: outlineVariant,

                scrim = Color.Black,
                surfaceBright = customBackground?.let { Color(it).copy(alpha = 0.98f) } ?: surfaceBright,
                surfaceContainer = customBackground?.let { Color(it).copy(alpha = 0.96f) } ?: surfaceContainer,
                surfaceContainerHigh = customBackground?.let { Color(it).copy(alpha = 0.94f) } ?: surfaceContainerHigh,
                surfaceContainerHighest = customBackground?.let { Color(it).copy(alpha = 0.92f) } ?: surfaceContainerHighest,
                surfaceContainerLow = customBackground?.let { Color(it).copy(alpha = 0.97f) } ?: surfaceContainerLow,
                surfaceContainerLowest = customBackground?.let { Color(it) } ?: surfaceContainerLowest,
                surfaceDim = customBackground?.let { Color(it).copy(alpha = 0.90f) } ?: surfaceDim
            )
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context.findActivity())?.window ?: return@SideEffect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = Typography,
        content = {
            CompositionLocalProvider(LocalDimensions provides EasyNitsDimensions()) {
                content()
            }
        }
    )
}

val MaterialTheme.dimensions: EasyNitsDimensions
    @Composable
    get() = LocalDimensions.current
