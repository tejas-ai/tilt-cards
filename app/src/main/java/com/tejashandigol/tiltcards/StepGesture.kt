package com.tejashandigol.tiltcards

import kotlin.math.abs

internal class StepGesture {
    private var armed = true

    fun reset() { armed = true }

    fun update(degrees: Float): Int {
        if (abs(degrees) <= 1.2f) {
            armed = true
            return 0
        }
        if (!armed || degrees < 3f) return 0
        armed = false
        return 1
    }
}
