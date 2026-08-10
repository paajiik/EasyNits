package com.easyapps.easynits.domain

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

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
        val effectiveLux = max(0.05f, lux)
        val logLux = log10(effectiveLux.toDouble()).toFloat()
        
        // Universal Logarithmic scale optimized for high-end responsiveness (Oppo)
        // while maintaining ultra-dim night performance (Honor).
        val minLog = -1.0f // 0.1 lux
        val maxLog = 4.3f  // ~20,000 lux
        
        val normalized = (logLux - minLog) / (maxLog - minLog)
        
        // Power 2.4: Steep enough for darkness, but reaches 100% at ~20k lux.
        val base = 1.0f + normalized.coerceIn(0f, 1f).pow(2.4f) * 254.0f
        
        return base.coerceIn(1.0f, 255f)
    }

    /**
     * Converts raw linear brightness (0-255) to a perceptual percentage (0-100).
     */
    fun calculatePerceptualPercent(brightness: Float): Int {
        return (brightness / 255f).toDouble().pow(0.7).toFloat().let { (it * 100).roundToInt() }.coerceIn(0, 100)
    }
}
