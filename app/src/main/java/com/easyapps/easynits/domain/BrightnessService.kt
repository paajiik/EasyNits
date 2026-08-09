package com.easyapps.easynits.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.easyapps.easynits.MainActivity
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
        private const val SYNC_COOLDOWN_MS = 4000L // Increased to 4s to prevent Honor sync fighting
        private const val JITTER_THRESHOLD = 12 // Increased to 12 (approx 5%) to be more conservative
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
    private var isStartedFromBoot = false

    private var lastAppliedSystemValue = -1f
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

    private fun getSystemBrightnessAsFloat(): Float {
        return try {
            // Prioritize standard Int as it's the most compatible across OEMs like Honor/Huawei
            val intVal = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Read float as secondary for smoothing, but only if it's in sync with the int
                val floatVal = try { Settings.System.getFloat(contentResolver, "screen_brightness_float") * 255f } catch (e: Exception) { intVal.toFloat() }
                if (abs(floatVal - intVal) < 2.0f) {
                    return floatVal
                }
            }
            intVal.toFloat()
        } catch (e: Exception) {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).toFloat()
        }
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val now = System.currentTimeMillis()
            // Ignore updates during or immediately after the app itself changes brightness
            if (now - lastAppUpdateTime < SYNC_COOLDOWN_MS) return

            val systemBrightness = getSystemBrightnessAsFloat()

            // If system value is identical to our last applied value (within epsilon), ignore
            if (abs(systemBrightness - lastAppliedSystemValue) < 1.0f) return

            val settings = currentSettings ?: return
            if (!settings.isServiceActive) return

            // Manual override detected in system shade: Calculate new proportional offset
            val base = nitsCalculator.calculateBaseBrightness(currentLux)
            
            val newInternalOffset = when {
                abs(systemBrightness - base) < 2.0f -> 0
                systemBrightness > base -> {
                    val range = 255f - base
                    if (range > 1.0f) {
                        ((systemBrightness - base) / range * 255f).roundToInt()
                    } else 0
                }
                else -> { // systemBrightness < base
                    if (base > 1.0f) {
                        ((systemBrightness - base) / base * 255f).roundToInt()
                    } else 0
                }
            }.coerceIn(-255, 255)

            // Calculate difference in UI scale for logging and threshold check
            val currentOffset = settings.brightnessOffset
            
            // Only update if the change is significant AND the device is likely not in a transition
            if (abs(newInternalOffset - currentOffset) >= JITTER_THRESHOLD) {
                val manufacturer = Build.MANUFACTURER.lowercase()
                val isHonorOrHuawei = manufacturer.contains("honor") || manufacturer.contains("huawei")
                
                // Extra conservative on Honor/Huawei to prevent "not saving" issues
                if (isHonorOrHuawei && abs(newInternalOffset - currentOffset) < 25) return

                Log.d(TAG, "Manual Override Detected: sys=$systemBrightness, lux=$currentLux -> newOffset=$newInternalOffset")
                serviceScope.launch {
                    settingsRepository.updateBrightnessOffset(newInternalOffset)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("EasyNits_Debug", "BrightnessService: onCreate - Starting service")
        settingsRepository = SettingsRepository.getInstance(this)
        luxSensorManager = LuxSensorManager(this)

        // Show initial notification immediately to satisfy foreground service requirements
        showInitialNotification()

        if (!Settings.System.canWrite(this)) {
            Log.e("EasyNits_Debug", "BrightnessService: CRITICAL - WRITE_SETTINGS permission not granted!")
        }
        
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver
        )

        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                Log.d("EasyNits_Debug", "BrightnessService: Settings update received - active=${settings.isServiceActive}, runBg=${settings.runInBackground}, boot=$isStartedFromBoot")
                Log.d("EasyNits_Debug", "BrightnessService: Detailed Settings - Offset=${settings.brightnessOffset}, AutoStart=${settings.autoStart}, Lang=${settings.language}")
                
                currentSettings = settings
                
                if (settings.isServiceActive) {
                    if (settings.runInBackground || isStartedFromBoot) {
                        updateForegroundNotification()
                    } else {
                        Log.d("EasyNits_Debug", "BrightnessService: Stopping foreground (runInBackground=false and not in boot grace period)")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    // Trigger immediate recalculation on settings change (e.g., offset slider move)
                    calculateTargetBrightness(currentLux, settings.brightnessOffset)
                } else {
                    // If we just started from boot, settings might be default/empty if migration was deferred.
                    // We must maintain foreground during the 30s grace period to satisfy OS requirements.
                    if (isStartedFromBoot) {
                        Log.d("EasyNits_Debug", "BrightnessService: isServiceActive is false, but maintaining foreground during boot grace period.")
                        updateForegroundNotification()
                    } else {
                        Log.d("EasyNits_Debug", "BrightnessService: Stopping foreground (isServiceActive=false)")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
        
        startBrightnessControl()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra("FROM_BOOT", false) == true
        Log.d("EasyNits_Debug", "BrightnessService: onStartCommand - fromBoot=$fromBoot")
        
        if (fromBoot) {
            isStartedFromBoot = true
            Log.d("EasyNits_Debug", "BrightnessService: onStartCommand - Service started from boot. Forcing foreground for 30s.")
            
            // Re-trigger notification update now that isStartedFromBoot is true
            currentSettings?.let { settings ->
                if (settings.isServiceActive) {
                    updateForegroundNotification()
                }
            }

            // Maintain foreground for at least 30s to prevent OS from killing it during busy boot
            serviceScope.launch {
                delay(30000)
                Log.d("EasyNits_Debug", "BrightnessService: Boot grace period ended.")
                isStartedFromBoot = false
                // Re-trigger notification update based on actual settings
                currentSettings?.let { settings ->
                    if (settings.isServiceActive) {
                        if (settings.runInBackground) {
                            updateForegroundNotification()
                        } else {
                            Log.d("EasyNits_Debug", "BrightnessService: runInBackground is false, removing foreground.")
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startBrightnessControl() {
        serviceScope.launch {
            luxSensorManager.getLuxFlow().collect { lux ->
                // Increased sensitivity in dark environments: 0.1 lux threshold for low light
                val threshold = if (lux < 10f) 0.1f else 0.5f
                if (abs(currentLux - lux) < threshold && currentAppliedBrightness >= 0) return@collect
                
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
                    try {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val choreographer = try { Choreographer.getInstance() } catch (e: Exception) { null }
                            
                            if (choreographer != null) {
                                choreographer.postFrameCallback {
                                    val step = (target - currentAppliedBrightness) * 0.12f
                                    currentAppliedBrightness += step
                                    
                                    val rounded = currentAppliedBrightness.roundToInt().coerceIn(0, 255)
                                    if (abs(rounded - lastAppliedSystemValue) >= 1.0f) {
                                        applyBrightnessToSystem(rounded)
                                    }
                                    continuation.resume(Unit)
                                }
                            } else {
                                // Fallback for environments where Choreographer might fail initially (e.g. boot)
                                serviceScope.launch {
                                    delay(16) // ~60fps
                                    val step = (target - currentAppliedBrightness) * 0.12f
                                    currentAppliedBrightness += step
                                    val rounded = currentAppliedBrightness.roundToInt().coerceIn(0, 255)
                                    if (abs(rounded - lastAppliedSystemValue) >= 1.0f) {
                                        applyBrightnessToSystem(rounded)
                                    }
                                    continuation.resume(Unit)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in interpolation loop: ${e.message}")
                        delay(100) // Cooling off on error
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
        if (!Settings.System.canWrite(this)) {
            Log.e(TAG, "Cannot apply brightness: permission missing")
            return
        }
        
        lastAppliedSystemValue = brightness.toFloat()
        lastAppUpdateTime = System.currentTimeMillis()
        
        try {
            Log.v(TAG, "Applying brightness: $brightness")
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
        val settings = currentSettings
        val channelId = "brightness_service_channel"
        val localizedContext = if (settings != null) getLocalizedContext() else this
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                localizedContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(localizedContext.getString(R.string.notification_title))
            .setContentText(if (settings?.isServiceActive == true) localizedContext.getString(R.string.notification_text) else "Initializing...")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(settings?.customPrimaryColor?.toInt() ?: androidx.core.content.ContextCompat.getColor(this, R.color.glance_primary))
            .setColorized(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showInitialNotification() {
        val channelId = "brightness_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "EasyNits",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("EasyNits")
            .setContentText("Starting...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("EasyNits_Debug", "BrightnessService: onDestroy")
        contentResolver.unregisterContentObserver(brightnessObserver)
        serviceJob.cancel()
    }
}
