package com.easyapps.easynits.ui.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.first
import java.util.Calendar
import kotlin.math.roundToInt

class BrightnessWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = SettingsRepository.getInstance(context)
        
        provideContent {
            val settings by repository.settingsFlow.collectAsState(initial = null)
            val size = LocalSize.current
            
            settings?.let { s ->
                WidgetContent(context, s, size)
            }
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context, 
        settings: UserSettings, 
        size: DpSize
    ) {
        // Determine the active offset
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
        val currentTimeInMinutes = currentHour * 60 + currentMinute
        
        val activeSchedule = settings.scheduledOffsets.find { schedule ->
            if (currentDay !in schedule.days) return@find false
            val startMinutes = schedule.startHour * 60 + schedule.startMinute
            val endMinutes = schedule.endHour * 60 + schedule.endMinute
            if (startMinutes <= endMinutes) currentTimeInMinutes in startMinutes..endMinutes
            else currentTimeInMinutes >= startMinutes || currentTimeInMinutes <= endMinutes
        }

        val uiOffset = activeSchedule?.offset ?: (settings.brightnessOffset * 100f / 255f).roundToInt()
        val offsetText = if (uiOffset == 0) "0" else "%+d".format(uiOffset)

        // Manual Theme resolution to ensure sync with app settings
        val isNight = when(settings.themeMode) {
            1 -> false
            2 -> true
            else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        val primary: Color
        val background: Color
        val onSurface: Color
        val track: Color

        if (settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (isNight) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
            primary = scheme.primary
            background = scheme.background
            onSurface = scheme.onSurface
            track = scheme.primary.copy(alpha = 0.2f)
        } else {
            primary = settings.customPrimaryColor?.let { Color(it) } ?: Color(0xFF6750A4)
            background = settings.customBackgroundColor?.let { Color(it) } ?: Color(0xFFF3F3F3)
            onSurface = settings.customTextColor?.let { Color(it) } ?: Color(0xFF1C1B1F)
            track = primary.copy(alpha = 0.2f)
        }

        // Adaptive scaling based on widget size
        val isTiny = size.height < 70.dp
        val isWide = size.width > 250.dp
        
        val horizontalPadding = if (isWide) 20.dp else 12.dp
        val verticalPadding = if (isTiny) 8.dp else 16.dp
        val spacing = if (isTiny) 6.dp else 10.dp
        val titleSize = if (isTiny) 16.sp else 20.sp
        val btnSize = if (isTiny) 36.dp else 52.dp
        val sliderHeight = if (isTiny) 12.dp else 16.dp

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(background)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .appWidgetBackground(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.brightness_offset_title),
                    style = TextStyle(
                        color = ColorProvider(onSurface),
                        fontWeight = FontWeight.Bold,
                        fontSize = titleSize
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                Text(
                    text = context.getString(R.string.offset_label, offsetText),
                    style = TextStyle(
                        color = ColorProvider(primary),
                        fontWeight = FontWeight.Bold,
                        fontSize = titleSize
                    )
                )
            }

            Spacer(GlanceModifier.height(spacing))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Minus Button
                CircleButton(
                    iconRes = R.drawable.ic_remove_24,
                    contentDescription = "Decrease",
                    size = btnSize,
                    color = track,
                    iconTint = primary,
                    onClick = actionRunCallback<UpdateOffsetAction>(
                        actionParametersOf(UpdateOffsetAction.CHANGE_KEY to -5)
                    )
                )

                // Expressive Material 3 Slider Representation
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .height(sliderHeight)
                        .padding(horizontal = if (isWide) 32.dp else 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val progress = ((uiOffset + 100) / 200f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = GlanceModifier.fillMaxWidth().height(sliderHeight).cornerRadius(sliderHeight / 2),
                        color = ColorProvider(primary),
                        backgroundColor = ColorProvider(track)
                    )
                }

                // Plus Button
                CircleButton(
                    iconRes = R.drawable.ic_add_24,
                    contentDescription = "Increase",
                    size = btnSize,
                    color = track,
                    iconTint = primary,
                    onClick = actionRunCallback<UpdateOffsetAction>(
                        actionParametersOf(UpdateOffsetAction.CHANGE_KEY to 5)
                    )
                )
            }
        }
    }

    @Composable
    private fun CircleButton(
        iconRes: Int,
        contentDescription: String,
        size: androidx.compose.ui.unit.Dp,
        color: Color,
        iconTint: Color,
        onClick: Action
    ) {
        Box(
            modifier = GlanceModifier
                .size(size)
                .background(color)
                .cornerRadius(size / 2)
                .clickable(onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(size * 0.55f),
                colorFilter = ColorFilter.tint(ColorProvider(iconTint))
            )
        }
    }
}

class UpdateOffsetAction : ActionCallback {
    companion object {
        val CHANGE_KEY = ActionParameters.Key<Int>("change")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val change = parameters[CHANGE_KEY] ?: 0
        val repository = SettingsRepository.getInstance(context)
        val currentSettings = repository.settingsFlow.first()
        val currentUiOffset = (currentSettings.brightnessOffset * 100f / 255f).roundToInt()
        val newUiOffset = (currentUiOffset + change).coerceIn(-100, 100)
        val newInternalOffset = (newUiOffset * 255f / 100f).roundToInt()
        
        repository.updateBrightnessOffset(newInternalOffset)
        BrightnessWidget().updateAll(context)
    }
}

class BrightnessWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BrightnessWidget()
}
