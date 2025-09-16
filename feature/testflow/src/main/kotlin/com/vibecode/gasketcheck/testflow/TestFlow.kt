package com.vibecode.gasketcheck.testflow

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
// Use standard Wear Button for actions; keep UI minimal.
import com.vibecode.gasketcheck.sensors.AndroidSensors
import com.vibecode.gasketcheck.sensors.MotionSample
import com.vibecode.gasketcheck.sensors.PressureSample
import com.vibecode.gasketcheck.sensors.downsample
import com.vibecode.gasketcheck.signal.Features
import com.vibecode.gasketcheck.signal.PressReleaseDetector
import com.vibecode.gasketcheck.signal.Phase
import com.vibecode.gasketcheck.signal.computeFeatures
import com.vibecode.gasketcheck.signal.score
import com.vibecode.gasketcheck.store.GasketStore
import com.vibecode.gasketcheck.store.Result as StoreResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class UiState(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    val deltaPHpa: Float = 0f,
    val features: Features? = null,
    val scoreValue: Int? = null,
    val confidence: Float? = null,
    val error: String? = null,
    val hasBarometer: Boolean = true,
    val results: List<StoreResult> = emptyList(),
    val showHistory: Boolean = false
)

@Composable
fun TestFlowEntry() {
    val context = LocalContext.current
    var ui by remember { mutableStateOf(UiState(message = "Press Start to begin")) }
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    val repo = remember(context) { GasketStore.repository(context) }

    fun stopJobs() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    DisposableEffect(Unit) {
        onDispose { stopJobs() }
    }

    // Observe results history
    LaunchedEffect(Unit) {
        repo.results.onEach { list ->
            ui = ui.copy(results = list)
        }.launchIn(this)
    }

    fun startTest(context: Context) {
        stopJobs()
        val pm = context.packageManager
        val hasBaro = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER)
        if (!hasBaro) {
            ui = ui.copy(
                phase = Phase.Error,
                error = "Barometer not available on this device.",
                hasBarometer = false
            )
            return
        }

        val sensors = AndroidSensors.create(context)
        if (sensors.pressure == null) {
            ui = ui.copy(
                phase = Phase.Error,
                error = "Barometer not available on this device.",
                hasBarometer = false
            )
            return
        }
        val detector = PressReleaseDetector()
        ui = UiState(phase = Phase.Baseline, message = "Stabilizing baseline… Hold watch steady.")

        var lastMotion: MotionSample? = null
        var lastPhase: Phase = Phase.Baseline
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        val motionJob = sensors.motion?.samples
            ?.downsample(50)
            ?.onEach { m -> lastMotion = m }
            ?.launchIn(scope)

        val pressureJob = sensors.pressure!!.samples
            .downsample(25)
            .onEach { p: PressureSample ->
                val motionOk = lastMotion?.let { m ->
                    // Simple motion gating: check per-sample accel change magnitude
                    val prev = lastMotion
                    if (prev == null) true else {
                        val dx = m.ax - prev.ax
                        val dy = m.ay - prev.ay
                        val dz = m.az - prev.az
                        val mag = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                        mag < 1.5f // m/s^2 threshold
                    }
                } ?: true

                val st = detector.onSample(p.timeNanos, p.hPa, motionOk)
                if (st.phase != lastPhase) {
                    when (st.phase) {
                        Phase.Press -> shortHaptic(vibrator)
                        Phase.Release -> shortHaptic(vibrator)
                        Phase.Complete -> successHaptic(vibrator)
                        else -> {}
                    }
                    lastPhase = st.phase
                }
                when (st.phase) {
                    Phase.Baseline -> {
                        ui = ui.copy(
                            phase = Phase.Baseline,
                            message = "Stabilizing baseline…",
                            deltaPHpa = 0f,
                            error = null
                        )
                    }
                    Phase.Press -> {
                        ui = ui.copy(
                            phase = Phase.Press,
                            message = "Press and hold now…",
                            deltaPHpa = st.deltaPMax,
                            error = null
                        )
                    }
                    Phase.Release -> {
                        ui = ui.copy(
                            phase = Phase.Release,
                            message = "Release and stay still…",
                            deltaPHpa = st.deltaPMax,
                            error = null
                        )
                    }
                    Phase.Complete -> {
                        // Extract release window and compute features + score
                        val samples = detector.windowSamples()
                        val release = samples.filter { it.first >= st.releaseStartNs }
                        val peak = st.baselineHpa + st.deltaPMax
                        val feats = computeFeatures(st.baselineHpa, peak, release)
                        val s = score(feats)
                        // Persist result
                        scope.launch {
                            repo.addResult(
                                StoreResult(
                                    timestampMs = System.currentTimeMillis(),
                                    deltaPHpa = feats.deltaPMax,
                                    tauSec = feats.decayTauSec,
                                    score = s.value,
                                    confidence = s.confidence
                                )
                            )
                        }
                        ui = ui.copy(
                            phase = Phase.Complete,
                            message = if (s.value >= 70) "Likely OK" else if (s.value >= 40) "Inconclusive" else "Likely Compromised",
                            features = feats,
                            scoreValue = s.value,
                            confidence = s.confidence,
                            error = null
                        )
                    }
                    Phase.Idle -> {
                        ui = ui.copy(phase = Phase.Idle, message = "Press Start to begin")
                    }
                    Phase.Error -> {
                        ui = ui.copy(phase = Phase.Error, error = st.error ?: "Unknown error")
                    }
                }
            }
            .launchIn(scope)

        jobs = listOfNotNull(pressureJob, motionJob)
    }

    TestScreen(
        ui = ui,
        onStart = { startTest(context) },
        onReset = {
        stopJobs()
        ui = UiState(message = "Press Start to begin")
        },
        onShowHistory = { ui = ui.copy(showHistory = true) },
        onHideHistory = { ui = ui.copy(showHistory = false) },
        onClearHistory = { scope.launch { repo.clearResults() } }
    )
}

@Composable
private fun TestScreen(
    ui: UiState,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onShowHistory: () -> Unit,
    onHideHistory: () -> Unit,
    onClearHistory: () -> Unit
) {
    if (ui.showHistory) {
        HistoryScreen(results = ui.results, onBack = onHideHistory, onClear = onClearHistory)
        return
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Keep the screen awake during active test phases
        val active = ui.phase == Phase.Baseline || ui.phase == Phase.Press || ui.phase == Phase.Release
        KeepAwake(enabled = active)
        when (ui.phase) {
            Phase.Idle -> {
                Text("Gasket Check", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Dry environment. Light press when prompted.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("This is guidance only; not a certified test.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStart) { Text("Start Test") }
                Spacer(Modifier.height(6.dp))
                Button(onClick = onShowHistory) { Text("History") }
            }
            Phase.Baseline -> {
                Text("Baseline", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(ui.message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator()
            }
            Phase.Press -> {
                Text("Press & Hold", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("ΔP: ${String.format("%.2f", ui.deltaPHpa)} hPa", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator()
            }
            Phase.Release -> {
                Text("Release", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Measuring decay…", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator()
            }
            Phase.Complete -> {
                val s = ui.scoreValue ?: 0
                val conf = ui.confidence ?: 0f
                Text("Result: $s / 100", textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                val feats = ui.features
                if (feats != null) {
                    Text("ΔP ${String.format("%.2f", feats.deltaPMax)} hPa, τ ${String.format("%.2f", feats.decayTauSec)} s", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                }
                Text("Confidence: ${String.format("%.0f", conf * 100)}%", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onReset) { Text("Run Again") }
                Spacer(Modifier.height(6.dp))
                Button(onClick = onShowHistory) { Text("History") }
            }
            Phase.Error -> {
                Text("Error", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(ui.error ?: "Unknown", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onReset) { Text("Back") }
            }
        }
    }
}

@Composable
private fun HistoryScreen(results: List<StoreResult>, onBack: () -> Unit, onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("History", textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (results.isEmpty()) {
            Text("No results yet.", textAlign = TextAlign.Center)
        } else {
            val last = results.takeLast(10).reversed()
            for (r in last) {
                val line = formatResultLine(r)
                Text(line, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack) { Text("Back") }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onClear) { Text("Clear History") }
    }
}

private fun formatResultLine(r: StoreResult): String {
    // Format: HH:mm - Score (Conf%) ΔP hPa, τ s
    val time = java.time.Instant.ofEpochMilli(r.timestampMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    val hhmm = "%02d:%02d".format(time.hour, time.minute)
    val confPct = (r.confidence * 100).toInt()
    return "$hhmm  •  ${r.score} (${confPct}%)  •  ΔP ${"%.2f".format(r.deltaPHpa)} hPa, τ ${"%.2f".format(r.tauSec)} s"
}

@Composable
private fun KeepAwake(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context as? Activity
        if (enabled) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun shortHaptic(vibrator: Vibrator) {
    if (Build.VERSION.SDK_INT >= 26) {
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(40)
    }
}

private fun successHaptic(vibrator: Vibrator) {
    if (Build.VERSION.SDK_INT >= 26) {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 40, 40, 80), intArrayOf(0, 180, 0, 200), -1)
        vibrator.vibrate(effect)
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(longArrayOf(0, 40, 40, 80), -1)
    }
}
