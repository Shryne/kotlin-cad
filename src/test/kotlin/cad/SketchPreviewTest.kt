package cad

import org.junit.jupiter.api.Test
import kotlin.test.*

class SketchPreviewTest {
    @Test fun newDraftPreviewDoesNotModifyDocumentOrHistory() {
        val original=DocumentState(parameters=listOf(Parameter("d","12 mm")))
        val history=DocumentHistory(original)
        val draft=Sketch(depth="d",mirror=SketchPlane.YZ)
        val context=original.prepareSketchPreview(draft.id)
        assertNull(context.geometry)
        assertEquals(2,context.bodies(draft).size)
        assertEquals(12.0,context.bodies(draft).first().bounds.size.z)
        assertEquals(24.0,context.bodies(draft.copy(depth="d * 2")).first().bounds.size.z)
        assertTrue(original.sketches.isEmpty());assertSame(original,history.current);assertFalse(history.canUndo)
    }
    @Test fun existingBodyAndItsDependentGeometryAreReplacedOnlyInPreview() {
        val base=Sketch(id="base",depth="12 mm")
        val state=DocumentState(sketches=listOf(base))
        val face=state.selectFace(1)
        val child=Sketch(id="child",support=face.reference,frame=face.frame,width="5 mm",height="5 mm",depth="3 mm")
        val other=Sketch(id="other",u="100 mm",depth="7 mm")
        val document=state.copy(sketches=listOf(base,child,other))
        val context=document.prepareSketchPreview(base.id)
        assertEquals(setOf("base","child"),context.affectedIds)
        assertEquals(100.0,context.geometry!!.bounds.low.x)
        val bodies=context.bodies(base.copy(depth="25 mm"))
        assertEquals(2,bodies.size)
        assertEquals(25.0,bodies[0].bounds.high.z)
        assertEquals(25.0,bodies[1].bounds.low.z)
        assertEquals(28.0,bodies[1].bounds.high.z)
        assertEquals(12.0,document.bodyMeshes()[0].bounds.high.z)
        assertEquals(15.0,document.bodyMeshes()[1].bounds.high.z)
        // Editing a profile no longer removes its independent extrusion.
        assertEquals(12.0,context.bodies(base.copy(depth=null)).first().bounds.high.z)
    }
    @Test fun negativeDepthAndInvalidIntermediateInputAreHandledWithoutChangingTheSource() {
        val original=Sketch(id="body",depth="10 mm")
        val state=DocumentState(sketches=listOf(original))
        val context=state.prepareSketchPreview(original.id)
        assertNull(context.geometry)
        val negative=context.bodies(original.copy(depth="-20 mm")).single()
        assertEquals(-20.0,negative.bounds.low.z);assertEquals(0.0,negative.bounds.high.z)
        listOf("","12 deg","0 mm","missing").forEach { text -> assertFails { context.bodies(original.copy(depth=text)) } }
        assertEquals(10.0,context.bodies(original).single().bounds.high.z)
        assertEquals(original,state.sketches.single())
    }
    @Test fun importedContextIsReusedAndRetainedWhenAnExistingBodyIsEdited() {
        val imported=MeshData("import",listOf(Vec3(0.0,0.0,0.0),Vec3(1.0,0.0,0.0),Vec3(0.0,1.0,0.0)),intArrayOf(0,1,2))
        val sketch=Sketch(depth="10 mm")
        val state=DocumentState(mesh=imported,sketches=listOf(sketch))
        val context=state.prepareSketchPreview(sketch.id)
        assertSame(imported,context.geometry)
        assertEquals(20.0,context.bodies(sketch.copy(depth="20 mm")).single().bounds.size.z)
        assertSame(imported,state.mesh)
    }
}
