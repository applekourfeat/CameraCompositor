package dev.cameracompositor

import android.graphics.Matrix
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Analysis-thread only. Tracks through consecutive frames; no translation/depth is inferred. */
class EnvironmentMotionTracker : AutoCloseable {
    private var previous = Mat()
    private var origin = emptyList<Point>()
    private var live = emptyList<Point>()
    private var width = 0
    private var height = 0
    private var anchor = false
    private var anchorToView = Matrix()
    private var latestToView = Matrix()
    var registration: ViewRegistration? = null
        private set

    fun update(bytes: ByteArray, rawWidth: Int, rawHeight: Int, bufferToView: Matrix, viewWidth: Int, viewHeight: Int) {
        registration = null
        val factor = minOf(1f, 384f / maxOf(rawWidth, rawHeight))
        val w = (rawWidth * factor).roundToInt()
        val h = (rawHeight * factor).roundToInt()
        if (w != width || h != height) { close(); width = w; height = h }
        val reducedToRaw = Matrix().apply { setScale(rawWidth.toFloat() / w, rawHeight.toFloat() / h) }
        val viewToNormalized = Matrix().apply { setScale(1f / viewWidth, 1f / viewHeight) }
        latestToView = Matrix().apply {
            setConcat(viewToNormalized, Matrix().apply { setConcat(bufferToView, reducedToRaw) })
        }
        val original = Mat(rawHeight,rawWidth,CvType.CV_8UC1).apply { put(0,0,bytes) }
        val current = Mat()
        try {
            Imgproc.resize(original, current, Size(w.toDouble(),h.toDouble()),0.0,0.0,Imgproc.INTER_AREA)
            if (!previous.empty() && live.size >= 30) {
                val from = MatOfPoint2f(*live.toTypedArray()); val to = MatOfPoint2f(); val back = MatOfPoint2f()
                val status = MatOfByte(); val reverse = MatOfByte(); val errors = MatOfFloat(); val backErrors = MatOfFloat()
                try {
                    Video.calcOpticalFlowPyrLK(previous,current,from,to,status,errors,Size(21.0,21.0),3)
                    Video.calcOpticalFlowPyrLK(current,previous,to,back,reverse,backErrors,Size(21.0,21.0),3)
                    val next = to.toArray(); val returned = back.toArray(); val ok = status.toArray(); val reversed = reverse.toArray(); val error = errors.toArray()
                    val keep = live.indices.filter { ok[it].toInt()!=0 && reversed[it].toInt()!=0 && error[it]<30 &&
                        hypot(returned[it].x-live[it].x,returned[it].y-live[it].y)<1 &&
                        next[it].x>=0 && next[it].x<w && next[it].y>=0 && next[it].y<h }
                    origin=keep.map { origin[it] }; live=keep.map { next[it] }
                    if (anchor && live.size>=24) registration = measure()
                } finally { listOf(from,to,back,status,reverse,errors,backErrors).forEach { it.release() } }
            } else { live=emptyList(); origin=emptyList() }
            current.copyTo(previous)
        } finally { original.release(); current.release() }
    }
    /** Snapshot is the new graph node. Its relation to the previous node is returned first. */
    fun checkpoint(): ViewRegistration? {
        val link=registration
        val corners=MatOfPoint()
        try {
            if (!previous.empty()) {
                Imgproc.goodFeaturesToTrack(previous,corners,220,.01,5.0)
                live=corners.toArray().toList(); origin=live.map { Point(it.x,it.y) }; anchor=true
                anchorToView = Matrix(latestToView)
            }
        } finally { corners.release() }
        registration=null
        return link
    }
    private fun measure(): ViewRegistration? {
        val a=MatOfPoint2f(*origin.toTypedArray()); val b=MatOfPoint2f(*live.toTypedArray()); val mask=Mat()
        val h=Calib3d.findHomography(a,b,Calib3d.RANSAC,2.0,mask)
        val projected=MatOfPoint2f()
        val hull=MatOfInt(); val hullPoints=MatOfPoint2f()
        try {
            if (h.empty() || mask.empty()) return null
            val flags=ByteArray(mask.rows()); mask.get(0,0,flags)
            val indices=origin.indices.filter { flags[it].toInt()!=0 }
            if (indices.size<18) return null
            Core.perspectiveTransform(a,projected,h)
            val predicted=projected.toArray()
            val errors=indices.map { hypot(predicted[it].x-live[it].x,predicted[it].y-live[it].y) }.sorted()
            val anchors=MatOfPoint(*indices.map { origin[it] }.toTypedArray())
            val coverage=try {
                Imgproc.convexHull(anchors,hull)
                val positions=anchors.toArray()
                hullPoints.fromArray(*hull.toArray().map { positions[it] }.toTypedArray())
                (Imgproc.contourArea(hullPoints)/(width.toDouble()*height)).toFloat()
            } finally { anchors.release() }
            val pixelCoefficients = DoubleArray(9)
            h.get(0, 0, pixelCoefficients)
            val pixelWarp = Matrix().apply { setValues(FloatArray(9) { pixelCoefficients[it].toFloat() }) }
            val viewToAnchor = Matrix()
            if (!anchorToView.invert(viewToAnchor)) return null
            val viewWarp = Matrix().apply {
                setConcat(latestToView, Matrix().apply { setConcat(pixelWarp, viewToAnchor) })
            }
            val inverse = Matrix()
            if (!viewWarp.invert(inverse)) return null
            val forward = coefficients(viewWarp) ?: return null
            val backward = coefficients(inverse) ?: return null
            val center = forward.project(ScenePoint(.5f, .5f)) ?: return null
            val corners = forward.project(SubjectBox(0f, 0f, 1f, 1f)) ?: return null
            if (corners.right - corners.left !in .5f..1.85f || corners.bottom - corners.top !in .5f..1.85f) return null
            return ViewRegistration(indices.size,live.size,hypot(center.x-.5f,center.y-.5f),
                errors[errors.size/2].toFloat(),coverage,forward,backward)
        } finally { listOf(a,b,mask,h,projected,hull,hullPoints).forEach { it.release() } }
    }

    private fun coefficients(matrix: Matrix): ImageProjection? {
        val values = FloatArray(9)
        matrix.getValues(values)
        return if (values.all(Float::isFinite)) ImageProjection(values.toList()) else null
    }
    override fun close() {
        previous.release(); previous=Mat(); origin=emptyList(); live=emptyList(); registration=null; anchor=false
    }
}
