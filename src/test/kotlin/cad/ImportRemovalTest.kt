package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ImportRemovalTest {
    @TempDir lateinit var folder: Path
    @Test fun `removal retains attached sketch geometry and descendants across undo and save`() {
        val mesh=Sketch(name="Imported",depth="15 mm").extrudedMesh(emptyMap())
        val base=DocumentState(mesh=mesh,source="/example/source.obj")
        val face=base.selectFace(0)
        val attached=Sketch(id="attached",name="Attached",depth="4 mm",frame=face.frame,support=face.reference)
        val original=base.copy(sketches=listOf(attached),display=DisplaySettings(hiddenBodyIds=setOf("import")))
        val childFace=original.setBodyVisibility("import",false).selectFace(0)
        val child=Sketch(id="child",name="Child",width="2 mm",height="2 mm",depth="1 mm",frame=childFace.frame,support=childFace.reference)
        val withChild=original.copy(sketches=original.sketches+child)
        val childRemoved=withChild.removeImportedBody()
        assertEquals(withChild.bodyMeshes().map { it.vertices },childRemoved.bodyMeshes().map { it.vertices })
        val before=original.evaluatedSketches()
        val next=original.removeImportedBody()
        assertNull(next.mesh);assertNull(next.source)
        assertFalse("import" in next.display.hiddenBodyIds)
        assertNull(next.sketches.single().support)
        assertEquals(before.single().drawingFrame(),next.evaluatedSketches().single().drawingFrame())
        assertEquals(original.bodyMeshes().map { it.vertices },next.bodyMeshes().map { it.vertices })
        val history=DocumentHistory(original);history.commit(next)
        assertEquals(original,history.undo());assertEquals(next,history.redo())
        assertFailsWith<IllegalArgumentException> { next.removeImportedBody() }
        val project=folder.resolve("removed.kcad")
        ProjectIO.write(project,ProjectArchive(childRemoved))
        val restored=ProjectIO.read(project).document
        assertNull(restored.mesh);assertNull(restored.source)
        assertEquals(childRemoved.canonicalFeatures().sketches,restored.sketches)
        assertEquals(childRemoved.bodyMeshes().map { it.vertices },restored.bodyMeshes().map { it.vertices })
        assertTrue(assistantTools.any { it.name=="cad_delete_import" })
    }
}
