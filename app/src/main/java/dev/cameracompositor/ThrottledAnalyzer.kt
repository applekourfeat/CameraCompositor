package dev.cameracompositor

import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Target 10 Hz normally and up to 30 Hz during temporal observation. Preview is independent. */
class ThrottledAnalyzer(private val delegate: ImageAnalysis.Analyzer,
                        private val intervalMs: () -> Long = { 100 }) : ImageAnalysis.Analyzer {
    private var lastFrameMs = Long.MIN_VALUE
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (lastFrameMs != Long.MIN_VALUE && now - lastFrameMs < intervalMs()) {
            image.close()
            return
        }
        lastFrameMs = now
        delegate.analyze(image) // SceneFrameAnalyzer closes the image after its work finishes.
    }
    override fun getTargetCoordinateSystem(): Int = delegate.targetCoordinateSystem
    override fun getDefaultTargetResolution(): Size = Size(640, 480)
    override fun updateTransform(matrix: Matrix?) = delegate.updateTransform(matrix)
}
