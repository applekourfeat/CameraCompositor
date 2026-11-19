package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.sqrt

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
        return similar(reference, current, maxLightChange = 20, maxDifference = 6, maxChanged = 4, changedThreshold = 22)
    }

    fun sameScene(reference: IntArray, current: IntArray): Boolean {
        return similar(reference, current, maxLightChange = 45, maxDifference = 12, maxChanged = 15, changedThreshold = 30)
    }

    /** Tap-to-focus can change exposure between the tap and the first stored image. */
    fun sameSelectedView(reference: IntArray, current: IntArray): Boolean {
        if (sameAngle(reference, current)) return true
        if (reference.size != 96 || current.size != 96) return false
        val firstMean = reference.average()
        val secondMean = current.average()
        var firstVariance = 0.0
        var covariance = 0.0
        for (i in reference.indices) {
            val base = reference[i] - firstMean
            firstVariance += base * base
            covariance += base * (current[i] - secondMean)
        }
        if (firstVariance < reference.size * 144) return false
        val gain = covariance / firstVariance
        if (gain !in .53..1.78) return false
        var errorSquared = 0.0
        var changed = 0
        for (i in reference.indices) {
            val error = current[i] - secondMean - (reference[i] - firstMean) * gain
            errorSquared += error * error
            if (abs(error) > 17) changed++
        }
        return sqrt(errorSquared / firstVariance) < .19 && changed <= 3
    }

    private fun similar(reference: IntArray, current: IntArray, maxLightChange: Int, maxDifference: Int,
                        maxChanged: Int, changedThreshold: Int): Boolean {
        if (reference.size != 96 || current.size != 96) return false
        val lightChange = current.average() - reference.average()
        if (abs(lightChange) > maxLightChange) return false
        var difference = 0.0
        var changedTiles = 0
        for (i in reference.indices) {
            val residual = abs(current[i] - reference[i] - lightChange)
            difference += residual
            if (residual > changedThreshold) changedTiles++
        }
        return difference / reference.size < maxDifference && changedTiles <= maxChanged
    }
}
