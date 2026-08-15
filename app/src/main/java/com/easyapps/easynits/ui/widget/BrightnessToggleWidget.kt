package com.easyapps.easynits.ui.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.easyapps.easynits.R
import com.easyapps.easynits.data.SettingsRepository
import com.easyapps.easynits.data.UserSettings
import com.easyapps.easynits.domain.BrightnessService
import kotlinx.coroutines.flow.first

class BrightnessToggleWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = SettingsRepository.getInstance(context)
        
        provideContent {
            val settings by repository.settingsFlow.collectAsState(initial = null)
            settings?.let { s ->
                ToggleContent(context, s)
            }
        }
    }

    @Composable
    private fun ToggleContent(context: Context, settings: UserSettings) {
        // Manual Theme resolution to ensure sync with app settings
        val isNight = when(settings.themeMode) {
            1 -> false
            2 -> true
            else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        val primary: Color
        val background: Color

        if (settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (isNight) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
            primary = scheme.primary
            background = scheme.background
        } else {
            primary = settings.customPrimaryColor?.let { Color(it) } ?: Color(0xFF6750A4)
            background = settings.customBackgroundColor?.let { Color(it) } ?: Color(0xFFF3F3F3)
        }

        val isActive = settings.isServiceActive
        val circleColor = if (isActive) primary else primary.copy(alpha = 0.2f)
        val iconTint = if (isActive) background else primary

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(circleColor)
                    .cornerRadius(100.dp) // Large value for circle
                    .clickable(actionRunCallback<ToggleServiceAction>()),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_qs_tile),
                    contentDescription = "Toggle Brightness Service",
                    modifier = GlanceModifier.size(38.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(iconTint))
                )
            }
        }
    }
}

class ToggleServiceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repository = SettingsRepository.getInstance(context)
        val settings = repository.settingsFlow.first()
        val newState = !settings.isServiceActive
        repository.updateServiceActive(newState)
        
        val intent = Intent(context, BrightnessService::class.java)
        if (newState) {
            if (settings.runInBackground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
        
        BrightnessToggleWidget().updateAll(context)
        // Also update the main widget if it exists, as it might show service state (though currently it doesn't)
        BrightnessWidget().updateAll(context)
    }
}

class BrightnessToggleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BrightnessToggleWidget()
}
