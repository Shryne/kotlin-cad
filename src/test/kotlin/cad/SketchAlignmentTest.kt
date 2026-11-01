package cad

import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.test.*

class SketchAlignmentTest {
    @Test fun facesPointTowardCameraIncludingSlopedAndOppositeNormals() {
        val normals = listOf(Vec3(1.0,0.0,0.0),Vec3(-1.0,0.0,0.0),Vec3(0.0,1.0,0.0),Vec3(0.0,-1.0,0.0),Vec3(0.0,0.0,1.0),Vec3(0.0,0.0,-1.0),Vec3(1.0,2.0,3.0).normalized(),Vec3(-2.0,-1.0,-3.0).normalized())
        for (n in normals) {
            val axis = if(abs(n.x)<.9) Vec3(1.0,0.0,0.0) else Vec3(0.0,1.0,0.0)
            val u=(axis-n*axis.dot(n)).normalized()
            val frame=SketchFrame(Vec3(3.0,4.0,5.0),u,n.cross(u))
            val original=CameraPose(panX=37.0,panY=-12.0,zoom=2.4)
            val pose=original.normalTo(frame)
            assertEquals(original.panX,pose.panX);assertEquals(original.panY,pose.panY);assertEquals(original.zoom,pose.zoom)
            val yaw=Math.toRadians(pose.yaw);val pitch=Math.toRadians(pose.pitch)
            val x=cos(yaw)*n.x+sin(yaw)*n.y
            val z=-sin(yaw)*n.x+cos(yaw)*n.y
            val y=cos(pitch)*(-n.z)-sin(pitch)*z
            val depth=sin(pitch)*(-n.z)+cos(pitch)*z
            assertEquals(0.0,x,1e-10);assertEquals(0.0,y,1e-10);assertEquals(-1.0,depth,1e-10)
            assertEquals(pose,pose.normalTo(frame))
        }
    }
    @Test fun originSketchPlanesProduceExpectedViews() {
        assertEquals(90.0,CameraPose().normalTo(Sketch(plane=SketchPlane.XY).drawingFrame()).pitch)
        assertEquals(0.0,CameraPose().normalTo(Sketch(plane=SketchPlane.XZ).drawingFrame()).yaw)
        assertEquals(90.0,CameraPose().normalTo(Sketch(plane=SketchPlane.YZ).drawingFrame()).yaw)
    }
}
