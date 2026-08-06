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
        // Ensure even at 0 lux we have a small baseline for visibility and offset range.
        // Human eyes perceive light logarithmically, so we use log10(lux).
        val effectiveLux = max(1f, lux)
        val logLux = log10(effectiveLux.toDouble()).toFloat()
        
        // Logarithmic mapping:
        // We want 1 lux (log=0) -> ~15 (dim but visible) 
        // We want 10000 lux (log=4) -> 255 (full bright)
        
        val minBase = 15f
        val maxLog = 4f // 10^4 = 10000 lux
        
        val normalizedLog = (logLux / maxLog).coerceIn(0f, 1f)
        val base = minBase + normalizedLog * (255f - minBase)
        
        return base.coerceIn(0f, 255f)
    }
}
