package dev.cameracompositor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

enum class SceneKind(val description: String, val subjectWeight: Float) {
    FOLIAGE("tree or plant", .09f), ARCHITECTURE("building or bridge", .065f),
    MOUNTAIN("ridge", .08f), WATER("water", .025f), PERSON("person", .09f), ANIMAL("animal", .10f),
    OBJECTS("objects", .025f), WALKWAY("walkway", .015f), BACKGROUND("background", 0f), OBSTRUCTION("foreground edge", 0f)
}

data class SceneSubject(val kind: SceneKind, val bounds: SubjectBox, val point: ScenePoint, val strength: Float,
                        val skyline: Boolean = false, val framingBounds: SubjectBox = bounds, val detected: Boolean = false,
                        val outlinedDetail: Boolean = false, val paired: Boolean = false)

data class SubjectHint(val kind: SceneKind, val point: ScenePoint)

/** Scores from related prompts vote together. Gaps and background remain separate from subjects. */
object SceneRegions {
    const val GRID = 12
    val PROMPT_GROUP = listOf(SceneKind.FOLIAGE, SceneKind.FOLIAGE, SceneKind.FOLIAGE,
        SceneKind.ARCHITECTURE, SceneKind.ARCHITECTURE, SceneKind.MOUNTAIN, SceneKind.WATER,
        SceneKind.PERSON, SceneKind.ANIMAL, SceneKind.OBJECTS, SceneKind.WALKWAY,
        SceneKind.BACKGROUND, SceneKind.BACKGROUND, SceneKind.OBSTRUCTION, SceneKind.BACKGROUND)

    @JvmOverloads
    fun subjects(scores: FloatArray, layout: SceneLayout, includeBuildingOutlines: Boolean = false): List<SceneSubject> {
        require(scores.size == GRID * GRID * PROMPT_GROUP.size && scores.all(Float::isFinite))
        val groups = IntArray(GRID * GRID) { -1 }
        val margins = FloatArray(groups.size)
        val peaks = FloatArray(SceneKind.entries.size)
        val skyByRow = IntArray(GRID)
        val buildingEvidence = if (includeBuildingOutlines) FloatArray(groups.size) else null
        for (cell in groups.indices) {
            peaks.fill(-1f)
            for ((prompt, group) in PROMPT_GROUP.withIndex()) {
                val score = scores[cell * PROMPT_GROUP.size + prompt]
                peaks[group.ordinal] = maxOf(peaks[group.ordinal], score)
            }
            val best = peaks.indices.maxBy { peaks[it] }
            val gap = peaks[best] - peaks.indices.filter { it != best }.maxOf { peaks[it] }
            val sky = best == SceneKind.BACKGROUND.ordinal &&
                scores[cell * PROMPT_GROUP.size + 12] == peaks[best]
            if (sky && gap >= .008f) skyByRow[cell / GRID]++
            if (gap >= .010f && peaks[best] >= .118f) {
                groups[cell] = best
                margins[cell] = gap
            }
            val architecture = peaks[SceneKind.ARCHITECTURE.ordinal]
            if (buildingEvidence != null && !sky && architecture >= .116f && peaks[best] - architecture <= .035f) {
                buildingEvidence[cell] = architecture
            }
        }
        val found = regions(groups, margins, layout, openSky = skyByRow.sum() >= 14)
        val skyline = (2 until GRID - 3).firstOrNull { row ->
            skyByRow.take(row).count { it >= GRID / 3 } >= 2 &&
                (row until row + 3).all { skyByRow[it] < GRID / 4 }
        }
        val silhouettes = if (skyline != null) {
            val towers = IntArray(groups.size) { cell ->
                if (cell / GRID <= skyline && groups[cell] == SceneKind.ARCHITECTURE.ordinal) groups[cell] else -1
            }
            regions(towers, margins, layout, skyline = true).filter { it.bounds.top <= (skyline - 1f) / GRID }
        } else emptyList()
        val outlines = buildingEvidence?.let { buildingOutlines(it, skyByRow, layout) }.orEmpty()
        return (outlines + found + silhouettes).sortedByDescending { it.strength }
    }

    /** Connect textured facade patches beneath a visible stretch of sky. */
    private fun buildingOutlines(evidence: FloatArray, skyByRow: IntArray, layout: SceneLayout): List<SceneSubject> {
        val skyline = (2 until GRID - 3).firstOrNull { row ->
            skyByRow.take(row).count { it >= GRID / 3 } >= 2 &&
                (row until row + 3).count { skyByRow[it] < GRID / 4 } >= 2
        } ?: return emptyList()
        val columns = IntArray(GRID)
        val support = FloatArray(evidence.size)
        for (i in 0 until (skyline + 1) * GRID) {
            if (evidence[i] == 0f) continue
            val x = i % GRID
            val y = i / GRID
            val cell = SubjectBox(x.toFloat() / GRID, y.toFloat() / GRID, (x + 1f) / GRID, (y + 1f) / GRID)
            if (layout.subjectDetail(cell) >= .025f) {
                columns[x]++
                // Keep outline support on the same scale as the winning category margins.
                support[i] = .018f + (evidence[i] - .116f).coerceIn(0f, .22f) * .08f
            }
        }
        val facades = IntArray(evidence.size) { i ->
            if (support[i] > 0f && columns[i % GRID] >= 2) SceneKind.ARCHITECTURE.ordinal else -1
        }
        val base = (skyline + 3.5f) / GRID
        return regions(facades, support, layout, skyline = true, outlineBase = base)
            .filter { it.bounds.top < (skyline - .5f) / GRID }
    }

    private fun regions(groups: IntArray, margins: FloatArray, layout: SceneLayout,
                        skyline: Boolean = false, openSky: Boolean = false, outlineBase: Float? = null): List<SceneSubject> {
        val visited = BooleanArray(groups.size)
        val found = mutableListOf<SceneSubject>()
        for (cell in groups.indices) {
            if (visited[cell] || groups[cell] < 0 || groups[cell] >= SceneKind.BACKGROUND.ordinal) continue
            val group = groups[cell]
            val region = mutableListOf(cell)
            visited[cell] = true
            var cursor = 0
            while (cursor < region.size) {
                val index = region[cursor++]
                for (y in (index / GRID - 1).coerceAtLeast(0)..(index / GRID + 1).coerceAtMost(GRID - 1)) {
                    for (x in (index % GRID - 1).coerceAtLeast(0)..(index % GRID + 1).coerceAtMost(GRID - 1)) {
                        val next = y * GRID + x
                        if (!visited[next] && groups[next] == group) {
                            visited[next] = true
                            region.add(next)
                        }
                    }
                }
            }
            if (region.size !in 5..88) continue
            val left = region.minOf { it % GRID }.toFloat() / GRID
            val top = region.minOf { it / GRID }.toFloat() / GRID
            val right = (region.maxOf { it % GRID } + 1f) / GRID
            val bottom = (region.maxOf { it / GRID } + 1f) / GRID
            val area = (right - left) * (bottom - top)
            val regionBox = SubjectBox(left, top, right, bottom)
            val detail = layout.subjectDetail(regionBox)
            if (area > .67f || region.size / (area * GRID * GRID) < .44f || detail < .013f) continue
            val gap = region.map { margins[it] }.average().toFloat()
            if (gap < .016f) continue
            val total = region.sumOf { margins[it].toDouble() }.toFloat()
            val x = region.sumOf { ((it % GRID + .5f) / GRID * margins[it]).toDouble() }.toFloat() / total
            val y = region.sumOf { ((it / GRID + .5f) / GRID * margins[it]).toDouble() }.toFloat() / total
            val kind = SceneKind.entries[group]
            val prominence = 2.8f * gap + .23f * sqrt(region.size.toFloat() / groups.size) + kind.subjectWeight -
                .10f * hypot(x - .5f, y - .45f) + minOf(detail, .09f) * .7f + if (skyline) .14f else 0f
            val box = when {
                outlineBase != null -> {
                    val roofBand = minOf(bottom, top + .20f)
                    val leftSky = left == 0f && layout.subjectDetail(SubjectBox(0f, top, .027f, roofBand)) < .015f
                    val rightSky = right == 1f && layout.subjectDetail(SubjectBox(.973f, top, 1f, roofBand)) < .015f
                    SubjectBox(
                        if (leftSky) .034f else maxOf(0f, left - .35f / GRID), maxOf(0f, top - .55f / GRID),
                        if (rightSky) .966f else minOf(1f, right + .35f / GRID),
                        minOf(1f, maxOf(outlineBase, bottom + 1.5f / GRID))
                    )
                }
                skyline -> regionBox.copy(bottom = minOf(1f, bottom + 1.5f / GRID))
                else -> regionBox
            }
            val lookHigher = skyline && box.bottom <= .64f &&
                layout.subjectDetail(SubjectBox(0f, box.bottom, 1f, 1f)) >= maxOf(.027f, detail * .66f)
            // Branches can cross the frame boundary. Preserve the crown's core when its center has room.
            val crown = if (openSky && kind == SceneKind.FOLIAGE && region.size >= 7 &&
                x in .15f.. .85f && y in .15f.. .85f) {
                val rx = ((right - left) * .31f).coerceIn(.09f, .23f)
                val ry = ((bottom - top) * .31f).coerceIn(.09f, .23f)
                SubjectBox(maxOf(left, x - rx), maxOf(top, y - ry), minOf(right, x + rx), minOf(bottom, y + ry))
            } else box
            found.add(SceneSubject(kind, box, ScenePoint(x, y), prominence, lookHigher, crown))
        }
        return found
    }
}

data class DiscoveryAssessment(val plan: StyleAssessment, val region: SceneSubject?)

/** Applies the same subject and viewpoint policy to camera frames and recorded research scenes. */
object SceneDiscovery {
    fun needsSemantics(detected: DetectedSubject?, picked: ScenePoint?, expected: SubjectHint?) =
        picked == null && (expected != null || (detected?.confidence ?: 0f) < .58f)

    fun choose(style: CompositionStyle, map: EnvironmentMap, layouts: Map<Int, SceneLayout>, zoom: Float,
               detected: DetectedSubject?, selected: SelectedSubject?, scores: FloatArray?,
               allowExploration: Boolean, expected: SubjectHint? = null): DiscoveryAssessment {
        val view = map.views.last().id
        val regions = if (scores != null && needsSemantics(detected, selected?.point, expected)) {
            SceneRegions.subjects(scores, layouts.getValue(view)).filter { it.strength >= .155f }
        } else emptyList()
        val region = if (expected == null) regions.firstOrNull() else {
            regions.filter { it.kind == expected.kind && distance(it.point, expected.point) <= .34f }
                .maxByOrNull { it.strength - .4f * distance(it.point, expected.point) }
        }
        if (expected != null && region == null) {
            return DiscoveryAssessment(StyleAssessment(EnvironmentDecision.ObserveMore("Keep the subject in view."), false), null)
        }
        val observed = StyledComposition.choose(style, map, layouts, zoom, detected.takeIf { expected == null }, selected, region)
        val explore = if (allowExploration && selected == null) NextView.suggest(view, style, detected, region, observed.decision) else null
        return DiscoveryAssessment(if (explore == null) observed else StyleAssessment(explore, false), region)
    }

    private fun distance(a: ScenePoint, b: ScenePoint) = hypot(a.x - b.x, a.y - b.y)
}

object NextView {
    /** A full field of view at the same zoom, aimed toward likely continuation beyond the edges. */
    fun suggest(viewId: Int, style: CompositionStyle, detection: DetectedSubject?, semantic: SceneSubject?,
                current: EnvironmentDecision): EnvironmentDecision.Explore? {
        val box = detection?.takeIf { it.confidence >= .58f }?.box ?: semantic?.takeIf { it.strength >= .155f }?.bounds
            ?: return null
        val center = semantic?.takeIf { it.bounds == box }?.point ?: ScenePoint((box.left + box.right) / 2, (box.top + box.bottom) / 2)
        fun move(start: Float, end: Float, point: Float): Float = when {
            start <= .055f && end < .86f -> -.22f
            end >= .945f && start > .14f -> .22f
            start <= .055f || end >= .945f -> 0f
            else -> (point - .5f).coerceIn(-.19f, .19f)
        }
        var dx = move(box.left, box.right, center.x)
        var dy = move(box.top, box.bottom, center.y)
        val clipped = listOf(box.left, box.top, 1 - box.right, 1 - box.bottom).min() <= .055f
        val zoomSuggestion = current is EnvironmentDecision.Guide && current.viewId == viewId &&
            current.crop.right - current.crop.left < .72f
        val skyline = semantic?.skyline == true && style != CompositionStyle.WIDE && current !is EnvironmentDecision.Guide
        if (style == CompositionStyle.WIDE && !clipped) return null
        if (!clipped && !zoomSuggestion && !skyline && current !is EnvironmentDecision.ObserveMore) return null
        if (abs(dx) < .085f) dx = 0f
        if (abs(dy) < .085f) dy = 0f
        if (hypot(dx, dy) < .13f) return null
        val objectName = semantic?.takeIf { it.bounds == box }?.kind?.description ?: "subject"
        val reason = when {
            skyline -> "Follow the arrow to check a higher view of the skyline."
            clipped -> "Follow the arrow to show more of the $objectName."
            else -> "Follow the arrow to check another angle on the $objectName."
        }
        val expected = semantic?.takeIf { it.bounds == box }?.let {
            SubjectHint(it.kind, ScenePoint(it.point.x - dx, it.point.y - dy))
        }
        return EnvironmentDecision.Explore(viewId, ScenePoint(.5f + dx, .5f + dy), reason, expected)
    }

    fun frame(aim: ScenePoint) = SubjectBox(aim.x - .5f, aim.y - .5f, aim.x + .5f, aim.y + .5f)

    fun arrived(aim: ScenePoint) = hypot(aim.x - .5f, aim.y - .5f) <= .063f
}
