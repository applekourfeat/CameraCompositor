package dev.cameracompositor

import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Bound analysis work to at most 10 frames per second. Preview runs independently. */
class ThrottledAnalyzer(private val delegate: ImageAnalysis.Analyzer) : ImageAnalysis.Analyzer {
    private var lastFrameMs = Long.MIN_VALUE
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameMs != Long.MIN_VALUE && now - lastFrameMs < 100) {
            image.close()
            return
        }
        lastFrameMs = now
        delegate.analyze(image) // MlKitAnalyzer closes the image when its asynchronous task finishes.
    }
    override fun getTargetCoordinateSystem(): Int = delegate.targetCoordinateSystem
    override fun getDefaultTargetResolution(): Size = Size(640, 480)
    override fun updateTransform(matrix: Matrix?) = delegate.updateTransform(matrix)
}
