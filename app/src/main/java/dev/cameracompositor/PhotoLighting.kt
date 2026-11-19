package dev.cameracompositor

enum class PhotoMode { JPEG, ADAPTIVE, AUTO, HDR, NIGHT }
enum class PhotoLighting { ORDINARY, DIM, BACKLIT }

/** A rough indication from camera luma. The adaptive mode only requests an OEM extension for clear cases. */
fun classifyLighting(signature: IntArray?): PhotoLighting {
    if (signature == null || signature.size != 96 || signature.any { it !in 0..255 }) return PhotoLighting.ORDINARY
    val shadow = signature.count { it <= 54 }
    val highlight = signature.count { it >= 207 }
    if (shadow >= 14 && highlight >= 12) return PhotoLighting.BACKLIT
    if (shadow >= 66 && signature.count { it > 112 } <= 4) return PhotoLighting.DIM
    return PhotoLighting.ORDINARY
}
