package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class CenteredRectangleTest {
    @TempDir lateinit var directory: Path
    private fun close(a: Vec3,b: Vec3) { assertEquals(a.x,b.x,2e-6);assertEquals(a.y,b.y,2e-6);assertEquals(a.z,b.z,2e-6) }
    @Test fun dragInEveryQuadrantKeepsTheChosenCenter() {
        for(plane in SketchPlane.entries) for(x in listOf(-8.0,8.0)) for(y in listOf(-5.0,5.0)) {
            val draft=Sketch(plane=plane,angle="30 deg")
            val center=SketchPoint(20.0,15.0)
            val result=draft.drawn(center,SketchPoint(center.u+x,center.v+y),false,fromCenter=true)!!
            assertTrue(result.centered);assertEquals("20 mm",result.u);assertEquals("15 mm",result.v)
            val points=result.points(emptyMap());close(plane.point(20.0,15.0),(points[0]+points[2])*.5)
        }
    }
    @Test fun cornerResizeAndParameterChangesStaySymmetric() {
        val draft=Sketch(centered=true,u="10 mm",v="20 mm",width="w",height="12 mm",depth="5 mm")
        val values=ParameterEvaluator.evaluate(listOf(Parameter("w","20 mm")))
        val result=draft.resizeHandle(2,SketchPoint(25.0,29.0),values)!!
        assertEquals("30 mm",result.width);assertEquals("18 mm",result.height)
        assertEquals(draft.u,result.u);assertEquals(draft.v,result.v)
        val points=draft.points(ParameterEvaluator.evaluate(listOf(Parameter("w","40 mm"))))
        close(Vec3(10.0,20.0,0.0),(points[0]+points[2])*.5)
        assertEquals(SketchPoint(10.0,20.0),draft.handles(values).last().point)
        assertEquals("w",draft.resizeHandle(4,SketchPoint(30.0,40.0),values)!!.width)
    }
    @Test fun changingAnchorDoesNotMoveTheContourIncludingRotation() {
        val corner=Sketch(u="4 mm",v="8 mm",width="40 mm",height="20 mm",angle="27 deg")
        val center=corner.withCenterAnchor(true,emptyMap())
        corner.points(emptyMap()).zip(center.points(emptyMap())).forEach { (a,b) -> close(a,b) }
        val back=center.withCenterAnchor(false,emptyMap())
        corner.points(emptyMap()).zip(back.points(emptyMap())).forEach { (a,b) -> close(a,b) }
    }
    @Test fun centeredProfilesRoundTripAndRemainClosedSolids() {
        val sketch=Sketch(centered=true,u="10 mm",v="20 mm",width="40 mm",height="20 mm",depth="8 mm",angle="30 deg")
        val state=DocumentState(sketches=listOf(sketch));val path=directory.resolve("centered.kcad")
        ProjectIO.write(path,ProjectArchive(state));assertEquals(state.canonicalFeatures(),ProjectIO.read(path).document)
        val body=state.bodyMeshes().single()
        val volume=body.indices.toList().chunked(3).sumOf { f -> body.vertices[f[0]].dot(body.vertices[f[1]].cross(body.vertices[f[2]]))/6 }
        assertEquals(6400.0,volume,1e-6)
        assertFails { sketch.copy(shape=SketchShape.CIRCLE).points(emptyMap()) }
        val legacy=sketch.json().toMutableMap().apply { remove("centered") }
        assertFalse(Sketch.read(kotlinx.serialization.json.JsonObject(legacy)).centered)
    }
}
