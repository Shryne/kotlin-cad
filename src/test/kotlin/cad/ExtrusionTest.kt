package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*
import kotlin.math.*

class ExtrusionTest {
    @TempDir lateinit var directory: Path
    private fun volume(mesh: MeshData): Double = mesh.indices.toList().chunked(3).sumOf { face ->
        val a=mesh.vertices[face[0]];val b=mesh.vertices[face[1]];val c=mesh.vertices[face[2]]
        (a.x*(b.y*c.z-b.z*c.y)+a.y*(b.z*c.x-b.x*c.z)+a.z*(b.x*c.y-b.y*c.x))/6
    }
    private fun assertClosed(mesh: MeshData) {
        val directed=mutableMapOf<Pair<Int,Int>,Int>()
        mesh.indices.toList().chunked(3).forEach { face ->
            for (i in 0..2) { val edge=face[i] to face[(i+1)%3];directed[edge]=(directed[edge] ?: 0)+1 }
        }
        directed.forEach { (edge,count) -> assertEquals(1,count); assertEquals(1,directed[edge.second to edge.first]) }
    }
    @Test fun boxesHaveCorrectVolumeAndClosedOutwardSurfacesOnAllPlanesAndDirections() {
        SketchPlane.entries.forEach { plane -> listOf("5 mm","-5 mm").forEach { depth ->
            val body=Sketch(plane=plane,width="20 mm",height="10 mm",depth=depth).extrudedMesh(emptyMap())!!
            assertEquals(8,body.vertices.size);assertEquals(12,body.triangleCount)
            assertEquals(1000.0,volume(body),1e-8);assertClosed(body)
            assertTrue(listOf(body.bounds.size.x,body.bounds.size.y,body.bounds.size.z).sorted()==listOf(5.0,10.0,20.0))
        } }
    }
    @Test fun circularPrismUsesParameterizedDepthAndApproximatesCylinderVolume() {
        val sketch=Sketch(shape=SketchShape.CIRCLE,width="10 mm",depth="d / 2")
        val body=sketch.extrudedMesh(ParameterEvaluator.evaluate(listOf(Parameter("d","20 mm"))))!!
        assertClosed(body);assertEquals(PI*100*10,volume(body),3.0)
        assertEquals(10.0,body.bounds.size.z)
        listOf("0 mm","2 deg","missing","1 mm * 1 mm").forEach { depth -> assertFails { sketch.copy(depth=depth).extrudedMesh(emptyMap()) } }
    }
    @Test fun extrusionRoundTripsWithFormulasAndRemainsIndependentOfImportedGeometry() {
        val sketch=Sketch(depth="d")
        val state=DocumentState(parameters=listOf(Parameter("d","12 mm")),sketches=listOf(sketch))
        val file=directory.resolve("body.kcad")
        ProjectIO.write(file,ProjectArchive(state))
        val loaded=ProjectIO.read(file).document
        assertEquals(state.canonicalFeatures(),loaded);assertNull(loaded.mesh)
        assertEquals(12.0,loaded.renderMesh()!!.bounds.size.z)
        assertEquals(24.0,loaded.copy(parameters=listOf(Parameter("d","24 mm"))).renderMesh()!!.bounds.size.z)
        assertFails { loaded.copy(parameters=emptyList()).bodyMeshes() }
    }
    @Test fun triangularPrismsAreClosedAndHaveExpectedVolumeInEveryDirection() {
        SketchPlane.entries.forEach { plane -> listOf("5 mm", "-5 mm").forEach { depth ->
            val sketch = Sketch(shape=SketchShape.TRIANGLE, plane=plane, width="20 mm", height="10 mm", depth=depth)
            val body = sketch.extrudedMesh(emptyMap())!!
            assertEquals(8, body.triangleCount)
            assertClosed(body)
            assertEquals(500.0, volume(body), 1e-8)
        } }
    }
    @Test fun triangleDragParametersAndProjectRoundTripPreserveApex() {
        val triangle = Sketch(shape=SketchShape.TRIANGLE).drawn(SketchPoint(22.0,14.0), SketchPoint(2.0,4.0), false)!!
        assertEquals(listOf(Vec3(2.0,4.0,0.0),Vec3(22.0,4.0,0.0),Vec3(12.0,14.0,0.0)), triangle.points(emptyMap()))
        val state = DocumentState(parameters=listOf(Parameter("base", "24 mm")), sketches=listOf(triangle.copy(width="base",depth="base / 4")))
        val path = directory.resolve("triangle.kcad")
        ProjectIO.write(path, ProjectArchive(state))
        val restored = ProjectIO.read(path).document
        assertEquals(state.canonicalFeatures(), restored)
        assertEquals(720.0, volume(restored.bodyMeshes().single()), 1e-8)
        assertFails { triangle.copy(height="0 mm").points(emptyMap()) }
    }

}
