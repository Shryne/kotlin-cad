package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*
import kotlin.math.*

class SketchSupportTest {
    @TempDir lateinit var directory: Path
    private fun parent()=Sketch(id="base",width="60 mm",height="40 mm",depth="height")
    private fun document()=DocumentState(parameters=listOf(Parameter("height","12 mm")),sketches=listOf(parent()))
    @Test fun faceFrameHandlesAllBoxSidesAndSketchesExtrudeAlongFaceNormal() {
        val state=document()
        (0 until 12).forEach { face ->
            val selection=state.selectFace(face)
            assertEquals(6,selection.patch.size)
            val child=Sketch(frame=selection.frame,support=selection.reference,width="5 mm",height="3 mm",depth="2 mm")
            val body=child.extrudedMesh(emptyMap())!!
            assertTrue(body.vertices.take(4).all { abs((it-selection.frame.origin).dot(selection.frame.normal))<1e-8 })
            assertTrue(body.vertices.drop(4).all { abs((it-selection.frame.origin).dot(selection.frame.normal)-2)<1e-8 })
        }
    }
    @Test fun attachmentFollowsParentAndSurvivesRoundTrip() {
        val state=document();val face=state.selectFace(1)
        assertEquals(12.0,face.frame.origin.z)
        val child=Sketch(id="child",frame=face.frame,support=face.reference,width="5 mm",height="3 mm",depth="2 mm")
        val next=state.copy(sketches=state.sketches+child,parameters=listOf(Parameter("height","25 mm")))
        val resolved=next.evaluatedSketches().last()
        assertEquals(25.0,resolved.points(emptyMap()).first().z)
        assertEquals(27.0,next.bodyMeshes().last().bounds.high.z)
        val path=directory.resolve("face.kcad");ProjectIO.write(path,ProjectArchive(next))
        assertEquals(25.0,ProjectIO.read(path).document.evaluatedSketches().last().frame!!.origin.z)
        assertFails { next.copy(sketches=listOf(child)).bodyMeshes() }
        assertFails { next.copy(sketches=listOf(parent().copy(depth=null),child)).bodyMeshes() }
        assertFails { next.copy(sketches=listOf(parent().copy(shape=SketchShape.CIRCLE),child)).bodyMeshes() }
    }
    @Test fun slantedImportedFaceUsesOrthonormalCoordinates() {
        val mesh=MeshData("slanted",listOf(Vec3(2.0,3.0,4.0),Vec3(12.0,3.0,14.0),Vec3(2.0,13.0,4.0)),intArrayOf(0,1,2))
        val face=DocumentState(mesh=mesh).selectFace(0)
        val sketch=Sketch(frame=face.frame,support=face.reference,width="3 mm",height="2 mm")
        val point=sketch.points(emptyMap())[2]
        assertEquals(3.0,sketch.localProject(point).u,1e-8);assertEquals(2.0,sketch.localProject(point).v,1e-8)
        assertEquals(0.0,(point-face.frame.origin).dot(face.frame.normal),1e-8)
        assertFails { SketchFrame(Vec3(0.0,0.0,0.0),Vec3(2.0,0.0,0.0),Vec3(0.0,1.0,0.0)) }
    }
    @Test fun cyclicAttachmentsAndCurvedCylinderWallsAreRejected() {
        val cyclic=parent().copy(support=FaceReference("base",triangle=1,shape=SketchShape.RECTANGLE))
        assertFails { document().copy(sketches=listOf(cyclic)).evaluatedSketches() }
        val circle=Sketch(shape=SketchShape.CIRCLE,depth="10 mm")
        val state=DocumentState(sketches=listOf(circle))
        assertNotNull(state.selectFace(1));assertFails { state.selectFace(188) }
    }
    @Test fun backdropOmitsCoplanarTriangulationDiagonals() {
        val body=parent().extrudedMesh(ParameterEvaluator.evaluate(document().parameters))!!
        assertEquals(12,body.contextEdges().size)
    }
    @Test fun mirroredBodySupportsAndLegacyPlanesRemainValid() {
        val state=document().copy(sketches=listOf(parent().copy(mirror=SketchPlane.YZ)))
        val face=state.selectFace(13)
        assertTrue(face.reference.mirrored)
        val child=Sketch(frame=face.frame,support=face.reference)
        assertEquals(2,state.copy(sketches=state.sketches+child).evaluatedSketches().size)
        assertFails { state.copy(sketches=listOf(parent(),child)).evaluatedSketches() }
        listOf("bracket","sketches","extrusions","mirrors").forEach { ProjectIO.read(Path.of("samples/$it.kcad")).document.evaluatedSketches() }
    }
}
