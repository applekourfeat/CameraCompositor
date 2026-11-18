package dev.cameracompositor

import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.hypot

/** Analysis-thread only. Tracks through consecutive frames; no translation/depth is inferred. */
class EnvironmentMotionTracker : AutoCloseable {
    private var previous = Mat()
    private var origin = emptyList<Point>()
    private var live = emptyList<Point>()
    private var width = 0
    private var height = 0
    private var anchor = false
    var registration: ViewRegistration? = null
        private set

    fun update(bytes: ByteArray, w: Int, h: Int) {
        if (w != width || h != height) { close(); width = w; height = h }
        val current = Mat(h,w,CvType.CV_8UC1).apply { put(0,0,bytes) }
        try {
            registration = null
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
                    if (anchor && live.size>=30) registration = measure()
                } finally { listOf(from,to,back,status,reverse,errors,backErrors).forEach { it.release() } }
            } else { live=emptyList(); origin=emptyList() }
            previous.release(); previous=current.clone()
        } finally { current.release() }
    }
    /** Snapshot is the new graph node. Its relation to the previous node is returned first. */
    fun checkpoint(): ViewRegistration? {
        val link=registration
        val corners=MatOfPoint()
        try {
            if (!previous.empty()) {
                Imgproc.goodFeaturesToTrack(previous,corners,400,.01,8.0)
                live=corners.toArray().toList(); origin=live.map { Point(it.x,it.y) }; anchor=true
            }
        } finally { corners.release() }
        registration=null
        return link
    }
    private fun measure(): ViewRegistration? {
        val a=MatOfPoint2f(*origin.toTypedArray()); val b=MatOfPoint2f(*live.toTypedArray()); val mask=Mat()
        val h=Calib3d.findHomography(a,b,Calib3d.RANSAC,2.0,mask)
        val projected=MatOfPoint2f(); val center=MatOfPoint2f(Point(width*.5,height*.5)); val moved=MatOfPoint2f()
        val hull=MatOfInt(); val hullPoints=MatOfPoint2f()
        try {
            if (h.empty() || mask.empty()) return null
            val flags=ByteArray(mask.rows()); mask.get(0,0,flags)
            val indices=origin.indices.filter { flags[it].toInt()!=0 }
            if (indices.size<24) return null
            Core.perspectiveTransform(a,projected,h); Core.perspectiveTransform(center,moved,h)
            val predicted=projected.toArray(); val c=moved.toArray().single()
            val errors=indices.map { hypot(predicted[it].x-live[it].x,predicted[it].y-live[it].y) }.sorted()
            val anchors=MatOfPoint(*indices.map { origin[it] }.toTypedArray())
            val coverage=try {
                Imgproc.convexHull(anchors,hull)
                val positions=anchors.toArray()
                hullPoints.fromArray(*hull.toArray().map { positions[it] }.toTypedArray())
                (Imgproc.contourArea(hullPoints)/(width.toDouble()*height)).toFloat()
            } finally { anchors.release() }
            return ViewRegistration(indices.size,live.size,hypot((c.x-width*.5)/width,(c.y-height*.5)/height).toFloat(),
                errors[errors.size/2].toFloat(),coverage)
        } finally { listOf(a,b,mask,h,projected,center,moved,hull,hullPoints).forEach { it.release() } }
    }
    override fun close() {
        previous.release(); previous=Mat(); origin=emptyList(); live=emptyList(); registration=null; anchor=false
    }
}
