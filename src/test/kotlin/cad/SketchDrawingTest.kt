package cad

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SketchDrawingTest {
    @Test fun `open line polyline and arc stay visible as open sketch geometry`() {
        val line=openSketchPath(SketchElementKind.LINE,listOf(SketchPoint(0.0,0.0),SketchPoint(10.0,0.0)))
        val polyline=openSketchPath(SketchElementKind.POLYLINE,listOf(SketchPoint(0.0,5.0),SketchPoint(5.0,10.0),SketchPoint(10.0,5.0)))
        val arc=sketchArc(SketchPoint(20.0,20.0),SketchPoint(30.0,20.0),SketchPoint(20.0,30.0))
        val sketch=Sketch(id="wires").withElements(listOf(line,polyline,arc))
        val document=DocumentState(sketches=listOf(sketch))
        val geometry=document.sketchGeometry()

        assertEquals(listOf(SketchElementKind.LINE,SketchElementKind.POLYLINE,SketchElementKind.ARC),geometry.map { it.kind })
        assertEquals(listOf(2,3,19),geometry.map { it.points.size })
        assertTrue(geometry.all { !it.closed })
        assertTrue(document.bodyMeshes().isEmpty())
        assertEquals(listOf(line,polyline,arc),document.evaluatedSketches().single().elements)
    }

    @Test fun `a mixed sketch renders closed profiles without closing open paths`() {
        val rectangle=SketchElement.from(Sketch(id="mixed",width="20 mm",height="10 mm"),"plate")
        val line=openSketchPath(SketchElementKind.LINE,listOf(SketchPoint(0.0,0.0),SketchPoint(0.0,10.0)))
        val sketch=Sketch(id="mixed").withElements(listOf(rectangle,line))
        val geometry=DocumentState(sketches=listOf(sketch)).sketchGeometry()

        assertEquals(listOf(true,false),geometry.map { it.closed })
        assertEquals(4,geometry.first().points.size)
        assertEquals(2,geometry.last().points.size)
        assertFalse(geometry.last().closed)
    }
}
