package cad

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.math.sqrt

class SurfaceDrawingTest {
    @Test fun screenRaysMeetTheActualSupportPlaneInLocalCoordinates() {
        val frames=listOf(
            SketchFrame(Vec3(0.0,0.0,12.0),Vec3(1.0,0.0,0.0),Vec3(0.0,1.0,0.0)),
            SketchFrame(Vec3(3.0,5.0,0.0),Vec3(1.0,0.0,0.0),Vec3(0.0,0.0,1.0)),
            SketchFrame(Vec3(7.0,0.0,0.0),Vec3(0.0,1.0,0.0),Vec3(0.0,0.0,1.0)),
            SketchFrame(Vec3(2.0,3.0,4.0),Vec3(1/sqrt(2.0),0.0,1/sqrt(2.0)),Vec3(0.0,1.0,0.0)))
        frames.forEach { frame ->
            val target=frame.point(13.0,8.0)
            val origin=target+frame.normal*100.0+frame.uAxis*25.0
            val result=assertNotNull(frame.intersectRay(origin,target-origin))
            assertEquals(13.0,result.u,1e-8);assertEquals(8.0,result.v,1e-8)
        }
    }
    @Test fun parallelAndBehindCameraPlanesDoNotProduceUnboundedDrawingCoordinates() {
        val frame=Sketch().drawingFrame()
        assertNull(frame.intersectRay(Vec3(0.0,0.0,10.0),Vec3(1.0,0.0,0.0)))
        assertNull(frame.intersectRay(Vec3(0.0,0.0,10.0),Vec3(0.0,0.0,1.0)))
        assertNull(frame.intersectRay(Vec3(0.0,0.0,10.0),Vec3(0.0,0.0,0.0)))
    }
    @Test fun drawnProfilesRemainOnTheirSelectedFaceAfterSnapping() {
        val frame=SketchFrame(Vec3(2.0,7.0,5.0),Vec3(1.0,0.0,0.0),Vec3(0.0,0.0,1.0))
        val draft=Sketch(frame=frame)
        val a=frame.intersectRay(Vec3(4.2,-10.0,8.4),Vec3(0.0,1.0,0.0))!!
        val b=frame.intersectRay(Vec3(18.8,-10.0,14.3),Vec3(0.0,1.0,0.0))!!
        val drawn=draft.drawn(a,b,true)!!
        assertTrue(drawn.points(emptyMap()).all { it.y==7.0 })
        assertEquals("15 mm",drawn.width);assertEquals("6 mm",drawn.height)
    }
}
