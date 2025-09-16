package com.vibecode.gasketcheck.sensors

import kotlinx.coroutines.flow.Flow

data class PressureSample(val timeNanos: Long, val hPa: Float)
data class MotionSample(val timeNanos: Long, val ax: Float, val ay: Float, val az: Float)

interface PressureStream {
    val samples: Flow<PressureSample>
}

interface MotionStream {
    val samples: Flow<MotionSample>
}

/**
 * Optional convenience interface to access available streams from a single provider.
 */
interface SensorsProvider {
    val pressure: PressureStream?
    val motion: MotionStream?
}

