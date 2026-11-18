package dev.cameracompositor

import kotlin.math.abs

/** A cheap same-angle check for static scenes with too few feature tracks. */
object FrameSignature {
    fun sample(gray: ByteArray, width: Int, height: Int): IntArray {
        return IntArray(96) { cell ->
            val x = cell % 12
            val y = cell / 12
            var sum = 0
            for (row in 0..3) for (column in 0..3) {
                val px = ((x * 4 + column + .5f) * width / 48).toInt()
                val py = ((y * 4 + row + .5f) * height / 32).toInt()
                sum += gray[py * width + px].toInt() and 0xff
            }
            sum / 16
        }
    }

    fun sameAngle(reference: IntArray, current: IntArray): Boolean {
        if (reference.size != 96 || current.size != 96) return false
        val lightChange = current.average() - reference.average()
        if (abs(lightChange) > 20) return false
        var difference = 0.0
        var changedTiles = 0
        for (i in reference.indices) {
            val residual = abs(current[i] - reference[i] - lightChange)
            difference += residual
            if (residual > 22) changedTiles++
        }
        return difference / reference.size < 6 && changedTiles <= 4
    }
}
