package com.vibecode.gasketcheck.store

import kotlinx.serialization.Serializable

@Serializable
data class Result(
    val timestampMs: Long,
    val deltaPHpa: Float,
    val tauSec: Float,
    val score: Int,
    val confidence: Float
)

@Serializable
data class Calibration(
    val lowDeltaPThreshold: Float = 0.15f,
    val highTauThresholdSec: Float = 0.8f,
    val version: Int = 1
)

// TODO: Implement DataStore repositories for results history and calibration.

