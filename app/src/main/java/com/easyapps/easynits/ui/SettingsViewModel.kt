package com.easyapps.easynits.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.widget.Toast
import android.view.Display
import android.view.WindowManager
import android.view.Choreographer
import android.hardware.display.DisplayManager
import androidx.glance.appwidget.updateAll
import com.easyapps.easynits.R
import com.easyapps.easynits.ui.widget.BrightnessWidget
import com.easyapps.easynits.data.LuxSensorManager
import com.easyapps.easynits.data.SettingsRepository
import com.easyapps.easynits.data.ScheduledOffset
import com.easyapps.easynits.domain.BrightnessService
import com.easyapps.easynits.domain.NitsCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository.getInstance(application)
    private val luxSensorManager = LuxSensorManager(application)
    
    private val nitsCalculator: NitsCalculator by lazy {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = application.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            val windowManager = application.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        var minNits = 2.0f
        var maxNits = 1000.0f

        val hdrCapabilities = display?.hdrCapabilities
        val detectedMax = hdrCapabilities?.desiredMaxLuminance ?: 0f
        val detectedMin = hdrCapabilities?.desiredMinLuminance ?: 0f

        if (detectedMax > 100f) {
            maxNits = detectedMax
        }
        if (detectedMin > 0f && detectedMin < 10f) {
            minNits = detectedMin
        }

        NitsCalculator(minNits = minNits, maxNits = maxNits)
    }

    val settings = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val currentLux = luxSensorManager.getLuxFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0f
    )

    private val _targetNits = MutableStateFlow(0f)
    private val _smoothNits = MutableStateFlow(0f)
    val currentNits = _smoothNits.asStateFlow()

    private val _smoothBrightnessPercent = MutableStateFlow(0)
    val currentBrightnessPercent = _smoothBrightnessPercent.asStateFlow()

    init {
        // Ensure service is running if it should be
        viewModelScope.launch {
            val s = repository.settingsFlow.first()
            if (s.isServiceActive) {
                startService()
            }
        }

        // Smoothing loop
        viewModelScope.launch {
            while (true) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    Choreographer.getInstance().postFrameCallback {
                        val target = _targetNits.value
                        if (abs(_smoothNits.value - target) > 0.1f) {
                            _smoothNits.value += (target - _smoothNits.value) * 0.12f
                        } else {
                            _smoothNits.value = target
                        }
                        continuation.resume(Unit)
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(currentLux, settings) { lux, settingsValue ->
                val base = nitsCalculator.calculateBaseBrightness(lux)
                
                // Determine the active offset factor
                val calendar = Calendar.getInstance()
                val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                val currentMinute = calendar.get(Calendar.MINUTE)
                val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
                val currentTimeInMinutes = currentHour * 60 + currentMinute
                
                val activeSchedule = settingsValue?.scheduledOffsets?.find { schedule ->
                    if (currentDay !in schedule.days) return@find false
                    val startMinutes = schedule.startHour * 60 + schedule.startMinute
                    val endMinutes = schedule.endHour * 60 + schedule.endMinute
                    if (startMinutes <= endMinutes) currentTimeInMinutes in startMinutes..endMinutes
                    else currentTimeInMinutes >= startMinutes || currentTimeInMinutes <= endMinutes
                }

                val offsetFactor = if (activeSchedule != null) {
                    activeSchedule.offset / 100f
                } else {
                    (settingsValue?.brightnessOffset ?: 0) / 255f
                }
                
                // Proportional Layering Algorithm
                val targetBrightness = if (offsetFactor >= 0) {
                    base + (255f - base) * offsetFactor
                } else {
                    base + base * offsetFactor
                }.coerceIn(0f, 255f)
                
                // Calculate nits based on actual screen output
                val targetNits = nitsCalculator.calculateScreenNits(targetBrightness)
                
                // Use a more conservative perceptual scale (0.7 power) to match Oppo/ColorOS/MagicOS slider logic
                val perceptualPercent = (targetBrightness / 255f).toDouble().pow(0.7).toFloat() * 100
                
                targetNits to perceptualPercent.toInt()
            }.collectLatest { (nits, percent) ->
                _targetNits.value = nits
                _smoothBrightnessPercent.value = percent
            }
        }
    }

    private val _transientOffset = MutableStateFlow<Int?>(null)
    private var lastManualInteractionTime = 0L
    private val interactionLockDuration = 3000L // 3 seconds of lock

    val uiBrightnessOffset = combine(settings, _transientOffset) { settingsValue, transient ->
        val now = System.currentTimeMillis()
        if (transient != null || now - lastManualInteractionTime < interactionLockDuration) {
            // Lock UI to the user's manual value during interaction
            transient ?: settingsValue?.brightnessOffset?.let { offset -> (offset * 100f / 255f).roundToInt() } ?: 0
        } else {
            // Check for scheduled offset first
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)
            val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
            val currentTimeInMinutes = currentHour * 60 + currentMinute
            
            val activeSchedule = settingsValue?.scheduledOffsets?.find { schedule ->
                if (currentDay !in schedule.days) return@find false

                val startMinutes = schedule.startHour * 60 + schedule.startMinute
                val endMinutes = schedule.endHour * 60 + schedule.endMinute
                if (startMinutes <= endMinutes) currentTimeInMinutes in startMinutes..endMinutes
                else currentTimeInMinutes >= startMinutes || currentTimeInMinutes <= endMinutes
            }

            if (activeSchedule != null) {
                activeSchedule.offset
            } else {
                // Revert to background sync value
                settingsValue?.brightnessOffset?.let { offset -> (offset * 100f / 255f).roundToInt() } ?: 0
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun updateBrightnessOffset(uiOffset: Int) {
        _transientOffset.value = uiOffset
        lastManualInteractionTime = System.currentTimeMillis()
        viewModelScope.launch {
            val internalOffset = (uiOffset * 255f / 100f).roundToInt()
            repository.updateBrightnessOffset(internalOffset)
            
            // Sync all widgets
            BrightnessWidget().updateAll(getApplication())
            
            // Hold the lock for a period to allow system changes to propagate without jumping
            delay(interactionLockDuration) 
            if (System.currentTimeMillis() - lastManualInteractionTime >= interactionLockDuration) {
                _transientOffset.value = null
            }
        }
    }

    fun toggleService(isActive: Boolean) {
        viewModelScope.launch {
            repository.updateServiceActive(isActive)
            if (isActive) {
                startService()
            } else {
                stopService()
            }
        }
    }

    fun toggleRunInBackground(runInBackground: Boolean) {
        viewModelScope.launch {
            repository.updateRunInBackground(runInBackground)
            val s = repository.settingsFlow.first()
            if (s.isServiceActive) {
                startService()
            }
        }
    }

    fun toggleAutoStart(autoStart: Boolean) {
        viewModelScope.launch {
            repository.updateAutoStart(autoStart)
        }
    }

    fun updateThemeMode(mode: Int) {
        viewModelScope.launch {
            repository.updateThemeMode(mode)
            BrightnessWidget().updateAll(getApplication())
        }
    }

    fun updateUseDynamicColor(use: Boolean) {
        viewModelScope.launch {
            repository.updateUseDynamicColor(use)
            BrightnessWidget().updateAll(getApplication())
        }
    }

    fun updateCustomPrimaryColor(color: Long?) {
        viewModelScope.launch {
            val currentSettings = settings.value
            if (color != null && color == currentSettings?.customBackgroundColor) {
                Toast.makeText(getApplication(), getApplication<Application>().getString(R.string.color_conflict_toast), Toast.LENGTH_SHORT).show()
                return@launch
            }
            repository.updateCustomPrimaryColor(color)
            BrightnessWidget().updateAll(getApplication())
        }
    }

    fun updateCustomBackgroundColor(color: Long?) {
        viewModelScope.launch {
            val currentSettings = settings.value
            if (color != null && (color == currentSettings?.customTextColor || color == currentSettings?.customPrimaryColor)) {
                Toast.makeText(getApplication(), getApplication<Application>().getString(R.string.color_conflict_toast), Toast.LENGTH_SHORT).show()
                return@launch
            }
            repository.updateCustomBackgroundColor(color)
            BrightnessWidget().updateAll(getApplication())
        }
    }

    fun updateCustomTextColor(color: Long?) {
        viewModelScope.launch {
            val currentSettings = settings.value
            if (color != null && color == currentSettings?.customBackgroundColor) {
                Toast.makeText(getApplication(), getApplication<Application>().getString(R.string.color_conflict_toast), Toast.LENGTH_SHORT).show()
                return@launch
            }
            repository.updateCustomTextColor(color)
            BrightnessWidget().updateAll(getApplication())
        }
    }

    fun updateAppIcon(icon: String, context: Context) {
        viewModelScope.launch {
            repository.updateAppIcon(icon)
            
            val packageManager = context.packageManager
            val packageName = context.packageName
            
            val icons = (1..21).map { "v$it" }
            
            icons.forEach { iconId ->
                val aliasName = if (iconId == "v1") "MainActivityDefault" else "MainActivityV${iconId.removePrefix("v")}"
                val component = ComponentName(packageName, "$packageName.$aliasName")
                val state = if (iconId == icon) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
            
            Toast.makeText(context, context.getString(R.string.icon_changed_toast), Toast.LENGTH_LONG).show()
        }
    }

    fun updateLanguage(language: String) {
        viewModelScope.launch {
            repository.updateLanguage(language)
            val appLocale = if (language == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language)
            }
            AppCompatDelegate.setApplicationLocales(appLocale)
        }
    }

    fun addScheduledOffset(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, offset: Int, days: List<Int>) {
        viewModelScope.launch {
            val current = settings.value?.scheduledOffsets ?: emptyList()
            val newList = current + ScheduledOffset(
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                offset = offset,
                days = days
            )
            repository.updateScheduledOffsets(newList)
        }
    }

    fun removeScheduledOffset(id: String) {
        viewModelScope.launch {
            val current = settings.value?.scheduledOffsets ?: emptyList()
            val newList = current.filter { it.id != id }
            repository.updateScheduledOffsets(newList)
        }
    }

    fun updateScheduledOffset(id: String, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, offset: Int, days: List<Int>) {
        viewModelScope.launch {
            val current = settings.value?.scheduledOffsets ?: emptyList()
            val newList = current.map {
                if (it.id == id) {
                    it.copy(
                        startHour = startHour,
                        startMinute = startMinute,
                        endHour = endHour,
                        endMinute = endMinute,
                        offset = offset,
                        days = days
                    )
                } else it
            }
            repository.updateScheduledOffsets(newList)
        }
    }

    private fun startService() {
        viewModelScope.launch {
            val s = repository.settingsFlow.first()
            val context = getApplication<Application>()
            val intent = Intent(context, BrightnessService::class.java)
            
            if (s.runInBackground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopService() {
        val context = getApplication<Application>()
        val intent = Intent(context, BrightnessService::class.java)
        context.stopService(intent)
    }

    fun hasWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(getApplication())
        } else {
            true
        }
    }

    fun requestWriteSettingsPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
