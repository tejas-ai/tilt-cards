package com.tejashandigol.tiltcards

import kotlin.math.abs
import kotlin.math.atan2

internal object RightTilt {
    fun degrees(rest: FloatArray, current: FloatArray, rotation: Int): Float {
        var w = rest[0] * current[0] + rest[1] * current[1] +
            rest[2] * current[2] + rest[3] * current[3]
        var x = rest[0] * current[1] - rest[1] * current[0] -
            rest[2] * current[3] + rest[3] * current[2]
        var y = rest[0] * current[2] + rest[1] * current[3] -
            rest[2] * current[0] - rest[3] * current[1]
        var z = rest[0] * current[3] - rest[1] * current[2] +
            rest[2] * current[1] - rest[3] * current[0]
        if (w < 0) { w = -w; x = -x; y = -y; z = -z }
        val wrist = when (rotation) {
            1 -> -x
            2 -> -y
            3 -> x
            else -> y
        }
        val rightAxis = if (abs(wrist) > abs(z)) wrist else -z
        return Math.toDegrees(2.0 * atan2(rightAxis.toDouble(), w.toDouble())).toFloat()
    }
}
