package com.vibecode.gasketcheck.sensors

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/**
 * Downsample a stream of timestamped samples to approximately [targetHz].
 * Emits the latest sample when the minimum interval has passed.
 */
fun Flow<PressureSample>.downsample(targetHz: Int): Flow<PressureSample> = flow {
    require(targetHz > 0)
    val minDeltaNanos = 1_000_000_000L / targetHz
    var lastEmitTime = Long.MIN_VALUE
    var pending: PressureSample? = null
    collect { sample ->
        // Always keep the latest sample
        pending = sample
        if (lastEmitTime == Long.MIN_VALUE) {
            lastEmitTime = sample.timeNanos
            emit(sample)
            pending = null
        } else if (sample.timeNanos - lastEmitTime >= minDeltaNanos) {
            val toEmit = pending
            if (toEmit != null) {
                emit(toEmit)
                lastEmitTime = toEmit.timeNanos
                pending = null
            }
        }
    }
}

fun Flow<MotionSample>.downsample(targetHz: Int): Flow<MotionSample> = flow {
    require(targetHz > 0)
    val minDeltaNanos = 1_000_000_000L / targetHz
    var lastEmitTime = Long.MIN_VALUE
    var pending: MotionSample? = null
    collect { sample ->
        pending = sample
        if (lastEmitTime == Long.MIN_VALUE) {
            lastEmitTime = sample.timeNanos
            emit(sample)
            pending = null
        } else if (sample.timeNanos - lastEmitTime >= minDeltaNanos) {
            val toEmit = pending
            if (toEmit != null) {
                emit(toEmit)
                lastEmitTime = toEmit.timeNanos
                pending = null
            }
        }
    }
}

