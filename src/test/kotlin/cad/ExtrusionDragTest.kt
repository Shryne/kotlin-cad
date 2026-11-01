package cad

import org.junit.jupiter.api.Test
import kotlin.test.*

class ExtrusionDragTest {
    @Test fun projectedAxisDistanceFindsTheActualSignedDepth() {
        val origin=Vec3(4.0,8.0,12.0);val axis=Vec3(0.0,0.0,1.0)
        val eye=Vec3(70.0,-90.0,100.0)
        for(depth in listOf(-30.0,0.0,12.0,40.0)) {
            val ray=origin+axis*depth-eye
            assertEquals(depth,axisDistance(origin,axis,eye,ray)!!,1e-8)
        }
        assertNull(axisDistance(origin,axis,eye,axis))
        assertNull(axisDistance(origin,axis,eye,Vec3(1e-4,0.0,1.0)))
    }
    @Test fun draggingRetainsGrabOffsetAndSupportsFineNegativeDepth() {
        val origin=Vec3(0.0,0.0,0.0);val axis=Vec3(0.0,0.0,1.0);val eye=Vec3(60.0,-70.0,90.0)
        // Grabbed 2 mm beyond the actual end: clicking must not jump from 10 to 12.
        val drag=ExtrusionDrag(origin,axis,10.0,12.0)
        fun ray(t: Double)=origin+axis*t-eye
        assertEquals(10.0,drag.depth(eye,ray(12.0),false)!!,1e-8)
        assertEquals(16.0,drag.depth(eye,ray(18.36),false)!!,1e-8)
        assertEquals(16.4,drag.depth(eye,ray(18.36),true)!!,1e-8)
        assertEquals(-8.0,drag.depth(eye,ray(-6.0),false)!!,1e-8)
        assertNull(drag.depth(eye,axis,false))
    }
    @Test fun everyOriginPlaneAndAttachedFaceUseTheSameAxisAsTheirBody() {
        for(plane in SketchPlane.entries) {
            val sketch=Sketch(plane=plane,width="10 mm",height="8 mm",depth="12 mm")
            val body=sketch.extrudedMesh(emptyMap())!!
            assertEquals(sketch.extrusionAxis()*12.0,body.vertices[4]-body.vertices[0])
        }
        val frame=SketchFrame(Vec3(0.0,0.0,0.0),Vec3(1.0,0.0,0.0),Vec3(0.0,0.0,1.0))
        assertEquals(Vec3(0.0,-1.0,0.0),Sketch(frame=frame).extrusionAxis())
    }
}
