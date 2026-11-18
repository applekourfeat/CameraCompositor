package dev.cameracompositor

import kotlin.math.*

/** SDK estimates in one ARCore session. Coordinates: x right, y up, -z forward; XYZW quaternion. */
data class SpatialEvidence(val sessionId: String,val timestampNs: Long,val cameraId: String,
    val translationMeters: List<Float>,val quaternionXYZW: List<Float>,
    val imageSize: List<Int>,val focalLengthPixels: List<Float>,val principalPointPixels: List<Float>,
    val depthGridViewAligned: List<Float?>?,val sparsePointCount: Int,val rawImageToViewNormalized: List<Float> = emptyList()) {
    init {
        require(sessionId.isNotBlank() && cameraId.isNotBlank() && timestampNs>0)
        require(translationMeters.size==3 && translationMeters.all { it.isFinite() })
        require(quaternionXYZW.size==4 && quaternionXYZW.all { it.isFinite() })
        require(abs(quaternionXYZW.sumOf { it.toDouble()*it }-1)<.02)
        require(imageSize.size==2 && imageSize.all { it>0 } && focalLengthPixels.size==2 && focalLengthPixels.all { it.isFinite() && it>0 })
        require(principalPointPixels.size==2 && principalPointPixels.all { it.isFinite() } && sparsePointCount>=0)
        require(depthGridViewAligned==null || depthGridViewAligned.size==64 && depthGridViewAligned.all { it==null || it.isFinite() && it in .1f..65.535f })
        require(rawImageToViewNormalized.isEmpty() || rawImageToViewNormalized.size==9 && rawImageToViewNormalized.all { it.isFinite() })
    }
    fun distanceTo(other: SpatialEvidence): Float = sqrt(translationMeters.indices.sumOf {
        val d=translationMeters[it]-other.translationMeters[it]; d.toDouble()*d
    }).toFloat()
    fun rotationDegreesTo(other: SpatialEvidence): Float {
        val dot=quaternionXYZW.indices.sumOf { quaternionXYZW[it].toDouble()*other.quaternionXYZW[it] }
        return Math.toDegrees(2*acos(abs(dot).coerceIn(0.0,1.0))).toFloat()
    }
    /** Rotation/framing routes only. Significant translation needs live position guidance first. */
    fun supportsRotationLink(other: SpatialEvidence): Boolean = sessionId==other.sessionId && cameraId==other.cameraId &&
        abs(timestampNs-other.timestampNs)<=1_500_000_000L && distanceTo(other)<=.05f && rotationDegreesTo(other)<=20f
}
