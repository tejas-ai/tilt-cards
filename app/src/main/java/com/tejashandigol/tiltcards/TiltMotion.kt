package com.tejashandigol.tiltcards

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.hypot

internal const val WALLPAPER_POWER_ENABLED = "wallpaper_power_enabled"

internal fun neutralCard(count: Int) = (count - 1) / 2

internal class CardSelection(private val count: Int) {
    var index = neutralCard(count)
        private set

    fun select(index: Int) {
        this.index = index.coerceIn(0, count - 1)
    }

    fun update(tilt: Float): Int {
        // Small wrist movements select photos, with a release margin for hand jitter.
        val boundaries = if (count == 2) floatArrayOf(7f)
            else floatArrayOf(-7f, 7f, 14f)
        while (index < count - 1 &&
            tilt >= boundaries[index] + (if (boundaries[index] < 0) 2.5f else 0f)) index++
        while (index > 0 &&
            tilt <= boundaries[index - 1] - (if (boundaries[index - 1] > 0) 2.5f else 0f)) index--
        return index
    }
}

internal class TiltMotion(context: Context, private val onTilt: (Float) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val windows = context.getSystemService(WindowManager::class.java)
    private val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val preferences = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
    private val orientation = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var reference: FloatArray? = null
    private val current = FloatArray(4)
    private var rotationFiltered = 0f
    private var running = false
    private var firstSample = true
    private var x = 0f
    private var y = 0f
    private var z = 0f
    private var lastTilt = Float.NaN
    private var restingTilt = Float.NaN

    fun start(): Boolean {
        if (!preferences.getBoolean(WALLPAPER_POWER_ENABLED, true)) return false
        if (running) return true
        val selected = if (preferences.getBoolean("fixed_mode", false)) orientation ?: gravity else gravity
        if (selected == null) return false
        reference = null
        rotationFiltered = 0f
        firstSample = true
        lastTilt = Float.NaN
        restingTilt = Float.NaN
        running = sensors?.registerListener(this, selected, SensorManager.SENSOR_DELAY_GAME) == true
        return running
    }

    fun stop() {
        if (running) sensors?.unregisterListener(this)
        running = false
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getQuaternionFromVector(current, event.values)
            if (reference == null) reference = current.copyOf()
            val rotation = windows?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            val angle = RightTilt.degrees(reference!!, current, rotation)
            rotationFiltered += (angle - rotationFiltered) * 0.35f
            emit(rotationFiltered)
            return
        }
        val weight = if (firstSample) 1f else 0.24f
        x += (event.values[0] - x) * weight
        y += (event.values[1] - y) * weight
        z += (event.values[2] - z) * weight
        firstSample = false

        val (horizontal, vertical) = when (windows?.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        val absoluteTilt = Math.toDegrees(atan2(-horizontal.toDouble(), hypot(vertical.toDouble(), z.toDouble()))).toFloat()
        if (restingTilt.isNaN()) restingTilt = absoluteTilt
        val tilt = absoluteTilt - restingTilt
        emit(tilt)
    }

    private fun emit(tilt: Float) {
        if (lastTilt.isNaN() || kotlin.math.abs(tilt - lastTilt) >= 0.15f) {
            lastTilt = tilt
            onTilt(tilt)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
