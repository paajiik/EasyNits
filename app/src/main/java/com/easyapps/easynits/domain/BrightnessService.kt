package com.easyapps.easynits.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.database.ContentObserver
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
        private const val SYNC_COOLDOWN_MS = 4000L 
        private const val JITTER_THRESHOLD = 12 
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var luxSensorManager: LuxSensorManager

    private var wakeLock: PowerManager.WakeLock? = null

    private val keyguardManager: KeyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

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
        var maxNits = 500.0f 

        val hdr = display?.hdrCapabilities
        val detectedMax = hdr?.desiredMaxLuminance ?: 0f
        val detectedMin = hdr?.desiredMinLuminance ?: 0f
        
        val supportsHighDynamicRange = hdr?.supportedHdrTypes?.isNotEmpty() == true
        val isHighEndPanel = supportsHighDynamicRange || detectedMax > 800f

        if (isHighEndPanel) {
            maxNits = if (detectedMax > 1200f) detectedMax else 1500f
        } else if (detectedMax > 100f) {
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
    private var lastSystemEventTime = 0L

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            lastSystemEventTime = now
            serviceScope.launch {
                settingsRepository.updateLastUnlockTime(now)
            }
            Log.d(TAG, "System event detected: ${intent.action}, suppressing sync.")
        }
    }

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
            val intVal = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            if (now - lastAppUpdateTime < SYNC_COOLDOWN_MS) return
            
            // Increased to 15 seconds to completely clear Honor's Face Unlock brightness ramps
            if (now - lastSystemEventTime < 15000L) return

            // Ignore system changes while the keyguard is locked (prevents Face Unlock ramp override)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && keyguardManager.isKeyguardLocked) {
                Log.d(TAG, "Keyguard locked, ignoring system brightness change.")
                return
            }

            val systemBrightness = getSystemBrightnessAsFloat()
            if (abs(systemBrightness - lastAppliedSystemValue) < 1.0f) return

            val settings = currentSettings ?: return
            if (!settings.isServiceActive) return

            val base = nitsCalculator.calculateBaseBrightness(currentLux)
            
            val newInternalOffset = when {
                abs(systemBrightness - base) < 2.0f -> 0
                systemBrightness > base -> {
                    val range = 255f - base
                    if (range > 1.0f) {
                        ((systemBrightness - base) / range * 255f).roundToInt()
                    } else 0
                }
                else -> { 
                    if (base > 1.0f) {
                        ((systemBrightness - base) / base * 255f).roundToInt()
                    } else 0
                }
            }.coerceIn(-255, 255)

            val currentOffset = settings.brightnessOffset
            if (abs(newInternalOffset - currentOffset) >= JITTER_THRESHOLD) {
                val manufacturer = Build.MANUFACTURER.lowercase()
                val isHonorOrHuawei = manufacturer.contains("honor") || manufacturer.contains("huawei")
                
                // Extra aggressive: require a 25% change on Honor to save a manual override
                if (isHonorOrHuawei && abs(newInternalOffset - currentOffset) < 65) return

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

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EasyNits:BrightnessWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        showInitialNotification()

        if (!Settings.System.canWrite(this)) {
            Log.e("EasyNits_Debug", "BrightnessService: CRITICAL - WRITE_SETTINGS permission not granted!")
        }
        
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        registerReceiver(systemEventReceiver, filter)

        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                if (settings.isServiceActive) {
                    if (settings.runInBackground || isStartedFromBoot) {
                        updateForegroundNotification()
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    calculateTargetBrightness(currentLux, settings.brightnessOffset)
                } else {
                    if (isStartedFromBoot) {
                        updateForegroundNotification()
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
        
        startBrightnessControl()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        val fromBoot = intent?.getBooleanExtra("FROM_BOOT", false) == true
        if (fromBoot) {
            isStartedFromBoot = true
            serviceScope.launch {
                delay(30000)
                isStartedFromBoot = false
                currentSettings?.let { settings ->
                    if (settings.isServiceActive) {
                        if (settings.runInBackground) {
                            updateForegroundNotification()
                        } else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    }
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, this.javaClass).also {
            it.setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            this,
            1,
            restartServiceIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val alarmService = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    private fun startBrightnessControl() {
        serviceScope.launch {
            luxSensorManager.getLuxFlow().collect { lux ->
                val threshold = if (lux < 10f) 0.1f else 0.5f
                if (abs(currentLux - lux) < threshold && currentAppliedBrightness >= 0) return@collect
                
                currentLux = lux
                val settings = currentSettings ?: return@collect
                if (settings.isServiceActive) {
                    calculateTargetBrightness(lux, settings.brightnessOffset)
                }
            }
        }

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
                                        serviceScope.launch {
                                            val percent = nitsCalculator.calculatePerceptualPercent(rounded.toFloat())
                                            settingsRepository.updateLastBrightnessPercent(percent)
                                        }
                                    }
                                    continuation.resume(Unit)
                                }
                            } else {
                                serviceScope.launch {
                                    delay(16)
                                    val step = (target - currentAppliedBrightness) * 0.12f
                                    currentAppliedBrightness += step
                                    val rounded = currentAppliedBrightness.roundToInt().coerceIn(0, 255)
                                    if (abs(rounded - lastAppliedSystemValue) >= 1.0f) {
                                        applyBrightnessToSystem(rounded)
                                        serviceScope.launch {
                                            val percent = nitsCalculator.calculatePerceptualPercent(rounded.toFloat())
                                            settingsRepository.updateLastBrightnessPercent(percent)
                                        }
                                    }
                                    continuation.resume(Unit)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        delay(100)
                    }
                } else {
                    if (target >= 0 && currentAppliedBrightness != target) {
                        currentAppliedBrightness = target
                        applyBrightnessToSystem(target.roundToInt().coerceIn(0, 255))
                    }
                    delay(200)
                }
            }
        }
    }

    private fun calculateTargetBrightness(lux: Float, settingsOffset: Int) {
        val base = nitsCalculator.calculateBaseBrightness(lux)
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
        val currentTimeInMinutes = currentHour * 60 + currentMinute
        
        val activeScheduledOffset = currentSettings?.scheduledOffsets?.find { schedule ->
            if (currentDay !in schedule.days) return@find false
            val startMinutes = schedule.startHour * 60 + schedule.startMinute
            val endMinutes = schedule.endHour * 60 + schedule.endMinute
            if (startMinutes <= endMinutes) currentTimeInMinutes in startMinutes..endMinutes
            else currentTimeInMinutes >= startMinutes || currentTimeInMinutes <= endMinutes
        }
        
        val offsetFactor = if (activeScheduledOffset != null) {
            activeScheduledOffset.offset / 100f
        } else {
            settingsOffset / 255f
        }
        
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
        
        lastAppliedSystemValue = brightness.toFloat()
        lastAppUpdateTime = System.currentTimeMillis()
        
        try {
            val currentMode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            }
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        } catch (e: Exception) {}
    }

    private fun updateForegroundNotification() {
        val settings = currentSettings
        val channelId = "brightness_service_channel"
        val localizedContext = if (settings != null) getLocalizedContext() else this
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, localizedContext.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(localizedContext.getString(R.string.notification_title))
            .setContentText(if (settings?.isServiceActive == true) localizedContext.getString(R.string.notification_text) else "Initializing...")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(settings?.customPrimaryColor?.toInt() ?: androidx.core.content.ContextCompat.getColor(this, R.color.glance_primary))
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

    private fun showInitialNotification() {
        val channelId = "brightness_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(channelId, "EasyNits", NotificationManager.IMPORTANCE_LOW))
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

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
        releaseWakeLock()
        try { unregisterReceiver(systemEventReceiver) } catch (e: Exception) {}
        contentResolver.unregisterContentObserver(brightnessObserver)
        serviceJob.cancel()
    }
}
