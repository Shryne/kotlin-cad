package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class BodyVisibilityTest {
    @TempDir lateinit var directory: Path
    private fun document(): DocumentState {
        val mesh=Sketch(name="Import",depth="5 mm").extrudedMesh(emptyMap())!!
        return DocumentState(mesh=mesh,sketches=listOf(Sketch(id="a",name="A",u="100 mm",width="20 mm",height="10 mm",depth="8 mm",mirror=SketchPlane.YZ)))
    }
    @Test fun `import extrusion and mirror have stable independent visibility`() {
        val original=document()
        assertEquals(listOf("import","sketch:a","mirror:a"),original.sceneBodies().map { it.id })
        val hidden=original.setBodyVisibility("import",false).setBodyVisibility("mirror:a",false)
        assertEquals(listOf(false,true,false),hidden.sceneBodies().map { it.visible })
        assertEquals(original.bodyMeshes().first().vertices,hidden.renderMesh()!!.vertices)
        assertEquals(original.sketchPoints(),hidden.sketchPoints())
        assertEquals(original.framingBounds(),hidden.framingBounds())
        val allHidden=hidden.setBodyVisibility("sketch:a",false)
        assertNull(allHidden.renderMesh())
        assertEquals(original.framingBounds(),allHidden.framingBounds())
        assertEquals(original,allHidden.setBodyVisibility("import",true).setBodyVisibility("mirror:a",true).setBodyVisibility("sketch:a",true))
        assertFailsWith<IllegalArgumentException> { original.setBodyVisibility("missing",false) }
    }
    @Test fun `visible scene triangle indices retain correct face references`() {
        val hidden=document().setBodyVisibility("import",false).setBodyVisibility("sketch:a",false)
        val selected=hidden.selectFace(0)
        assertEquals(FaceReference("a",true,0,SketchShape.RECTANGLE),selected.reference)
        assertTrue(selected.patch.all { it.x<=-100 })
        assertFails { hidden.selectFace(12) }
    }
    @Test fun `hidden support remains valid and hidden surroundings stay absent in preview`() {
        val parent=Sketch(id="parent",depth="10 mm")
        val base=DocumentState(sketches=listOf(parent))
        val face=base.selectFace(0)
        val child=Sketch(id="child",name="Child",width="5 mm",height="5 mm",depth="2 mm",frame=face.frame,support=face.reference)
        val hidden=base.copy(sketches=listOf(parent,child)).setBodyVisibility(bodyId(parent.id),false)
        assertEquals(2,hidden.bodyMeshes().size)
        val preview=hidden.prepareSketchPreview(child.id)
        assertNull(preview.geometry)
        assertEquals(1,preview.bodies(child).size)
    }
    @Test fun `visibility persists supports history and does not remove exported bodies`() {
        val original=document();val hidden=original.setBodyVisibility("mirror:a",false)
        val history=DocumentHistory(original);history.commit(hidden)
        assertEquals(original,history.undo());assertEquals(hidden,history.redo())
        val project=directory.resolve("hidden.kcad")
        ProjectIO.write(project,ProjectArchive(hidden))
        val restored=ProjectIO.read(project).document
        assertEquals(hidden.display,restored.display)
        assertEquals(listOf(true,true,false),restored.sceneBodies().map { it.visible })
        val export=MeshExporter.write(directory.resolve("all.stl"),hidden)
        assertEquals(3,export.parts);assertEquals(36,export.triangles)
    }
}
