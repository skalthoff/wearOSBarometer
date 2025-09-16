package com.vibecode.gasketcheck.signal

import kotlin.math.exp

/**
 * Simple median filter over odd window sizes (3 or 5 recommended).
 */
fun medianFilter(samples: List<Float>, window: Int = 3): List<Float> {
    require(window % 2 == 1 && window >= 3) { "Window must be odd >= 3" }
    if (samples.isEmpty()) return emptyList()
    val half = window / 2
    val out = FloatArray(samples.size)
    val buf = FloatArray(window)
    for (i in samples.indices) {
        var k = 0
        for (j in (i - half)..(i + half)) {
            val jj = when {
                j < 0 -> 0
                j >= samples.size -> samples.size - 1
                else -> j
            }
            buf[k++] = samples[jj]
        }
        out[i] = buf.copyOf().sorted()[half]
    }
    return out.toList()
}

/**
 * One-pole IIR low-pass filter with time constant tauSec; alpha computed per-sample using dt.
 */
class LowPassFilter(private val tauSec: Float) {
    private var y: Float? = null
    private var lastTimeNs: Long? = null

    fun reset() { y = null; lastTimeNs = null }

    fun filter(timeNs: Long, x: Float): Float {
        val yPrev = y
        return if (yPrev == null) {
            y = x
            lastTimeNs = timeNs
            x
        } else {
            val dtSec = ((timeNs - (lastTimeNs ?: timeNs)).coerceAtLeast(0L)) / 1e9f
            val alpha = if (tauSec <= 0f || dtSec <= 0f) 1f else dtSec / (tauSec + dtSec)
            val yNew = yPrev + alpha * (x - yPrev)
            y = yNew
            lastTimeNs = timeNs
            yNew
        }
    }
}

/**
 * High-pass via input - low-pass(input) to remove slow drift.
 */
class HighPassFilter(private val tauSec: Float) {
    private val lp = LowPassFilter(tauSec)
    fun reset() = lp.reset()
    fun filter(timeNs: Long, x: Float): Float = x - lp.filter(timeNs, x)
}

