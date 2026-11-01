package cad

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.math.*

class BodyFramingTest {
    @Test fun `fit keeps projected corners inside unobscured area across orientations and aspect ratios`() {
        val bounds=Bounds(Vec3(80.0,-30.0,15.0),Vec3(120.0,10.0,80.0))
        val center=Vec3(0.0,0.0,0.0);val scale=2.0
        for((w,h) in listOf(1360.0 to 860.0,600.0 to 1000.0)) for((yaw,pitch) in listOf(-35.0 to 25.0,90.0 to 0.0,0.0 to 90.0,180.0 to -45.0)) {
            val pose=CameraPose(yaw,pitch,100.0,20.0,.2)
            val area=ViewArea(238.0,90.0,w-258.0,h-110.0)
            val fitted=pose.fittedTo(bounds,center,scale,w,h,area)
            assertEquals(yaw,fitted.yaw);assertEquals(pitch,fitted.pitch)
            val distance=cameraBaseDistance(w,h,35.0)/fitted.zoom
            val focal=h/2/tan(Math.toRadians(17.5))
            bounds.corners().forEach {
                val p=rotatedWorld(it,center,scale,yaw,pitch)
                val depth=p.z+distance
                assertTrue(depth>.1)
                val x=w/2+focal*(p.x+fitted.panX)/depth
                val y=h/2+focal*(p.y+fitted.panY)/depth
                assertTrue(x in area.left..area.left+area.width,"x=$x")
                assertTrue(y in area.top..area.top+area.height,"y=$y")
            }
            assertEquals(fitted,fitted.fittedTo(bounds,center,scale,w,h,area))
        }
    }
    @Test fun `tiny and flat bodies retain finite camera values within zoom limits`() {
        for(size in listOf(0.0,1e-8,1.0,1000.0)) {
            val bounds=Bounds(Vec3(100.0,100.0,100.0),Vec3(100.0+size,100.0+size,100.0))
            val fitted=CameraPose().fittedTo(bounds,Vec3(0.0,0.0,0.0),1.0,800.0,600.0,ViewArea(20.0,20.0,760.0,560.0))
            assertTrue(fitted.zoom in .05..40.0)
            assertTrue(listOf(fitted.panX,fitted.panY).all { it.isFinite() })
        }
    }
}
