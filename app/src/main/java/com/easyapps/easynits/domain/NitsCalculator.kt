package com.easyapps.easynits.domain

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.log10
import kotlin.math.max

/**
 * Modular logic for nits calculation and brightness mapping.
 */
class NitsCalculator(
    private val minNits: Float = 2.0f,
    private val maxNits: Float = 1000.0f
) {
    
    /**
     * Estimates nits based on ambient lux.
     * Uses the Lambertian surface formula as a baseline.
     */
    fun calculateNitsFromLux(lux: Float): Float {
        if (lux <= 0) return 0f
        return lux / PI.toFloat()
    }

    /**
     * Estimates the actual luminance (nits) of the device screen based on system brightness.
     * Maps 0..255 to a realistic range using a gamma-corrected curve.
     */
    fun calculateScreenNits(brightness: Float): Float {
        val ratio = (brightness / 255f).coerceIn(0f, 1f)
        // Using a 1.8 gamma curve for better mid-range responsiveness
        return minNits + (maxNits - minNits) * ratio.pow(1.8f)
    }

    /**
     * Maps ambient lux to a suggested system brightness level (0-255).
     * Uses a logarithmic curve that balances visibility in various lighting conditions.
     */
    fun calculateBaseBrightness(lux: Float): Float {
        // Human eyes perceive light logarithmically.
        val effectiveLux = max(0.1f, lux)
        val logLux = log10(effectiveLux.toDouble()).toFloat()
        
        // Universal Logarithmic scale: 
        // -0.6 (approx 0.25 lux) to 4.8 (approx 63,000 lux)
        val minLog = -0.6f 
        val maxLog = 4.8f  
        
        val normalized = (logLux - minLog) / (maxLog - minLog)
        
        // Power 2.2: Aggressive curve to keep the low-end very dim (0.1 - 10 lux).
        // This prevents the "40% at night" issue seen on Honor/Oppo.
        val base = 1.2f + normalized.coerceIn(0f, 1f).pow(2.2f) * 253.8f
        
        return base.coerceIn(1.2f, 255f)
    }
}
