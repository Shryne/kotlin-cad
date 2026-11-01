package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class BodyIsolationTest {
    @TempDir lateinit var directory: Path
    private fun document()=DocumentState(mesh=Sketch(name="Import",depth="5 mm").extrudedMesh(emptyMap()),
        sketches=listOf(Sketch(id="a",name="Body",depth="10 mm",mirror=SketchPlane.YZ,visible=false)),
        display=DisplaySettings(visible=false,hiddenBodyIds=setOf("mirror:a")))
    @Test fun `isolate enables exactly the selected hidden body and keeps sketches and framing`() {
        val original=document()
        val isolated=original.isolateBody("mirror:a")
        assertTrue(isolated.display.visible)
        assertEquals(listOf(false,false,true),isolated.sceneBodies().map { it.visible })
        assertEquals(original.sketches,isolated.sketches)
        assertEquals(original.framingBounds(),isolated.framingBounds())
        assertEquals(isolated,isolated.isolateBody("mirror:a"))
        assertEquals(FaceReference("a",true,0,SketchShape.RECTANGLE),isolated.selectFace(0).reference)
        assertFailsWith<IllegalArgumentException> { original.isolateBody("missing") }
    }
    @Test fun `show all clears local and global hiding without showing sketches`() {
        val original=document()
        val all=original.showAllBodies()
        assertTrue(all.display.visible);assertTrue(all.display.hiddenBodyIds.isEmpty())
        assertTrue(all.sceneBodies().all { it.visible })
        assertEquals(original.sketches,all.sketches)
        assertEquals(all,all.showAllBodies())
        assertEquals(DocumentState(),DocumentState().showAllBodies())
    }
    @Test fun `isolation is one undo step and survives project save`() {
        val original=document();val isolated=original.isolateBody("import")
        val history=DocumentHistory(original);history.commit(isolated)
        assertEquals(original,history.undo());assertEquals(isolated,history.redo())
        history.commit(isolated.showAllBodies());assertEquals(isolated,history.undo())
        val path=directory.resolve("isolated.kcad")
        ProjectIO.write(path,ProjectArchive(isolated))
        val restored=ProjectIO.read(path).document
        assertEquals(isolated.display,restored.display)
        assertEquals(listOf(true,false,false),restored.sceneBodies().map { it.visible })
    }
}
