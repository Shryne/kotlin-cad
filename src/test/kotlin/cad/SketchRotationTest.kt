package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class SketchRotationTest {
    @TempDir lateinit var directory: Path
    private fun close(a: Vec3,b: Vec3) {
        assertEquals(a.x,b.x,1e-6);assertEquals(a.y,b.y,1e-6);assertEquals(a.z,b.z,1e-6)
    }
    @Test fun profilesRotateAroundTheirAnchorOnEveryPlane() {
        for(plane in SketchPlane.entries) for(shape in listOf(SketchShape.RECTANGLE,SketchShape.TRIANGLE)) {
            val profile=Sketch(plane=plane,shape=shape,u="7 mm",v="11 mm",width="20 mm",height="10 mm",angle="90 deg",depth="5 mm")
            val points=profile.points(emptyMap())
            close(plane.point(7.0,11.0),points[0]);close(plane.point(7.0,31.0),points[1])
            close(if(shape==SketchShape.RECTANGLE) plane.point(-3.0,31.0) else plane.point(-3.0,21.0),points[2])
            val mesh=profile.extrudedMesh(emptyMap())!!
            val volume=mesh.indices.toList().chunked(3).sumOf { f -> mesh.vertices[f[0]].dot(mesh.vertices[f[1]].cross(mesh.vertices[f[2]]))/6 }
            assertEquals(if(shape==SketchShape.RECTANGLE) 1000.0 else 500.0,volume,1e-6)
        }
    }
    @Test fun rotatedDragPreservesAngleAndFitsOppositeCornersInEitherDirection() {
        val values=ParameterEvaluator.evaluate(listOf(Parameter("tilt","30 deg")))
        val a=SketchPoint(8.0,12.0);val b=SketchPoint(23.0,39.0)
        for((start,end) in listOf(a to b,b to a)) {
            val profile=Sketch(angle="tilt").drawn(start,end,false,values)!!
            assertEquals("tilt",profile.angle)
            val points=profile.points(values)
            assertTrue(points.any { (it-Vec3(a.u,a.v,0.0)).norm()<1e-6 })
            assertTrue(points.any { (it-Vec3(b.u,b.v,0.0)).norm()<1e-6 })
        }
    }
    @Test fun angleExpressionsRoundTripAndInvalidDimensionsAreRejected() {
        val profile=Sketch(angle="tilt",depth="6 mm")
        val state=DocumentState(parameters=listOf(Parameter("tilt","-45 deg")),sketches=listOf(profile))
        val file=directory.resolve("rotation.kcad")
        ProjectIO.write(file,ProjectArchive(state));assertEquals(state.canonicalFeatures(),ProjectIO.read(file).document)
        for(angle in listOf("10 mm","1","missing","360001 deg")) assertFails { profile.copy(angle=angle).points(emptyMap()) }
        assertEquals(PI/2,profile.copy(angle="pi * 1 rad / 2").rotation(emptyMap()),1e-10)
        val old=profile.json().toMutableMap().apply { remove("angle") }
        assertEquals("0 deg",Sketch.read(kotlinx.serialization.json.JsonObject(old)).angle)
    }
    @Test fun attachedProfilesFollowRotatedParentFace() {
        val parent=Sketch(id="parent",width="40 mm",height="30 mm",depth="8 mm",angle="turn")
        val child=Sketch(id="child",width="5 mm",height="4 mm",depth="3 mm",support=FaceReference("parent",false,1,SketchShape.RECTANGLE))
        fun doc(angle: String)=DocumentState(parameters=listOf(Parameter("turn",angle)),sketches=listOf(parent,child))
        val original=doc("0 deg").evaluatedSketches()[1].frame!!.origin
        val rotated=doc("90 deg").evaluatedSketches()[1].frame!!.origin
        close(Vec3(-original.y,original.x,original.z),rotated)
        assertEquals(2,doc("30 deg").bodyMeshes().size)
    }
}
