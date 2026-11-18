package dev.cameracompositor

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20 as GL
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Separate camera owner for spatial validation. CameraX is released before this activity starts.
 * No camera images are saved or sent. Session-local poses/points are NOT a completed scene model. */
class SpatialProbeActivity: ComponentActivity(), GLSurfaceView.Renderer {
    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var record: Button
    @Volatile private var session: Session? = null
    @Volatile private var recordingRequested=false
    private var installationRequested=false
    private var checks=0
    private var texture=0
    private var program=0
    private var positionSlot=0
    private var uvSlot=0
    private var width=1
    private var height=1
    private var configureTexture = true
    private var depthEnabled=false
    private var lastStatus=0L
    private var recordingSince=0L
    private var lastSample=0L
    private var samples=JSONArray()
    private var lastReport: String? = null
    private val sessionId=java.util.UUID.randomUUID().toString()
    private val vertices=buffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
    private val uv=buffer(FloatArray(8))
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text=lastReport
        if (uri!=null && text!=null) {
            val success=runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("No destination") }.isSuccess
            status.text=if(success) "Spatial observations exported." else "Export failed."
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        status=TextView(this).apply { text="Checking spatial tracking support…"; setPadding(20,28,20,16) }
        root.addView(status)
        surface=GLSurfaceView(this).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true; setRenderer(this@SpatialProbeActivity) }
        root.addView(surface,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply {
            text="Move gently near textured objects. Spatial observations stay in memory until export.\nGoogle Play Services for AR is governed by Google's Terms of Service and Privacy Policy."
            setPadding(20,8,20,8)
        })
        record=Button(this).apply { text="Record five seconds"; isEnabled=false; setOnClickListener { recordingRequested=true; isEnabled=false } }
        root.addView(record)
        root.addView(Button(this).apply { text="Export spatial observations"; setOnClickListener {
            if(lastReport!=null) export.launch("CameraCompositor-spatial-observations.json") else status.text="Record a spatial observation first."
        } })
        root.addView(Button(this).apply { text="Return to camera"; setOnClickListener { finish() } })
        setContentView(root)
    }
    override fun onResume() { super.onResume(); prepareSession() }
    private fun prepareSession() {
        if (isFinishing || isDestroyed) return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            status.text="Camera permission is required. Return to the camera to enable it."; return
        }
        try {
            val availability=ArCoreApk.getInstance().checkAvailability(this)
            if (availability.isTransient && checks++<8) { status.postDelayed({ if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) prepareSession() },500); return }
            if (!availability.isSupported) {
                surface.onPause(); status.text="ARCore support: $availability. The normal camera remains available."
                return
            }
            if (ArCoreApk.getInstance().requestInstall(this,!installationRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installationRequested=true; status.text="Complete Google Play Services for AR setup, then return."; return
            }
            val ar=session ?: Session(this).also { created ->
                val config=Config(created).apply {
                    planeFindingMode=Config.PlaneFindingMode.DISABLED
                    lightEstimationMode=Config.LightEstimationMode.DISABLED
                    focusMode=Config.FocusMode.AUTO
                    depthEnabled=created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    depthMode=if(depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                }
                created.configure(config); session=created
            }
            ar.resume()
            surface.queueEvent { configureTexture = true }
            surface.onResume()
            record.isEnabled=true
            status.text="Spatial tracking starting. Depth supported: $depthEnabled"
        } catch(error: Exception) { status.text="Spatial session unavailable: ${error.javaClass.simpleName}. Return to the normal camera." }
    }
    override fun onPause() {
        surface.onPause(); session?.pause(); recordingRequested=false; recordingSince=0
        super.onPause()
    }
    override fun onDestroy() { session?.close(); session=null; super.onDestroy() }
    override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?) {
        configureTexture = true
        val names=IntArray(1); GL.glGenTextures(1,names,0); texture=names[0]
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
        program=GL.glCreateProgram()
        GL.glAttachShader(program,shader(GL.GL_VERTEX_SHADER,"attribute vec2 position; attribute vec2 uvIn; varying vec2 uv; void main(){gl_Position=vec4(position,0.0,1.0);uv=uvIn;}"))
        GL.glAttachShader(program,shader(GL.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 uv; void main(){gl_FragColor=texture2D(camera,uv);}"))
        GL.glLinkProgram(program); val ok=IntArray(1); GL.glGetProgramiv(program,GL.GL_LINK_STATUS,ok,0); check(ok[0]!=0)
        positionSlot=GL.glGetAttribLocation(program,"position"); uvSlot=GL.glGetAttribLocation(program,"uvIn")
    }
    override fun onSurfaceChanged(gl: GL10?,w: Int,h: Int) {
        width=w; height=h
        configureTexture = true
        GL.glViewport(0,0,w,h)
    }
    override fun onDrawFrame(gl: GL10?) {
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        val ar=session ?: return
        val frame = try {
            if (configureTexture) {
                ar.setCameraTextureName(texture)
                ar.setDisplayGeometry(Surface.ROTATION_0,width,height)
                configureTexture = false
            }
            ar.update()
        } catch (error: Exception) {
            probeIssue("ARCore frame", error)
            return
        }
        if (frame.timestamp == 0L) return
        try {
            vertices.position(0); uv.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,vertices,Coordinates2d.TEXTURE_NORMALIZED,uv)
            vertices.position(0); uv.position(0)
            GL.glDisable(GL.GL_DEPTH_TEST); GL.glUseProgram(program); GL.glActiveTexture(GL.GL_TEXTURE0)
            GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture); GL.glUniform1i(GL.glGetUniformLocation(program,"camera"),0)
            GL.glEnableVertexAttribArray(positionSlot); GL.glEnableVertexAttribArray(uvSlot)
            GL.glVertexAttribPointer(positionSlot,2,GL.GL_FLOAT,false,0,vertices); GL.glVertexAttribPointer(uvSlot,2,GL.GL_FLOAT,false,0,uv)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP,0,4)
            GL.glDisableVertexAttribArray(positionSlot); GL.glDisableVertexAttribArray(uvSlot)
        } catch (error: Exception) {
            probeIssue("ARCore preview", error)
            return
        }
        try { sample(frame) } catch (error: Exception) { probeIssue("Spatial sample", error) }
    }

    private fun probeIssue(stage: String, error: Exception) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatus <= 1000) return
        lastStatus = now
        val message = "$stage: ${error.javaClass.simpleName}. Return to camera to scan there."
        runOnUiThread { status.text = message }
    }
    private fun sample(frame: Frame) {
        val now=SystemClock.elapsedRealtime(); val camera=frame.camera
        if(recordingRequested) { recordingRequested=false; recordingSince=now; lastSample=0; samples=JSONArray() }
        if(now-lastStatus>500) {
            lastStatus=now
            val count=samples.length()
            val message = if (camera.trackingState == TrackingState.TRACKING) {
                "Tracking active. Depth enabled: $depthEnabled. Recorded samples: $count"
            } else {
                "Tracking: ${camera.trackingFailureReason}. Keep the phone steady and include textured detail."
            }
            runOnUiThread { status.text=message }
        }
        if(recordingSince==0L) return
        if(now-recordingSince>=5000) {
            val report=JSONObject().put("schema",1).put("backend","ARCore 1.57.0").put("depthEnabled",depthEnabled)
                .put("sessionId",sessionId).put("outcome",if(samples.length()>0) "recorded" else "no_tracking")
                .put("coordinateConvention","ARCore session-local, x right/y up/-z forward; quaternion XYZW; estimated metres")
                .put("notes","Per-frame observations only. No persistent mesh, calibrated photographic judgement or movement advice. No GPS, camera images, Cloud Anchors, Geospatial API or app upload.")
                .put("samples",samples).toString(2)
            recordingSince=0
            val count=samples.length()
            runOnUiThread { lastReport=report; record.isEnabled=true; status.text=if(count>0) "Recorded $count tracked spatial samples. Export when useful." else "No tracked spatial samples. Try brighter light and textured objects." }
            return
        }
        if(camera.trackingState!=TrackingState.TRACKING || now-lastSample<250) return
        lastSample=now
        val points=JSONArray()
        frame.acquirePointCloud().use { cloud ->
            val values=cloud.points
            while(values.remaining()>=4 && points.length()<128) {
                val x=values.get(); val y=values.get(); val z=values.get(); val confidence=values.get()
                if(confidence>=.5f) points.put(JSONArray(listOf(x,y,z,confidence)))
            }
        }
        val intrinsics=camera.imageIntrinsics
        val item=JSONObject().put("timestampNs",frame.timestamp).put("cameraTimestampNs",frame.androidCameraTimestamp)
            .put("poseTranslationMeters",JSONArray(camera.pose.translation.toList()))
            .put("poseQuaternionXYZW",JSONArray(camera.pose.rotationQuaternion.toList()))
            .put("imageDimensions",JSONArray(intrinsics.imageDimensions.toList()))
            .put("focalLengthPixels",JSONArray(intrinsics.focalLength.toList())).put("principalPointPixels",JSONArray(intrinsics.principalPoint.toList()))
            .put("sparsePointsXYZConfidence",points).put("depthSample",JSONObject.NULL)
        if(depthEnabled) try {
            frame.acquireDepthImage16Bits().use { depth ->
                val plane=depth.planes[0]; val bytes=plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN); val base=bytes.position()
                val grid=JSONArray()
                for(y in 0 until 8) for(x in 0 until 8) {
                    val px=(x+.5f)*depth.width/8; val py=(y+.5f)*depth.height/8
                    val mm=bytes.getShort(base+py.toInt()*plane.rowStride+px.toInt()*plane.pixelStride).toInt() and 0xffff
                    grid.put(if(mm==0) JSONObject.NULL else mm/1000.0)
                }
                item.put("depthSample",JSONObject().put("timestampNs",depth.timestamp).put("width",depth.width).put("height",depth.height)
                    .put("grid8x8Meters",grid).put("notes","Camera-plane depth; zeros unavailable; no world projection or confidence inferred"))
            }
        } catch (_: NotYetAvailableException) { /* Depth remains explicitly absent until observations support it. */ }
        samples.put(item)
    }
    private fun shader(type: Int,source: String): Int {
        val id=GL.glCreateShader(type); GL.glShaderSource(id,source); GL.glCompileShader(id)
        val ok=IntArray(1); GL.glGetShaderiv(id,GL.GL_COMPILE_STATUS,ok,0); check(ok[0]!=0); return id
    }
    private fun buffer(values: FloatArray)=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
