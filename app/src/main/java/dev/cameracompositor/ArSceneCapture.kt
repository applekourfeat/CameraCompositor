package dev.cameracompositor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** In-process transfer avoids binder size limits and persistent camera-image files. */
object SpatialSweepTransfer {
    private val batches=ConcurrentHashMap<String,EnvironmentBatch>()
    fun offer(id: String,batch: EnvironmentBatch) {
        batches.keys.filter { it!=id }.forEach { discard(it) }
        batches.put(id,batch)?.close()
    }
    fun take(id: String)=batches.remove(id)
    fun discard(id: String) { batches.remove(id)?.close() }
}

/** Capture image, SDK pose and view-aligned depth from the same AR frame. No guessed rotations. */
object ArSceneCapture {
    fun capture(frame: Frame,session: Session,sessionId: String,id: Int,viewportWidth: Int,viewportHeight: Int,
                maxZoom: Float): EnvironmentFrame {
        require(frame.camera.trackingState==TrackingState.TRACKING)
        return frame.acquireCameraImage().use { image ->
            require(image.width.toLong()*image.height<=2_000_000 && image.width%2==0 && image.height%2==0)
            require(abs(image.timestamp-frame.androidCameraTimestamp)<5_000_000)
            val w=image.width; val h=image.height
            val bytes=ByteArray(w*h*3/2)
            val planes=image.planes
            val luminance=planes[0]; val yBuffer=luminance.buffer.duplicate(); val yBase=yBuffer.position()
            for(y in 0 until h) {
                if(luminance.pixelStride==1) { yBuffer.position(yBase+y*luminance.rowStride); yBuffer.get(bytes,y*w,w) }
                else for(x in 0 until w) bytes[y*w+x]=yBuffer.get(yBase+y*luminance.rowStride+x*luminance.pixelStride)
            }
            for(channel in 0..1) {
                val p=planes[2-channel]; val chroma=p.buffer.duplicate(); val base=chroma.position()
                for(y in 0 until h/2) for(x in 0 until w/2) bytes[w*h+y*w+2*x+channel]=chroma.get(base+y*p.rowStride+x*p.pixelStride)
            }
            val yuv=Mat(h+h/2,w,CvType.CV_8UC1); val rgba=Mat()
            var visible: Bitmap?=null
            try {
                yuv.put(0,0,bytes); Imgproc.cvtColor(yuv,rgba,Imgproc.COLOR_YUV2RGBA_NV21)
                val raw=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                val rawPoints=floatArrayOf(0f,0f,w.toFloat(),0f,0f,h.toFloat(),w.toFloat(),h.toFloat())
                val viewPoints=FloatArray(8)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,rawPoints,Coordinates2d.VIEW,viewPoints)
                val rawToView=Matrix(); check(rawToView.setPolyToPoly(rawPoints,0,viewPoints,0,4))
                try {
                    Utils.matToBitmap(rgba,raw)
                    val pictureWidth=480; val pictureHeight=(pictureWidth.toFloat()*viewportHeight/viewportWidth).toInt()
                    visible=Bitmap.createBitmap(pictureWidth,pictureHeight,Bitmap.Config.ARGB_8888)
                    val scale=Matrix().apply { setScale(pictureWidth.toFloat()/viewportWidth,pictureHeight.toFloat()/viewportHeight) }
                    Canvas(visible).drawBitmap(raw,Matrix().apply { setConcat(scale,rawToView) },null)
                } finally { raw.recycle() }
                val camera=frame.camera; val intrinsics=camera.imageIntrinsics
                require(intrinsics.imageDimensions.contentEquals(intArrayOf(w,h)))
                var pointCount=0
                frame.acquirePointCloud().use { cloud -> val points=cloud.points; while(points.remaining()>=4) {
                    points.get(); points.get(); points.get(); if(points.get()>=.5f) pointCount++
                } }
                var depthGrid: List<Float?>?=null
                if(session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) try {
                    frame.acquireDepthImage16Bits().use { depth ->
                        if(abs(depth.timestamp-frame.timestamp)<50_000_000) {
                            val input=FloatArray(128) { n -> ((if(n%2==0) (n/2)%8 else (n/2)/8)+.5f)/8 }
                            val texture=FloatArray(128)
                            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED,input,Coordinates2d.TEXTURE_NORMALIZED,texture)
                            val p=depth.planes[0]; val data=p.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN); val base=data.position()
                            depthGrid=List(64) { cell ->
                                val x=texture[cell*2]; val y=texture[cell*2+1]
                                if(!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) null else {
                                    val px=(x*depth.width).toInt().coerceIn(0,depth.width-1)
                                    val py=(y*depth.height).toInt().coerceIn(0,depth.height-1)
                                    val mm=data.getShort(base+py*p.rowStride+px*p.pixelStride).toInt() and 0xffff
                                    if(mm<100) null else mm/1000f
                                }
                            }
                        }
                    }
                } catch (_: NotYetAvailableException) { /* Keep depth absent. */ }
                val normalized=FloatArray(9); rawToView.getValues(normalized)
                for(i in 0..2) normalized[i]/=viewportWidth
                for(i in 3..5) normalized[i]/=viewportHeight
                val spatial=SpatialEvidence(sessionId,frame.timestamp,session.cameraConfig.cameraId,camera.pose.translation.toList(),
                    camera.pose.rotationQuaternion.toList(),intrinsics.imageDimensions.toList(),intrinsics.focalLength.toList(),
                    intrinsics.principalPoint.toList(),depthGrid,pointCount,normalized.toList())
                val view=EnvironmentView(id,SystemClock.elapsedRealtime(),1f,maxZoom,null,if(id==0)null else id-1,spatial)
                val scan=ScanFrame(visible,bytes.copyOf(w*h),w,h,rawToView,Size(viewportWidth,viewportHeight),image.timestamp,null)
                EnvironmentFrame(view,scan)
            } catch(error: Exception) { visible?.recycle(); throw error }
            finally { yuv.release(); rgba.release() }
        }
    }
}
