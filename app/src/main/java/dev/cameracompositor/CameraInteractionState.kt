package dev.cameracompositor

data class CameraControls(val captureEnabled: Boolean, val flipEnabled: Boolean, val cancelVisible: Boolean)

/** Every state change renders the controls, including paths that return without a guide. */
class CameraInteractionState(private val render: (CameraControls) -> Unit) {
    var ready = false
        set(value) { field = value; render(controls) }
    var saving = false
        set(value) { field = value; render(controls) }
    var scanning = false
        set(value) { field = value; render(controls) }
    var guiding = false
        set(value) { field = value; render(controls) }

    val controls get() = CameraControls(
        captureEnabled = ready && !saving,
        flipEnabled = ready && !saving && !scanning,
        cancelVisible = ready && !saving && (scanning || guiding),
    )
}

/** A full-view ranking is abstention, not evidence of a good photograph. */
object FramingFeedback {
    fun hasSuggestion(choice: ModelChoice): Boolean {
        val crop = choice.customCrop ?: ShotGeometry.options.getOrNull(choice.cropId)?.box
            ?: throw IllegalArgumentException("Invalid framing option")
        require(listOf(crop.left, crop.top, crop.right, crop.bottom).all { it.isFinite() && it in 0f..1f } &&
            crop.right > crop.left && crop.bottom > crop.top)
        return crop.left > .002f || crop.top > .002f || crop.right < .998f || crop.bottom < .998f
    }
}
