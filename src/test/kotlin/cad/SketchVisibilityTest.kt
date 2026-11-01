package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class SketchVisibilityTest {
    @TempDir lateinit var directory: Path
    @Test fun hidingAContourDoesNotRemoveItsBodyOrChangeExport() {
        val sketch=Sketch(id="profile",depth="8 mm")
        val visible=DocumentState(sketches=listOf(sketch))
        val hidden=visible.copy(sketches=listOf(sketch.copy(visible=false)))
        assertEquals(1,visible.sketchPoints().size);assertTrue(hidden.sketchPoints().isEmpty())
        assertEquals(visible.bodyMeshes().single().vertices,hidden.bodyMeshes().single().vertices)
        assertEquals(visible.renderMesh()!!.triangleCount,hidden.renderMesh()!!.triangleCount)
        ProjectIO.write(directory.resolve("hidden.kcad"),ProjectArchive(hidden))
        assertEquals(hidden.canonicalFeatures(),ProjectIO.read(directory.resolve("hidden.kcad")).document)
        val history=DocumentHistory(visible);history.commit(hidden);assertEquals(visible,history.undo())
        assertEquals(hidden,history.redo())
    }
    @Test fun invisibleInvalidProfilesAreStillValidated() {
        val hidden=DocumentState(sketches=listOf(Sketch(visible=false,width="missing")))
        assertFails { hidden.bodyMeshes() }
        assertFails { ProjectIO.write(directory.resolve("invalid.kcad"),ProjectArchive(hidden)) }
    }
    @Test fun legacyProfilesAreVisibleByDefault() {
        val json=Sketch().json().toMutableMap().apply { remove("visible") }
        assertTrue(Sketch.read(kotlinx.serialization.json.JsonObject(json)).visible)
    }
}
