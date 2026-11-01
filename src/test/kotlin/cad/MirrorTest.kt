package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class MirrorTest {
    @TempDir lateinit var directory: Path
    @Test fun reflectionIsInvolutionAroundOffsetPlane() {
        val point=Vec3(3.0,7.0,-2.0)
        assertEquals(Vec3(3.0,7.0,12.0),SketchPlane.XY.reflect(point,5.0))
        assertEquals(Vec3(3.0,3.0,-2.0),SketchPlane.XZ.reflect(point,5.0))
        assertEquals(Vec3(7.0,7.0,-2.0),SketchPlane.YZ.reflect(point,5.0))
        SketchPlane.entries.forEach { assertEquals(point,it.reflect(it.reflect(point,5.0),5.0)) }
    }
    @Test fun mirroredBodiesRetainOutwardFacesAndClosedSurface() {
        val original=Sketch(u="5 mm",width="10 mm",height="20 mm",depth="3 mm").extrudedMesh(emptyMap())!!
        fun signedVolume(mesh: MeshData) = mesh.indices.toList().chunked(3).sumOf { face ->
            val a=mesh.vertices[face[0]];val b=mesh.vertices[face[1]];val c=mesh.vertices[face[2]]
            (a.x*(b.y*c.z-b.z*c.y)+a.y*(b.z*c.x-b.x*c.z)+a.z*(b.x*c.y-b.y*c.x))/6
        }
        SketchPlane.entries.forEach { plane ->
            val body=original.mirrored(plane,2.0)
            assertEquals(600.0,signedVolume(body),1e-8)
            val edges=body.indices.toList().chunked(3).flatMap { f -> (0..2).map { f[it] to f[(it+1)%3] } }
            edges.forEach { assertTrue(it.second to it.first in edges) }
            assertContentEquals(original.indices,body.mirrored(plane,2.0).indices)
            assertEquals(original.vertices,body.mirrored(plane,2.0).vertices)
        }
    }
    @Test fun linkedCopiesRecomputeFromParametersAndRoundTrip() {
        val sketch=Sketch(u="5 mm",width="w",depth="3 mm",mirror=SketchPlane.YZ,mirrorOffset="offset")
        val state=DocumentState(parameters=listOf(Parameter("w","10 mm"),Parameter("offset","2 mm")),sketches=listOf(sketch))
        assertEquals(2,state.sketchPoints().size)
        assertEquals(2,state.bodyMeshes().size)
        assertEquals(-11.0,state.bodyMeshes()[1].bounds.low.x)
        val next=state.copy(parameters=listOf(Parameter("w","20 mm"),Parameter("offset","3 mm")))
        assertEquals(-19.0,next.bodyMeshes()[1].bounds.low.x)
        val path=directory.resolve("mirrored.kcad")
        ProjectIO.write(path,ProjectArchive(next))
        assertEquals(next.canonicalFeatures(),ProjectIO.read(path).document)
        assertFails { state.copy(parameters=listOf(Parameter("w","10 mm"))).bodyMeshes() }
        assertFails { sketch.copy(mirrorOffset="2 deg").profileCopies(ParameterEvaluator.evaluate(state.parameters)) }
    }
    @Test fun flatProfilesAndLegacyProjectsRemainValid() {
        val state=DocumentState(sketches=listOf(Sketch(mirror=SketchPlane.XZ)))
        assertEquals(2,state.sketchPoints().size);assertTrue(state.bodyMeshes().isEmpty())
        listOf("bracket","sketches","extrusions").forEach {
            assertTrue(ProjectIO.read(Path.of("samples/$it.kcad")).document.bodyMeshes().size <= 2)
        }
    }
}
