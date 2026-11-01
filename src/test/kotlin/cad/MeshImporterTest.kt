package cad

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.*
import org.junit.jupiter.api.Test

class MeshImporterTest {
    @Test fun binaryStlMayHaveSolidHeader() {
        val bytes = ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("solid binary, not ASCII".toByteArray())
        bytes.position(80); bytes.putInt(1)
        repeat(3) { bytes.putFloat(0f) }
        listOf(0f,0f,0f, 20f,0f,0f, 0f,10f,0f).forEach { bytes.putFloat(it) }
        bytes.putShort(0)
        val mesh = MeshImporter.stl("test.stl", bytes.array())
        assertEquals(1, mesh.triangleCount)
        assertEquals(Vec3(20.0,10.0,0.0), mesh.bounds.size)
    }
    @Test fun asciiStlAndScientificNotation() {
        val mesh = MeshImporter.stl("test.stl", """
            solid triangle
            facet normal 0 0 1
            outer loop
            vertex -1e1 0 0
            vertex 10 0 0
            vertex 0 5 0
            endloop
            endfacet
            endsolid triangle
        """.trimIndent().toByteArray())
        assertEquals(1, mesh.triangleCount)
        assertEquals(20.0, mesh.bounds.size.x)
    }
    @Test fun invalidStlCannotSilentlyBecomeTriangleSoup() {
        assertFailsWith<IllegalArgumentException> {
            MeshImporter.stl("bad.stl", "facet normal 0 0 1\nvertex 0 0 0\nendfacet".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            MeshImporter.stl("bad.stl", "facet normal 0 0 1\nvertex NaN 0 0\nvertex 1 0 0\nvertex 0 1 0\nendfacet".toByteArray())
        }
    }
    @Test fun objAcceptsRelativeAndTextureNormalIndices() {
        val mesh = MeshImporter.obj("quad.obj", """
            v 0 0 0
            v 10 0 0
            v 10 10 0
            v 0 10 0
            f -4/1/1 -3/2/1 -2/3/1 -1/4/1 # quad
        """.trimIndent())
        assertEquals(2, mesh.triangleCount)
        assertEquals(100.0, area(mesh), 1e-8)
    }
    @Test fun concaveObjKeepsNotchInBothWindings() {
        val vertices = "v 0 0 0\nv 3 0 0\nv 3 1 0\nv 1 1 0\nv 1 3 0\nv 0 3 0\n"
        for (face in listOf("f 1 2 3 4 5 6", "f 6 5 4 3 2 1")) {
            val mesh = MeshImporter.obj("concave.obj", vertices + face)
            assertEquals(4, mesh.triangleCount)
            assertEquals(5.0, area(mesh), 1e-8)
            for (triangle in mesh.indices.toList().chunked(3)) {
                val centroidX = triangle.sumOf { mesh.vertices[it].x } / 3
                val centroidY = triangle.sumOf { mesh.vertices[it].y } / 3
                assertFalse(centroidX > 1 && centroidY > 1)
            }
        }
    }
    @Test fun objErrorsIncludeLineNumber() {
        val error = assertFailsWith<IllegalArgumentException> {
            MeshImporter.obj("bad.obj", "v 0 0 0\nf 1 2 3")
        }
        assertContains(error.message!!, "Zeile 2")
    }
    @Test fun unsupportedOrEmptyGeometryFails() {
        assertFailsWith<IllegalArgumentException> { MeshImporter.obj("empty.obj", "# no faces") }
        assertFailsWith<IllegalArgumentException> { MeshImporter.stl("empty.stl", ByteArray(84)) }
    }
    @Test fun nonFiniteObjFailsBeforeTriangulation() {
        assertFailsWith<IllegalArgumentException> { MeshImporter.obj("bad.obj", "v Infinity 0 0") }
    }
    @Test fun cameraBoundsAndReset() {
        val camera = CameraState()
        camera.orbit(1000.0, 1000.0)
        assertTrue(camera.pitch in -89.0..89.0)
        camera.magnify(Double.POSITIVE_INFINITY)
        assertEquals(1.0, camera.zoom)
        camera.magnify(1e9)
        assertEquals(40.0, camera.zoom)
        camera.magnify(1e-12)
        assertEquals(0.05, camera.zoom)
        camera.panX = 200.0; camera.reset()
        assertEquals(0.0, camera.panX)
        assertEquals(1.0, camera.zoom)
    }
    private fun area(mesh: MeshData) = mesh.indices.toList().chunked(3).sumOf { triangle ->
        val a = mesh.vertices[triangle[0]]; val b = mesh.vertices[triangle[1]]; val c = mesh.vertices[triangle[2]]
        abs((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)) / 2
    }
}
