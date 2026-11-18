package dev.cameracompositor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

data class EnvironmentFrame(val view: EnvironmentView, val frame: ScanFrame)
class EnvironmentBatch(val map: EnvironmentMap, val frames: List<EnvironmentFrame>) : AutoCloseable {
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close() { if(closed.compareAndSet(false,true)) frames.forEach { it.frame.image.recycle() } }
}

/** Main-thread acquisition; a bounded in-memory scene observation, with no disk writes/uploads. */
class EnvironmentSweep(private val analyzer: SceneFrameAnalyzer, private val zoom: () -> Float,
                       private val maxZoom: () -> Float,
                       private val onFinished: (EnvironmentBatch?) -> Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val frames=mutableListOf<EnvironmentFrame>()
    private val map=EnvironmentMap(SystemClock.elapsedRealtimeNanos())
    private var active=false
    private var sequence=0
    fun start() { active=true; analyzer.beginEnvironment(); capture() }
    private fun capture() {
        if (!active) return
        val request=++sequence
        val z=zoom()
        analyzer.requestSnapshot { frame -> main.post {
            if (!active || request!=sequence) { frame?.image?.recycle(); return@post }
            sequence++
            if (frame==null) { cancel(); return@post }
            val view=EnvironmentView(frames.size,SystemClock.elapsedRealtime(),z,(maxZoom()/z).coerceIn(1f,3.2f),frame.registration,frames.lastOrNull()?.view?.id)
            if (runCatching { map.add(view) }.isFailure) { frame.image.recycle(); cancel(); return@post }
            frames.add(EnvironmentFrame(view,frame))
            if (frames.size==6) { active=false; onFinished(EnvironmentBatch(map,frames.toList())); frames.clear() }
            else main.postDelayed({ capture() },470)
        } }
        main.postDelayed({ if (active && sequence==request) cancel() },1500)
    }
    fun cancel() {
        if (!active) return
        active=false; sequence++; analyzer.cancelSnapshot(); analyzer.endEnvironment()
        frames.forEach { it.frame.image.recycle() }; frames.clear(); onFinished(null)
    }
}
