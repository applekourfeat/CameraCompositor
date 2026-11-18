package dev.cameracompositor

import kotlin.math.abs

/** Measured image overlap. A projective registration is explicitly distinct from a 3D pose. */
data class ViewRegistration(val inliers: Int, val tracks: Int, val centerMotion: Float,
                            val reprojectionErrorPx: Float, val spatialCoverage: Float,
                            val previousToCurrent: ImageProjection? = null,
                            val currentToPrevious: ImageProjection? = null) {
    val reliable get() = inliers >= 18 && tracks >= inliers && inliers.toFloat() / tracks >= .7f &&
        centerMotion.isFinite() && centerMotion in 0f..2f && reprojectionErrorPx.isFinite() &&
        reprojectionErrorPx in 0f..1.5f && spatialCoverage.isFinite() && spatialCoverage in .12f..1f
}

data class EnvironmentView(val id: Int, val timeMs: Long, val zoom: Float, val maxRelativeZoom: Float,
                           val registration: ViewRegistration?,val previousViewId: Int?=null,val spatial: SpatialEvidence?=null)

/** Bounded temporal scene graph. It records uncertainty instead of inventing camera translation. */
class EnvironmentMap(val sceneId: Long) {
    private val entries = mutableListOf<EnvironmentView>()
    val views: List<EnvironmentView> get() = entries.toList()
    fun add(view: EnvironmentView) {
        require(entries.size < 8 && view.id >= 0 && entries.none { it.id == view.id })
        require(view.zoom.isFinite() && view.zoom > 0 && view.maxRelativeZoom.isFinite() && view.maxRelativeZoom in 1f..3.2f)
        require(entries.lastOrNull()?.let { view.timeMs > it.timeMs && abs(view.zoom/it.zoom-1) < .02f } != false)
        require(view.previousViewId==null || view.previousViewId==entries.lastOrNull()?.id)
        require(view.spatial==null || entries.lastOrNull()?.spatial?.let { it.sessionId==view.spatial.sessionId && view.spatial.timestampNs>it.timestampNs } != false)
        require(view.spatial==null || entries.mapNotNull { it.spatial }.all { it.sessionId==view.spatial.sessionId && it.cameraId==view.spatial.cameraId })
        entries.add(view)
    }
    /** Keep the temporal endpoints and distinct intervening views, never fabricated views. */
    fun plannerViews(): List<EnvironmentView> {
        if (entries.isEmpty()) return emptyList()
        val selected = mutableListOf(entries.first())
        var movement = 0f
        for (view in entries.drop(1)) {
            val selectedPose=selected.last().spatial
            if(view.spatial!=null && selectedPose!=null) {
                if(selectedPose.rotationDegreesTo(view.spatial)>=6f || selectedPose.distanceTo(view.spatial)>=.03f) selected.add(view)
                continue
            }
            val link = view.registration
            if (link?.reliable == true) movement += link.centerMotion
            if (link?.reliable == false || link == null || movement >= .12f) {
                selected.add(view); movement = 0f
            }
        }
        val last = entries.last()
        if (selected.last().id != last.id) selected.add(last)
        if (selected.size <= 4) return selected
        return listOf(selected.first(), selected[selected.size/3], selected[2*selected.size/3], selected.last()).distinctBy { it.id }
    }
    val registeredLinks get() = entries.drop(1).count { it.registration?.reliable == true }
    /** Reverse the measured observation chain so a target outside the current FOV remains reachable.
     * This is an image/rotation route, not a path for walking through 3D space. */
    fun routeTo(viewId: Int): List<Int>? {
        val target=entries.indexOfFirst { it.id==viewId }
        if(target<0) return null
        for(i in target+1 until entries.size) {
            val previous=entries[i-1]; val next=entries[i]
            if(next.registration?.reliable!=true && previous.spatial?.let { a -> next.spatial?.let { a.supportsRotationLink(it) } }!=true) return null
        }
        return entries.subList(target,entries.size).asReversed().map { it.id }
    }

    /** Map a visible region through connected views. A broken registration has no projection. */
    fun project(fromId: Int, toId: Int, box: SubjectBox): SubjectBox? {
        val corners = projectPoints(fromId, toId, ShotGeometry.corners(box)) ?: return null
        return SubjectBox(corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    fun projectPoints(fromId: Int, toId: Int, points: List<ScenePoint>): List<ScenePoint>? {
        val from = entries.indexOfFirst { it.id == fromId }
        val to = entries.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return null
        var projected = points
        val indices = if (from <= to) (from + 1)..to else from downTo (to + 1)
        for (index in indices) {
            val link = entries[index].registration?.takeIf { it.reliable } ?: return null
            val projection = (if (from <= to) link.previousToCurrent else link.currentToPrevious) ?: return null
            projected = projected.map { projection.project(it) ?: return null }
        }
        return projected
    }
}

sealed interface EnvironmentDecision {
    data class Keep(val reason: String): EnvironmentDecision
    data class ObserveMore(val reason: String): EnvironmentDecision
    data class Guide(val viewId: Int, val crop: SubjectBox, val subject: String, val reason: String): EnvironmentDecision
}

data class SceneSubjectEvidence(val id: String,val name: String,val observedViewIds: Set<Int>)
/** These relationships are visual model inferences, never measured metric geometry. */
data class SceneRelationship(val fromSubjectId: String,val toSubjectId: String,val type: String)
data class EnvironmentAssessment(val summary: String,val subjects: List<SceneSubjectEvidence>,
                                 val relationships: List<SceneRelationship>,val decision: EnvironmentDecision)
