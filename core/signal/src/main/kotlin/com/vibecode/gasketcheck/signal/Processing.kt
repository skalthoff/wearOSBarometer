package com.vibecode.gasketcheck.signal

import kotlin.math.absoluteValue
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class Features(
    val deltaPMax: Float,      // hPa
    val decayTauSec: Float,    // seconds
    val r2: Float              // goodness of fit [0..1]
)

data class Score(val value: Int, val confidence: Float)

data class AnalyzerConfig(
    val baselineMinSec: Float = 2.0f,
    val pressSlopeHpaPerSec: Float = 0.15f,
    val releaseSlopeHpaPerSec: Float = -0.15f,
    val baselineStabilitySlope: Float = 0.05f,
    val minPressSec: Float = 1.5f,
    val minReleaseSec: Float = 1.0f,
    val maxTestSec: Float = 12.0f
)

enum class Phase { Idle, Baseline, Press, Release, Complete, Error }

data class DetectorState(
    val phase: Phase,
    val baselineHpa: Float = Float.NaN,
    val startTimeNs: Long = 0L,
    val pressStartNs: Long = 0L,
    val releaseStartNs: Long = 0L,
    val lastSampleTimeNs: Long = 0L,
    val deltaPMax: Float = 0f,
    val error: String? = null
)

/**
 * Streaming detector for baseline → press → release phases using dP/dt thresholds.
 * Motion gating can be provided by setting [motionOk] to false to pause detection.
 */
class PressReleaseDetector(private val cfg: AnalyzerConfig = AnalyzerConfig()) {
    private var state = DetectorState(phase = Phase.Idle)
    private val window = ArrayDeque<Pair<Long, Float>>() // (t, pHpa)

    fun reset() { state = DetectorState(phase = Phase.Idle); window.clear() }

    fun phase(): Phase = state.phase

    fun onSample(timeNs: Long, pHpa: Float, motionOk: Boolean = true): DetectorState {
        // Maintain rolling window of ~5 seconds for slope and baseline estimates
        window.addLast(timeNs to pHpa)
        trimWindow(maxAgeNs = 5_000_000_000L)

        if (state.phase == Phase.Complete || state.phase == Phase.Error) return state

        val nowState = when (state.phase) {
            Phase.Idle -> {
                DetectorState(
                    phase = Phase.Baseline,
                    startTimeNs = timeNs,
                    baselineHpa = pHpa,
                    lastSampleTimeNs = timeNs
                )
            }
            Phase.Baseline -> {
                val baseline = windowAverage(seconds = 1.0)
                val slope = slopeHpaPerSec(window)
                val elapsed = (timeNs - state.startTimeNs) / 1e9f
                val ready = elapsed >= cfg.baselineMinSec && slope.absoluteValue <= cfg.baselineStabilitySlope
                val phase = if (!motionOk) Phase.Baseline else if (ready) Phase.Baseline else Phase.Baseline
                // Transition to press if slope exceeds threshold and motion okay
                if (motionOk && slope >= cfg.pressSlopeHpaPerSec) {
                    state.copy(
                        phase = Phase.Press,
                        baselineHpa = baseline,
                        pressStartNs = timeNs,
                        lastSampleTimeNs = timeNs,
                        deltaPMax = max(0f, pHpa - baseline)
                    )
                } else state.copy(
                    phase = phase,
                    baselineHpa = baseline,
                    lastSampleTimeNs = timeNs
                )
            }
            Phase.Press -> {
                val delta = max(0f, pHpa - state.baselineHpa)
                val newDeltaMax = max(state.deltaPMax, delta)
                val slope = slopeHpaPerSec(window)
                val elapsedPressSec = (timeNs - state.pressStartNs) / 1e9f
                val shouldRelease = slope <= cfg.releaseSlopeHpaPerSec && elapsedPressSec >= 0.5f
                if (shouldRelease) {
                    state.copy(
                        phase = Phase.Release,
                        deltaPMax = newDeltaMax,
                        releaseStartNs = timeNs,
                        lastSampleTimeNs = timeNs
                    )
                } else state.copy(
                    phase = Phase.Press,
                    deltaPMax = newDeltaMax,
                    lastSampleTimeNs = timeNs
                )
            }
            Phase.Release -> {
                val elapsedReleaseSec = (timeNs - state.releaseStartNs) / 1e9f
                val totalSec = (timeNs - state.startTimeNs) / 1e9f
                if (elapsedReleaseSec >= cfg.minReleaseSec || totalSec >= cfg.maxTestSec) {
                    state.copy(
                        phase = Phase.Complete,
                        lastSampleTimeNs = timeNs
                    )
                } else state.copy(
                    phase = Phase.Release,
                    lastSampleTimeNs = timeNs
                )
            }
            Phase.Complete, Phase.Error -> state
        }
        state = nowState
        return state
    }

    fun windowSamples(): List<Pair<Long, Float>> = window.toList()

    private fun trimWindow(maxAgeNs: Long) {
        val latest = window.lastOrNull()?.first ?: return
        val cutoff = latest - maxAgeNs
        while (window.isNotEmpty() && window.first().first < cutoff) window.removeFirst()
    }

    private fun windowAverage(seconds: Double): Float {
        val latest = window.lastOrNull()?.first ?: return Float.NaN
        val cutoff = latest - (seconds * 1e9).toLong()
        var sum = 0.0
        var count = 0
        for (i in window.size - 1 downTo 0) {
            val (t, p) = window.elementAt(i)
            if (t < cutoff) break
            sum += p
            count++
        }
        return if (count > 0) (sum / count).toFloat() else Float.NaN
    }

    private fun slopeHpaPerSec(win: ArrayDeque<Pair<Long, Float>>): Float {
        // Linear regression over last ~0.5 s for robustness
        val latest = win.lastOrNull()?.first ?: return 0f
        val cutoff = latest - 500_000_000L
        var n = 0
        var sumT = 0.0
        var sumP = 0.0
        var sumTT = 0.0
        var sumTP = 0.0
        for (i in win.size - 1 downTo 0) {
            val (t, p) = win.elementAt(i)
            if (t < cutoff) break
            val ts = (t - latest).toDouble() / 1e9 // negative to 0
            n++
            sumT += ts
            sumP += p
            sumTT += ts * ts
            sumTP += ts * p
        }
        if (n < 3) return 0f
        val denom = (n * sumTT - sumT * sumT)
        if (denom == 0.0) return 0f
        val slope = (n * sumTP - sumT * sumP) / denom // hPa per second
        return slope.toFloat()
    }
}

/**
 * Compute features from a segment around release using an exponential decay fit.
 * @param baselineHpa pre-press baseline estimate
 * @param peakHpa maximum observed during press
 * @param releaseSamples samples from release start onward (tNs, pHpa)
 */
fun computeFeatures(
    baselineHpa: Float,
    peakHpa: Float,
    releaseSamples: List<Pair<Long, Float>>
): Features {
    val deltaP = max(0f, peakHpa - baselineHpa)
    if (releaseSamples.size < 5 || deltaP <= 0f) return Features(0f, 0f, 0f)

    // Estimate P_inf as average of the last 20% of points
    val tailCount = max(3, releaseSamples.size / 5)
    var sumTail = 0.0
    for (i in releaseSamples.size - tailCount until releaseSamples.size) {
        sumTail += releaseSamples[i].second
    }
    val pInf = (sumTail / tailCount).toFloat()

    // Build ln(P - P_inf) vs time to fit slope = -1/tau
    val t0 = releaseSamples.first().first
    val xs = ArrayList<Double>()
    val ys = ArrayList<Double>()
    for ((t, p) in releaseSamples) {
        val y = (p - pInf).toDouble()
        if (y <= 1e-6) continue // avoid log domain issues
        xs.add((t - t0) / 1e9)
        ys.add(ln(y))
    }
    if (xs.size < 3) return Features(deltaP, 0f, 0f)

    val (slope, r2) = linearFit(xs, ys)
    val tau = if (slope < 0) (-1.0 / slope).toFloat() else 0f
    return Features(deltaPMax = deltaP, decayTauSec = tau, r2 = r2.toFloat())
}

private fun linearFit(x: List<Double>, y: List<Double>): Pair<Double, Double> {
    val n = x.size
    var sumX = 0.0
    var sumY = 0.0
    var sumXX = 0.0
    var sumXY = 0.0
    for (i in 0 until n) {
        val xi = x[i]
        val yi = y[i]
        sumX += xi
        sumY += yi
        sumXX += xi * xi
        sumXY += xi * yi
    }
    val denom = n * sumXX - sumX * sumX
    if (denom == 0.0) return 0.0 to 0.0
    val slope = (n * sumXY - sumX * sumY) / denom
    val intercept = (sumY - slope * sumX) / n
    // R^2
    var ssTot = 0.0
    var ssRes = 0.0
    val meanY = sumY / n
    for (i in 0 until n) {
        val yi = y[i]
        val fi = slope * x[i] + intercept
        ssTot += (yi - meanY) * (yi - meanY)
        ssRes += (yi - fi) * (yi - fi)
    }
    val r2 = if (ssTot <= 1e-12) 0.0 else max(0.0, 1.0 - ssRes / ssTot)
    return slope to r2
}

/**
 * Map features to a 0..100 score and a confidence.
 */
fun score(features: Features, lowDeltaP: Float = 0.15f, highTauSec: Float = 0.8f): Score {
    fun clamp01(v: Float) = min(1f, max(0f, v))
    val deltaScore = clamp01((features.deltaPMax - lowDeltaP) / 0.35f) // ~0.5 hPa → 1.0
    val tauScore = clamp01((features.decayTauSec - 0.5f) / (highTauSec)) // ~1.3s → 1.0
    val raw = 0.6f * deltaScore + 0.4f * tauScore
    val value = (100f * raw).toInt().coerceIn(0, 100)
    val conf = clamp01(0.2f + 0.8f * features.r2)
    return Score(value = value, confidence = conf)
}

