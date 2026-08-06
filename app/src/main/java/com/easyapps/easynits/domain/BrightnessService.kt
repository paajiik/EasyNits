package com.easyapps.easynits.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.Choreographer
import android.hardware.display.DisplayManager
import androidx.core.app.NotificationCompat
import androidx.core.os.LocaleListCompat
import com.easyapps.easynits.R
import com.easyapps.easynits.data.LuxSensorManager
import com.easyapps.easynits.data.SettingsRepository
import com.easyapps.easynits.data.UserSettings
import com.easyapps.easynits.data.ScheduledOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

class BrightnessService : Service() {

    companion object {
        private const val TAG = "BrightnessService"
        private const val NOTIFICATION_ID = 1
        private const val SYNC_COOLDOWN_MS = 2500L
        private const val JITTER_THRESHOLD = 4 // Ignore small system jumps
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var luxSensorManager: LuxSensorManager
    private val nitsCalculator: NitsCalculator by lazy {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

    private var currentAppliedBrightness = -1f
    private var targetBrightness = -1f
    private var currentSettings: UserSettings? = null
    private var currentLux = 0f

    private var lastAppliedSystemValue = -1
    private var lastAppUpdateTime = 0L

    private fun getLocalizedContext(): Context {
        val lang = currentSettings?.language ?: "system"
        val locale = if (lang == "system") {
            Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(lang)
        }
        
        val config = resources.configuration
        config.setLocale(locale)
        return createConfigurationContext(config)
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val now = System.currentTimeMillis()
            // Ignore updates during or immediately after the app itself changes brightness
            if (now - lastAppUpdateTime < SYNC_COOLDOWN_MS) return

            val systemBrightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                0
            )

            // If system value is identical to our last applied value, ignore
            if (systemBrightness == lastAppliedSystemValue) return

            val settings = currentSettings ?: return
            if (!settings.isServiceActive) return

            // Manual override detected in system shade: Calculate new proportional offset
            val base = nitsCalculator.calculateBaseBrightness(currentLux)
            
            val newInternalOffset = when {
                systemBrightness.toFloat() == base -> 0
                systemBrightness > base -> {
                    val range = 255f - base
                    if (range > 0) {
                        ((systemBrightness - base) / range * 255f).roundToInt()
                    } else 0
                }
                else -> { // systemBrightness < base
                    if (base > 0) {
                        ((systemBrightness - base) / base * 255f).roundToInt()
                    } else 0
                }
            }.coerceIn(-255, 255)

            // Calculate difference in UI scale for logging and threshold check
            val currentOffset = settings.brightnessOffset
            if (abs(newInternalOffset - currentOffset) >= JITTER_THRESHOLD) {
                Log.d(TAG, "Manual Override Detected: sys=$systemBrightness, lux=$currentLux -> newOffset=$newInternalOffset")
                serviceScope.launch {
                    settingsRepository.updateBrightnessOffset(newInternalOffset)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        settingsRepository = SettingsRepository(this)
        luxSensorManager = LuxSensorManager(this)
        
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver
        )

        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                if (settings.isServiceActive && settings.runInBackground) {
                    updateForegroundNotification()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        
        startBrightnessControl()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startBrightnessControl() {
        serviceScope.launch {
            luxSensorManager.getLuxFlow().collect { lux ->
                // Small threshold to avoid micro-adjustments
                if (abs(currentLux - lux) < 0.5f && currentAppliedBrightness >= 0) return@collect
                
                currentLux = lux
                val settings = currentSettings ?: return@collect
                if (settings.isServiceActive) {
                    calculateTargetBrightness(lux, settings.brightnessOffset)
                }
            }
        }

        // Smooth interpolation loop (Synced with device refresh rate)
        serviceScope.launch {
            while (isActive) {
                val target = targetBrightness
                if (target >= 0 && abs(currentAppliedBrightness - target) > 0.05f) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        Choreographer.getInstance().postFrameCallback {
                            // Responsive glide: move 12% toward target per frame
                            // Slightly slower than 15% for even smoother battery-friendly transitions
                            val step = (target - currentAppliedBrightness) * 0.12f
                            currentAppliedBrightness += step
                            
                            val rounded = currentAppliedBrightness.roundToInt().coerceIn(0, 255)
                            if (rounded != lastAppliedSystemValue) {
                                applyBrightnessToSystem(rounded)
                            }
                            continuation.resume(Unit)
                        }
                    }
                } else {
                    // Snap to target if very close and stop looping
                    if (target >= 0 && currentAppliedBrightness != target) {
                        currentAppliedBrightness = target
                        applyBrightnessToSystem(target.roundToInt().coerceIn(0, 255))
                    }
                    delay(200) // Much longer idle wait when not transitioning
                }
            }
        }
    }

    private fun calculateTargetBrightness(lux: Float, settingsOffset: Int) {
        val base = nitsCalculator.calculateBaseBrightness(lux)
        
        // Find if there's an active scheduled offset
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
        val currentTimeInMinutes = currentHour * 60 + currentMinute
        
        val activeScheduledOffset = currentSettings?.scheduledOffsets?.find { schedule ->
            if (currentDay !in schedule.days) return@find false

            val startMinutes = schedule.startHour * 60 + schedule.startMinute
            val endMinutes = schedule.endHour * 60 + schedule.endMinute
            
            if (startMinutes <= endMinutes) {
                currentTimeInMinutes in startMinutes..endMinutes
            } else {
                // Crosses midnight
                currentTimeInMinutes >= startMinutes || currentTimeInMinutes <= endMinutes
            }
        }
        
        val offsetFactor = if (activeScheduledOffset != null) {
            activeScheduledOffset.offset / 100f
        } else {
            settingsOffset / 255f
        }
        
        // Proportional Layering Algorithm: Ensures offset is linked to current baseline
        val newTarget = if (offsetFactor >= 0) {
            base + (255f - base) * offsetFactor
        } else {
            base + base * offsetFactor
        }.coerceIn(0f, 255f)

        if (abs(targetBrightness - newTarget) > 0.1f) {
            targetBrightness = newTarget
        }
        
        if (currentAppliedBrightness < 0) {
            currentAppliedBrightness = targetBrightness
        }
    }

    private fun applyBrightnessToSystem(brightness: Int) {
        if (!Settings.System.canWrite(this)) return
        
        lastAppliedSystemValue = brightness
        lastAppUpdateTime = System.currentTimeMillis()
        
        try {
            // Set mode to manual only if it's not already manual to save battery/cycles
            val currentMode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
            
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
        } catch (e: Exception) {
            // Silently fail
        }
    }

    private fun updateForegroundNotification() {
        val settings = currentSettings ?: return
        val channelId = "brightness_service_channel"
        val localizedContext = getLocalizedContext()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                localizedContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(localizedContext.getString(R.string.notification_title))
            .setContentText(localizedContext.getString(R.string.notification_text))
            // Use monochrome icon for status bar (alpha-only)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            // Set the accent color of the notification (the circle around the small icon)
            .setColor(settings.customPrimaryColor?.toInt() ?: androidx.core.content.ContextCompat.getColor(this, R.color.glance_primary))
            .setColorized(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        contentResolver.unregisterContentObserver(brightnessObserver)
        serviceJob.cancel()
    }
}
