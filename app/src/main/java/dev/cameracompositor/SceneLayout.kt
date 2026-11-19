package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Coarse evidence in normalized view coordinates. All scores are comparative, within a sweep. */
class SceneLayout(rgb: IntArray, val depth: RelativeDepth?) {
    companion object {
        const val WIDTH = 144
        const val HEIGHT = 192
        private const val COLUMNS = 24
        private const val ROWS = 32
        private const val CELL = 6
        private const val SIZE = COLUMNS * ROWS
    }

    private val x = FloatArray(SIZE) { (it % COLUMNS + .5f) / COLUMNS }
    private val y = FloatArray(SIZE) { (it / COLUMNS + .5f) / ROWS }
    private val texture = FloatArray(SIZE)
    private val light = FloatArray(SIZE)
    private val clipping = FloatArray(SIZE)
    private val contrast = FloatArray(SIZE)
    private val attention = FloatArray(SIZE)
    private val occlusion = BooleanArray(SIZE)
    private val contentMass: Float
    private val contentMassWithoutDepth: Float
    val focus: SubjectBox?
    val detail: Float
    val hasSubject get() = detail > .012f && focus != null

    init {
        require(rgb.size == WIDTH * HEIGHT)
        val luma = FloatArray(rgb.size) {
            val color = rgb[it]
            (.213f * (color shr 16 and 255) + .715f * (color shr 8 and 255) + .072f * (color and 255)) / 255
        }
        for (index in luma.indices) {
            val px = index % WIDTH
            val py = index / WIDTH
            val cell = py / CELL * COLUMNS + px / CELL
            val value = luma[index]
            light[cell] += value
            if (value < .025f || value > .985f) clipping[cell]++
            if (px < WIDTH - 1) texture[cell] += abs(value - luma[index + 1])
            if (py < HEIGHT - 1) texture[cell] += abs(value - luma[index + WIDTH])
        }
        for (i in 0 until SIZE) {
            light[i] /= CELL * CELL
            clipping[i] /= CELL * CELL
            texture[i] /= 2 * CELL * CELL
        }
        detail = texture.average().toFloat()
        markForeground()
        for (i in 0 until SIZE) {
            val neighbors = neighbors(i)
            contrast[i] = abs(light[i] - neighbors.map { light[it] }.average().toFloat())
            val centerWeight = 1f - .30f * hypot(x[i] - .5f, y[i] - .43f)
            val foregroundWeight = if (occlusion[i]) .10f else 1f
            attention[i] = centerWeight * foregroundWeight *
                ((texture[i] / .14f).coerceAtMost(1f) * .8f + (contrast[i] / .24f).coerceAtMost(1f) * .2f)
        }
        contentMass = attention.sum()
        contentMassWithoutDepth = attention.indices.sumOf { weight(it, useDepth = false).toDouble() }.toFloat()
        focus = if (contentMass > .01f) focusBounds() else null
    }

    /** Count coherent near-depth areas attached to a frame edge, outside the main central region. */
    private fun markForeground() {
        val depths = depth ?: return
        val near = FloatArray(SIZE) { depths.at(x[it], y[it]) }
        val middle = near.indices.filter { x[it] in .2f .. .8f && y[it] in .17f .. .64f }
            .map { near[it] }.sorted()
        val cutoff = maxOf(.63f, middle[middle.size / 2] + .19f)
        val visited = BooleanArray(SIZE)
        for (i in near.indices) {
            if (visited[i] || near[i] < cutoff) continue
            val component = mutableListOf(i)
            visited[i] = true
            var cursor = 0
            while (cursor < component.size) {
                for (neighbor in neighbors(component[cursor++])) {
                    if (!visited[neighbor] && near[neighbor] >= cutoff) {
                        visited[neighbor] = true
                        component.add(neighbor)
                    }
                }
            }
            val touchesEdge = component.any { x[it] < .05f || x[it] > .95f || y[it] > .95f || y[it] < .05f }
            val central = component.count { x[it] in .27f .. .73f && y[it] in .25f .. .62f }
            val foreground = component.size >= SIZE / 32 && central < component.size * .22f
            if (touchesEdge && foreground) component.forEach { occlusion[it] = true }
        }
    }

    private fun neighbors(index: Int): List<Int> = buildList(4) {
        val col = index % COLUMNS
        val row = index / COLUMNS
        if (col > 0) add(index - 1)
        if (col < COLUMNS - 1) add(index + 1)
        if (row > 0) add(index - COLUMNS)
        if (row < ROWS - 1) add(index + COLUMNS)
    }

    /** Central weighted extent. Its size reflects how spread out the visible subject detail is. */
    private fun focusBounds(): SubjectBox {
        val horizontal = FloatArray(COLUMNS)
        val vertical = FloatArray(ROWS)
        for (i in attention.indices) {
            horizontal[i % COLUMNS] += attention[i]
            vertical[i / COLUMNS] += attention[i]
        }
        fun quantile(weights: FloatArray, fraction: Float): Float {
            var accumulated = 0f
            for (i in weights.indices) {
                accumulated += weights[i]
                if (accumulated >= contentMass * fraction) return (i + .5f) / weights.size
            }
            return 1f
        }
        return SubjectBox(quantile(horizontal, .26f), quantile(vertical, .24f),
            quantile(horizontal, .74f), quantile(vertical, .75f))
    }

    data class Rating(val quality: Float, val foreground: Float, val retained: Float,
                      val boundary: Float, val clipped: Float)
    private fun weight(index: Int, useDepth: Boolean) =
        if (!useDepth && occlusion[index]) attention[index] * 10 else attention[index]

    fun subjectDetail(box: SubjectBox): Float {
        var sum = 0f
        var samples = 0
        for (i in 0 until SIZE) {
            if (x[i] in box.left..box.right && y[i] in box.top..box.bottom) {
                sum += texture[i]
                samples++
            }
        }
        return sum / samples.coerceAtLeast(1)
    }

    /** Only offer an automatic anchor when the detail is concentrated in a distinct part of the view. */
    fun isolatedFocus(): SubjectBox? {
        val b = focus ?: return null
        val width = b.right - b.left
        val height = b.bottom - b.top
        if (!hasSubject || width < .06f || height < .06f || width > .64f || height > .65f || width * height > .195f) return null
        if (subjectDetail(b) < detail * 1.65f) return null
        val area = SubjectBox(b.left - .07f, b.top - .065f, b.right + .07f, b.bottom + .065f)
        if (area.left < .035f || area.top < .035f || area.right > .965f || area.bottom > .965f) return null
        var sum = 0f
        for (i in attention.indices) if (x[i] in area.left..area.right && y[i] in area.top..area.bottom) sum += attention[i]
        return area.takeIf { sum >= contentMass * .51f }
    }

    /** Score whether a usable part of the frame beside the subject is visually quiet. */
    fun quietSide(frame: SubjectBox, subject: SubjectBox, rightSide: Boolean): Float {
        val width = frame.right - frame.left
        val height = frame.bottom - frame.top
        val left = if (rightSide) maxOf(frame.left + width * .49f, subject.right + width * .04f) else frame.left + width * .045f
        val right = if (rightSide) frame.right - width * .045f else minOf(frame.left + width * .51f, subject.left - width * .04f)
        if (right - left < width * .29f) return 0f
        var sum = 0f
        var squares = 0f
        var quiet = 0
        var count = 0
        for (i in texture.indices) {
            if (x[i] !in left..right || y[i] !in (frame.top + height * .08f)..(frame.bottom - height * .08f)) continue
            val value = light[i]
            count++
            sum += value
            squares += value * value
            if (texture[i] < .043f && contrast[i] < .085f && clipping[i] < .48f && !occlusion[i]) quiet++
        }
        if (count < 16) return 0f
        val mean = sum / count
        val variation = sqrt((squares / count - mean * mean).coerceAtLeast(0f))
        return (quiet.toFloat() / count - (variation - .09f).coerceAtLeast(0f) * 1.7f).coerceIn(0f, 1f)
    }

    fun rate(box: SubjectBox, useDepth: Boolean): Rating {
        var mass = 0f
        var weightedX = 0f
        var weightedY = 0f
        var boundary = 0f
        var boundarySamples = 0
        var clipped = 0f
        var near = 0
        var samples = 0
        val width = box.right - box.left
        val height = box.bottom - box.top
        for (i in 0 until SIZE) {
            val u = (x[i] - box.left) / width
            val v = (y[i] - box.top) / height
            if (u !in 0f..1f || v !in 0f..1f) continue
            val focusWeight = weight(i, useDepth)
            mass += focusWeight
            weightedX += u * focusWeight
            weightedY += v * focusWeight
            clipped += clipping[i]
            if (useDepth && occlusion[i]) near++
            if (minOf(u, v, 1 - u, 1 - v) < .09f) {
                boundary += focusWeight
                boundarySamples++
            }
            samples++
        }
        if (samples == 0) return Rating(-1f, 0f, 0f, 0f, 0f)
        val foreground = near.toFloat() / samples
        val total = if (useDepth) contentMass else contentMassWithoutDepth
        val retained = mass / total.coerceAtLeast(.01f)
        val spread = if (mass > .01f) hypot(weightedX / mass - .5f, weightedY / mass - .47f) else 1f
        val boundaryWeight = boundary / boundarySamples.coerceAtLeast(1)
        val density = sqrt(mass / samples)
        val quality = .70f * density - .75f * foreground - .32f * boundaryWeight -
            .15f * clipped / samples - .34f * spread
        return Rating(quality, foreground, retained, boundaryWeight, clipped / samples)
    }
}
