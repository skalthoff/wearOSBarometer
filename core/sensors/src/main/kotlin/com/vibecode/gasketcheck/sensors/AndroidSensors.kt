package com.vibecode.gasketcheck.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class AndroidSensors private constructor(
    override val pressure: PressureStream?,
    override val motion: MotionStream?
) : SensorsProvider {

    companion object {
        fun create(context: Context): AndroidSensors {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

            val baroSensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
            val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            val pressureStream = baroSensor?.let { AndroidPressureStream(sm, it) }
            val motionStream = accelSensor?.let { AndroidMotionStream(sm, it) }

            return AndroidSensors(pressureStream, motionStream)
        }
    }
}

private class AndroidPressureStream(
    private val sensorManager: SensorManager,
    private val sensor: Sensor
) : PressureStream {

    override val samples: Flow<PressureSample> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_PRESSURE || event.values.isEmpty()) return
                val valueHpa = event.values[0]
                trySend(PressureSample(event.timestamp, valueHpa))
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        // SENSOR_DELAY_FASTEST requests max rate; we'll downsample later.
        sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        awaitClose { sensorManager.unregisterListener(listener) }
    }
        // Conflate to drop intermediate items under backpressure while keeping latest.
        .conflate()
        // Defensive buffer to avoid producer blocking UI thread callbacks.
        .buffer(capacity = 64)
}

private class AndroidMotionStream(
    private val sensorManager: SensorManager,
    private val sensor: Sensor
) : MotionStream {

    override val samples: Flow<MotionSample> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                trySend(MotionSample(event.timestamp, ax, ay, az))
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        awaitClose { sensorManager.unregisterListener(listener) }
    }
        .conflate()
        .buffer(capacity = 128)
}

