package cad

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.math.*

class SketchInteractionTest {
    @Test fun rectangleResizeKeepsOppositeCornerFixedWithRotation() {
        val draft=Sketch(u="10 mm",v="20 mm",width="40 mm",height="30 mm",angle="30 deg",depth="8 mm")
        val original=draft.handles(emptyMap())
        for(id in 0..3) {
            val handle=original.first { it.id==id }
            val target=SketchPoint(handle.point.u+2,handle.point.v+3)
            val resized=draft.resizeHandle(id,target,emptyMap())!!
            val moved=resized.handles(emptyMap()).first { it.id==id }.point
            val opposite=(id+2)%4
            val fixed=resized.handles(emptyMap()).first { it.id==opposite }.point
            assertEquals(target.u,moved.u,2e-6);assertEquals(target.v,moved.v,2e-6)
            assertEquals(original[opposite].point.u,fixed.u,2e-6);assertEquals(original[opposite].point.v,fixed.v,2e-6)
            assertEquals(draft.depth,resized.depth);assertEquals(draft.id,resized.id);assertEquals(draft.angle,resized.angle)
        }
    }
    @Test fun circleAndTriangleResizeAndMoveKeepUnchangedExpressions() {
        val values=ParameterEvaluator.evaluate(listOf(Parameter("w","20 mm"),Parameter("h","12 mm")))
        val circle=Sketch(shape=SketchShape.CIRCLE,width="w",height="h",u="5 mm",v="5 mm")
        val resized=circle.resizeHandle(0,SketchPoint(8.0,9.0),values)!!
        assertEquals("5 mm",resized.width);assertEquals(circle.u,resized.u)
        val moved=circle.resizeHandle(4,SketchPoint(25.0,30.0),values)!!
        assertEquals("w",moved.width);assertEquals("25 mm",moved.u)
        val triangle=Sketch(shape=SketchShape.TRIANGLE,width="w",height="h")
        assertEquals("w",triangle.resizeHandle(2,SketchPoint(10.0,18.0),values)!!.width)
        assertNull(triangle.resizeHandle(2,SketchPoint(10.0,-1.0),values))
        assertNull(Sketch().resizeHandle(2,SketchPoint(-1.0,-1.0),emptyMap()))
    }
    @Test fun snapPrefersOriginAndSupportCenterBeforeMillimeterGrid() {
        assertEquals(SketchSnap(SketchPoint(0.0,0.0),"Ursprung"),snapSketchPoint(SketchPoint(.4,.3),SketchPoint(20.0,10.0),1.0,true))
        assertEquals("Flächenmitte",snapSketchPoint(SketchPoint(20.3,9.6),SketchPoint(20.0,10.0),1.0,true).label)
        assertEquals(SketchPoint(7.0,4.0),snapSketchPoint(SketchPoint(7.3,3.7),null,.2,true).point)
        assertEquals(SketchPoint(7.3,3.7),snapSketchPoint(SketchPoint(7.3,3.7),null,.2,false).point)
    }
    @Test fun midpointAxesSnapIndependentlyAndReportTemporaryGuides() {
        val center=SketchPoint(20.0,10.0)
        val u=snapSketchPoint(SketchPoint(20.3,37.2),center,.5,true)
        assertEquals(SketchPoint(20.0,37.0),u.point)
        assertEquals("Mittellinie U",u.label)
        assertEquals(listOf(SketchGuide(center,SketchPoint(20.0,37.0))),u.guides)
        val v=snapSketchPoint(SketchPoint(43.2,10.3),center,.5,false)
        assertEquals(SketchPoint(43.2,10.0),v.point)
        assertEquals("Mittellinie V",v.label)
        val origin=snapSketchPoint(SketchPoint(.2,37.2),center,.5,false)
        assertEquals(SketchPoint(0.0,37.2),origin.point)
        assertEquals("Ursprungsachse U",origin.label)
    }
    @Test fun realBoundaryExcludesTriangulationDiagonals() {
        val frame=Sketch(plane=SketchPlane.XY).drawingFrame()
        val a=Vec3(0.0,0.0,0.0);val b=Vec3(40.0,0.0,0.0);val c=Vec3(40.0,30.0,0.0);val d=Vec3(0.0,30.0,0.0)
        val edges=sketchBoundary(listOf(a,b,c,a,c,d),frame)
        assertEquals(4,edges.size)
        assertTrue(edges.none { setOf(it.first,it.second)==setOf(SketchPoint(0.0,0.0),SketchPoint(40.0,30.0)) })
        val edge=snapSketchPoint(SketchPoint(15.3,.2),SketchPoint(20.0,15.0),.5,true,edges)
        assertEquals(SketchPoint(15.3,0.0),edge.point)
        assertEquals("Kante",edge.label);assertEquals(1,edge.guides.size)
        assertEquals("Ecke",snapSketchPoint(SketchPoint(39.8,29.8),null,.5,true,edges).label)
    }
    @Test fun slopedEdgesSnapToTheSegmentAndDoNotInventInfiniteEdges() {
        val edge=SketchPoint(10.0,10.0) to SketchPoint(30.0,30.0)
        val result=snapSketchPoint(SketchPoint(20.2,19.8),null,.5,false,listOf(edge))
        assertEquals(SketchPoint(20.0,20.0),result.point)
        assertEquals("Kante",result.label)
        val outside=snapSketchPoint(SketchPoint(40.2,39.8),null,.5,false,listOf(edge))
        assertNull(outside.label);assertTrue(outside.guides.isEmpty())
        assertFails { snapSketchPoint(SketchPoint(1.0,1.0),null,Double.NaN,true) }
    }

}
