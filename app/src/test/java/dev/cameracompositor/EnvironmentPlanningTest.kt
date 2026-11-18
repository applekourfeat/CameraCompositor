package dev.cameracompositor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnvironmentPlanningTest {
    private val link=ViewRegistration(80,100,.2f,.5f,.6f)
    private val views=listOf(EnvironmentView(0,1000,1f,3.2f,null),EnvironmentView(2,1600,1f,2.5f,link))
    private fun wrap(plan: String, finish: String="STOP")=JSONObject().put("candidates",JSONArray().put(JSONObject()
        .put("finishReason",finish).put("content",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",plan)))))).toString()
    private fun plan(id: Number=0,zoom: Number=2.2)=JSONObject().put("decision","guide").put("viewId",id)
        .put("centerX",500).put("centerY",500).put("zoom",zoom).put("subject","Building pair")
        .put("reason","Separate the towers from the foreground railing.").put("sceneSummary","Towers stand beyond a railing and bridge.")
        .put("subjectId","towers").put("subjects",JSONArray().put(JSONObject().put("id","towers").put("name","Building pair").put("viewIds",JSONArray(listOf(0,2)))))
        .put("relationships",JSONArray())

    @Test fun `request supplies several observations and explicit unknown spatial quantities`() {
        val request=JSONObject(EnvironmentProtocol.request(views.map { EncodedEnvironmentView(it,"image-${it.id}") },2))
        val parts=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(5,parts.length())
        val first=JSONObject(parts.getJSONObject(1).getString("text"))
        assertTrue(first.isNull("cameraPose")); assertTrue(first.isNull("metricDepth"))
        assertEquals("image-2",parts.getJSONObject(4).getJSONObject("inlineData").getString("data"))
        assertFalse(request.has("apiKey"))
    }
    @Test fun `guide chooses a real earlier view and optional tighter framing`() {
        val decision=EnvironmentProtocol.response(wrap(plan().toString()),views,2) as EnvironmentDecision.Guide
        assertEquals(0,decision.viewId)
        assertEquals(1/2.2f,decision.crop.right-decision.crop.left,.001f)
    }
    @Test fun `full framing of another observed view remains a meaningful target`() {
        val decision=EnvironmentProtocol.response(wrap(plan(0,1).toString()),views,2)
        assertTrue(decision is EnvironmentDecision.Guide)
        assertTrue(EnvironmentProtocol.response(wrap(plan(2,1).toString()),views,2) is EnvironmentDecision.Keep)
    }
    @Test fun `unobserved or fractional identities cannot create targets`() {
        for (id in listOf(99,1.5)) assertThrows(RuntimeException::class.java) {
            EnvironmentProtocol.response(wrap(plan(id).toString()),views,2)
        }
    }
    @Test fun `unsupported zoom or outside image coordinates cannot drive camera`() {
        for (p in listOf(plan(2,3),plan(0,8),plan().put("centerX",0),plan().put("centerY",1001))) {
            assertThrows(RuntimeException::class.java) { EnvironmentProtocol.response(wrap(p.toString()),views,2) }
        }
    }
    @Test fun `incomplete and unsupported decisions are rejected`() {
        assertThrows(RuntimeException::class.java) { EnvironmentProtocol.response(wrap(plan().toString(),"MAX_TOKENS"),views,2) }
        assertThrows(RuntimeException::class.java) { EnvironmentProtocol.response(wrap(plan().put("decision","walk").toString()),views,2) }
    }
    @Test fun `keep and observe more require no invented target geometry`() {
        for (kind in listOf("keep","observe_more")) {
            val p=JSONObject().put("decision",kind).put("reason","The complete scene has useful context.").put("sceneSummary","The bridge relates to the towers.")
                .put("subjects",JSONArray()).put("relationships",JSONArray())
            val decision=EnvironmentProtocol.response(wrap(p.toString()),views,2)
            assertTrue(if(kind=="keep") decision is EnvironmentDecision.Keep else decision is EnvironmentDecision.ObserveMore)
        }
    }
    @Test fun `subject identities and visibility constrain targets across observations`() {
        val p=plan(2)
        p.getJSONArray("subjects").getJSONObject(0).put("viewIds",JSONArray(listOf(0)))
        assertThrows(RuntimeException::class.java) { EnvironmentProtocol.response(wrap(p.toString()),views,2) }
        p.getJSONArray("subjects").getJSONObject(0).put("viewIds",JSONArray(listOf(99)))
        assertThrows(RuntimeException::class.java) { EnvironmentProtocol.response(wrap(p.toString()),views,2) }
        val unknown=plan().put("relationships",JSONArray().put(JSONObject().put("from","towers").put("to","invented").put("type","behind")))
        assertThrows(RuntimeException::class.java) { EnvironmentProtocol.response(wrap(unknown.toString()),views,2) }
    }
    @Test fun `temporal graph rejects zoom changes reordered frames and excess storage`() {
        val map=EnvironmentMap(1); map.add(views[0])
        assertThrows(IllegalArgumentException::class.java) { map.add(views[0].copy(id=1)) }
        assertThrows(IllegalArgumentException::class.java) { map.add(views[1].copy(zoom=1.5f)) }
        for (i in 1..7) map.add(views[0].copy(id=i,timeMs=1000L+i*600,registration=link))
        assertThrows(IllegalArgumentException::class.java) { map.add(views[0].copy(id=8,timeMs=10_000)) }
        assertTrue(map.plannerViews().size<=4)
        assertEquals(0,map.plannerViews().first().id); assertEquals(7,map.plannerViews().last().id)
    }
    @Test fun `static repeated views cannot inflate scene coverage`() {
        val map=EnvironmentMap(1)
        repeat(6) { map.add(views[0].copy(id=it,timeMs=1000L+it*600,registration=if(it==0)null else link.copy(centerMotion=0f))) }
        assertEquals(1,map.plannerViews().size)
        assertFalse(link.copy(inliers=4).reliable); assertFalse(link.copy(centerMotion=Float.NaN).reliable)
        assertFalse(link.copy(spatialCoverage=.02f).reliable)
    }
    @Test fun `higher validated framing limit still respects device zoom`() {
        val box=SubjectBox(.3f,.3f,.7f,.7f)
        val points=ShotGeometry.corners(box)
        assertEquals(2.5f,ShotGeometry.desiredZoom(1f,points,1f,8f,3.2f)!!,.001f)
        assertEquals(2f,ShotGeometry.desiredZoom(1f,points,1f,2f,3.2f)!!,.001f)
    }
    @Test fun `offscreen targets use measured intermediate observations and broken chains abstain`() {
        val map=EnvironmentMap(1)
        repeat(6) { map.add(views[0].copy(id=it,timeMs=1000L+it*600,registration=if(it==0)null else link,previousViewId=if(it==0)null else it-1)) }
        assertEquals(listOf(5,4,3,2,1,0),map.routeTo(0))
        assertEquals(listOf(5),map.routeTo(5)); assertNull(map.routeTo(99))
        val broken=EnvironmentMap(2)
        broken.add(views[0]); broken.add(views[1].copy(registration=null,previousViewId=0))
        assertNull(broken.routeTo(0))
    }
}
