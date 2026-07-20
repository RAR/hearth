package com.rar.hearth.vaca

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/** Ambient light readings, throttled: emit on >=20% change (min 5 lux) or every 30 s. */
class LightSensorReporter(
    context: Context,
    private val onLux: (Float) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var lastSentAt = 0L
    private var lastValue = -1f

    val hasSensor: Boolean get() = sensor != null

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values.firstOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        val changedEnough = lastValue < 0 || abs(lux - lastValue) > maxOf(5f, lastValue * 0.2f)
        if (changedEnough || now - lastSentAt >= 30_000) {
            lastSentAt = now
            lastValue = lux
            onLux(lux)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
