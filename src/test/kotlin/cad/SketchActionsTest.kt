package cad

import kotlin.test.*
import org.junit.jupiter.api.Test

class SketchActionsTest {
    @Test fun `duplicate preserves profile formulas attachment and reflected contours without a body`() {
        val sketch=Sketch(name="Profil",width="breite",height="breite / 2",depth="8 mm",mirror=SketchPlane.YZ,mirrorOffset="3 mm",angle="15 deg",centered=true,visible=false,
            support=FaceReference(triangle=0),frame=SketchFrame(Vec3(0.0,0.0,0.0),Vec3(1.0,0.0,0.0),Vec3(0.0,1.0,0.0)))
        val document=DocumentState(parameters=listOf(Parameter("breite","40 mm")),sketches=listOf(sketch))
        val copy=document.duplicateSketch(sketch.id)
        val duplicate=copy.sketches.last()
        assertNotEquals(sketch.id,duplicate.id)
        assertEquals(sketch.copy(id=duplicate.id,name="Profil Kopie",depth=null,visible=true),duplicate)
        val before=document.copy(sketches=document.sketches.map { it.copy(support=null) }).bodyMeshes()
        val after=copy.copy(sketches=copy.sketches.map { it.copy(support=null) }).bodyMeshes()
        assertEquals(before.size,after.size)
        before.zip(after).forEach { (a,b) -> assertEquals(a.vertices,b.vertices);assertContentEquals(a.indices,b.indices) }
        assertEquals(document.parameters,copy.parameters)
        assertEquals(sketch,copy.sketches.first())
    }
    @Test fun `duplicate names remain unique and within limit`() {
        val source=Sketch(name="x".repeat(128))
        val document=DocumentState(sketches=listOf(source)).duplicateSketch(source.id).duplicateSketch(source.id)
        assertEquals(3,document.sketches.map { it.id }.distinct().size)
        assertEquals(3,document.sketches.map { it.name }.distinct().size)
        assertTrue(document.sketches.last().name.endsWith(" Kopie 2"))
        assertEquals(128,document.sketches.last().name.length)
    }
    @Test fun `rename trims validates and changes only the name`() {
        val source=Sketch(name="Alt",depth="10 mm")
        val document=DocumentState(sketches=listOf(source))
        assertEquals(document.copy(sketches=listOf(source.copy(name="Neu"))),document.renameSketch(source.id," Neu "))
        assertFailsWith<IllegalArgumentException> { document.renameSketch(source.id,"  ") }
        assertFailsWith<IllegalArgumentException> { document.renameSketch(source.id,"x".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { document.renameSketch("missing","Neu") }
        assertFailsWith<IllegalArgumentException> { document.duplicateSketch("missing") }
    }
    @Test fun `rename and duplicate are separate undo steps`() {
        val sketch=Sketch(name="Original")
        val original=DocumentState(sketches=listOf(sketch));val history=DocumentHistory(original)
        val renamed=original.renameSketch(sketch.id,"Neu");history.commit(renamed)
        val duplicated=renamed.duplicateSketch(sketch.id);history.commit(duplicated)
        assertEquals(renamed,history.undo());assertEquals(original,history.undo())
        assertEquals(renamed,history.redo());assertEquals(duplicated,history.redo())
    }
}
