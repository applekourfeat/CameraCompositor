package dev.cameracompositor

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SpatialEvidenceTest {
    private fun evidence(x: Float=0f,degrees: Float=0f,time: Long=1_000_000_000)=SpatialEvidence("session-a",time,"0",
        listOf(x,0f,0f),listOf(0f,sin(Math.toRadians(degrees.toDouble())/2).toFloat(),0f,cos(Math.toRadians(degrees.toDouble())/2).toFloat()),
        listOf(640,480),listOf(434f,434f),listOf(320f,240f),List(64) { if(it==0)null else 2f },5,
        listOf(1/640f,0f,0f,0f,1/480f,0f,0f,0f,1f))

    @Test fun `SDK pose and view aligned depth enter planning while session identifier stays private`() {
        val e=evidence(); val view=EnvironmentView(0,1000,1f,3.2f,null,spatial=e)
        val request=JSONObject(EnvironmentProtocol.request(listOf(EncodedEnvironmentView(view,"image")),0))
        val details=JSONObject(request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("text"))
        assertFalse(details.isNull("cameraPose")); assertEquals(64,details.getJSONArray("metricDepth").length())
        assertTrue(details.getJSONArray("metricDepth").isNull(0))
        assertEquals(9,details.getJSONArray("rawImageToViewNormalized").length())
        assertFalse(request.toString().contains("session-a"))
    }
    @Test fun `nearby rotated SDK observations can support a rotation route without invented inliers`() {
        val a=evidence(); val b=evidence(.02f,10f,1_600_000_000)
        assertEquals(.02f,a.distanceTo(b),.0001f); assertEquals(10f,a.rotationDegreesTo(b),.001f)
        assertTrue(a.supportsRotationLink(b))
        val map=EnvironmentMap(1)
        map.add(EnvironmentView(0,1000,1f,3.2f,null,spatial=a))
        map.add(EnvironmentView(1,1600,1f,3.2f,null,0,b))
        assertEquals(0,map.registeredLinks); assertEquals(listOf(1,0),map.routeTo(0))
    }
    @Test fun `significant translation disconnected sessions and large turns cannot become rotation routes`() {
        val a=evidence()
        assertFalse(a.supportsRotationLink(evidence(.2f,10f,1_600_000_000)))
        assertFalse(a.supportsRotationLink(evidence(0f,50f,1_600_000_000)))
        assertFalse(a.supportsRotationLink(evidence().copy(sessionId="another-session")))
    }
    @Test fun `mixed spatial world frames are rejected rather than combined`() {
        val a=EnvironmentView(0,1000,1f,3.2f,null,spatial=evidence())
        val b=a.copy(id=1,timeMs=1600,spatial=evidence(time=1_600_000_000).copy(sessionId="other"))
        val map=EnvironmentMap(1); map.add(a)
        assertThrows(IllegalArgumentException::class.java) { map.add(b) }
        assertThrows(IllegalArgumentException::class.java) { EnvironmentProtocol.request(listOf(EncodedEnvironmentView(a,"image"),EncodedEnvironmentView(b,"image")),1) }
    }
    @Test fun `invalid pose and depth quantities cannot enter the spatial contract`() {
        assertThrows(IllegalArgumentException::class.java) { evidence().copy(quaternionXYZW=listOf(0f,0f,0f,0f)) }
        assertThrows(IllegalArgumentException::class.java) { evidence().copy(translationMeters=listOf(Float.NaN,0f,0f)) }
        assertThrows(IllegalArgumentException::class.java) { evidence().copy(depthGridViewAligned=listOf(2f)) }
    }
    @Test fun `static SDK frames retain current image without pretending to observe additional angles`() {
        val map=EnvironmentMap(1)
        repeat(6) { map.add(EnvironmentView(it,1000L+it*600,1f,3.2f,null,if(it==0)null else it-1,evidence(time=1_000_000_000L+it*600_000_000L))) }
        assertEquals(listOf(0,5),map.plannerViews().map { it.id })
    }
    @Test fun `registered camera field of view calibrates the baseline before relative composition zoom`() {
        val registered=ShotGeometry.corners(SubjectBox(.125f,.125f,.875f,.875f))
        val baseline=1f/ShotGeometry.width(registered)
        assertEquals(1.333333f,baseline,.001f)
        val chosen=ShotGeometry.corners(SubjectBox(.25f,.25f,.75f,.75f))
        assertEquals(2.666667f,ShotGeometry.desiredZoom(baseline,chosen,baseline,8f,3.2f)!!,.001f)
    }
}
