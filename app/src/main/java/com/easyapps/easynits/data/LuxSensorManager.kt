package com.easyapps.easynits.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import java.util.ArrayDeque
import kotlin.random.Random

class LuxSensorManager(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    fun getLuxFlow(): Flow<Float> = callbackFlow {
        var hasReceivedEvent = false
        val sampleWindow = 10
        val samples = ArrayDeque<Float>(sampleWindow)
        var lastEmaValue = -1f
        val emaAlpha = 0.15f // Smoothing factor for buttery transitions
        
        fun addSampleAndSend(lux: Float) {
            samples.addLast(lux)
            if (samples.size > sampleWindow) {
                samples.removeFirst()
            }
            val average = samples.average().toFloat()
            
            // Apply EMA on top of average for extra stability
            if (lastEmaValue < 0) {
                lastEmaValue = average
            } else {
                // Only update if change is significant to save processing later in the chain
                val nextEma = lastEmaValue + emaAlpha * (average - lastEmaValue)
                if (kotlin.math.abs(nextEma - lastEmaValue) > 0.1f) {
                    lastEmaValue = nextEma
                    trySend(lastEmaValue)
                }
            }
            
            if (!hasReceivedEvent) {
                hasReceivedEvent = true
                trySend(lastEmaValue)
            }
        }
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
                    val lux = event.values[0]
                    addSampleAndSend(lux)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        var simulationJob: kotlinx.coroutines.Job? = null

        fun startSimulation() {
            if (simulationJob?.isActive == true) return
            simulationJob = launch {
                var currentSimLux = 0f
                addSampleAndSend(currentSimLux)
                while (true) {
                    delay(1000) // Slower simulation to save battery
                    // Random walk for lux
                    currentSimLux = (currentSimLux + Random.nextFloat() * 10 - 5f).coerceIn(0f, 1000f)
                    addSampleAndSend(currentSimLux)
                }
            }
        }

        val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
                        android.os.Build.FINGERPRINT.contains("unknown") ||
                        android.os.Build.MODEL.contains("google_sdk") ||
                        android.os.Build.MODEL.contains("Emulator") ||
                        android.os.Build.MODEL.contains("Android SDK built for x86") ||
                        android.os.Build.MANUFACTURER.contains("Genymotion") ||
                        (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic")) ||
                        "google_sdk" == android.os.Build.PRODUCT

        if (lightSensor != null && !isEmulator) {
            // Use SENSOR_DELAY_UI (approx 60Hz) instead of FASTEST (200Hz+) to save battery
            val registered = sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)
            if (registered) {
                launch {
                    delay(10000) // Wait longer during boot for hardware to warm up
                    if (!hasReceivedEvent) {
                        startSimulation()
                    }
                }
            } else {
                startSimulation()
            }
        } else {
            startSimulation()
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
            simulationJob?.cancel()
        }
    }
}
