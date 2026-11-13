package com.tejashandigol.tiltcards

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

internal class SnapHaptic(private val context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var previous: Int? = null
    private var lastPulse = 0L

    fun reset() { previous = null }

    fun update(index: Int) {
        val changed = previous != null && previous != index
        previous = index
        val now = SystemClock.uptimeMillis()
        if (!changed || now - lastPulse < 180 || vibrator?.hasVibrator() != true) return
        if (Settings.System.getInt(context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        lastPulse = now
        vibrator.vibrate(VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
