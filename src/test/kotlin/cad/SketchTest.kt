package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class SketchTest {
    @TempDir lateinit var directory: Path
    @Test fun parameterChangesRecomputeProfilesOnEveryPlane() {
        SketchPlane.entries.forEach { plane ->
            val sketch = Sketch(plane = plane, width = "breite / 2", height = "2 cm")
            val first = sketch.points(ParameterEvaluator.evaluate(listOf(Parameter("breite", "60 mm"))))
            val second = sketch.points(ParameterEvaluator.evaluate(listOf(Parameter("breite", "80 mm"))))
            assertEquals(plane.point(30.0,20.0), first[2])
            assertEquals(plane.point(40.0,20.0), second[2])
        }
    }
    @Test fun invalidDependencyDimensionAndNegativeSizeAreRejected() {
        listOf("missing", "2 deg", "-2 mm", "0 mm", "1 mm * 1 mm").forEach {
            assertFails { Sketch(width = it).points(emptyMap()) }
        }
    }
    @Test fun circleAndExpressionsRoundTripAndHistoryPreservesDefinitions() {
        val sketch = Sketch(shape = SketchShape.CIRCLE, plane = SketchPlane.YZ, u = "-5 mm", width = "r")
        val state = DocumentState(parameters = listOf(Parameter("r", "12 mm")), sketches = listOf(sketch))
        val points = state.sketchPoints().single()
        assertEquals(96, points.size)
        assertEquals(Vec3(0.0,7.0,0.0), points.first())
        val file = directory.resolve("sketch.kcad")
        ProjectIO.write(file, ProjectArchive(state))
        assertEquals(state.canonicalFeatures(), ProjectIO.read(file).document)
        val history = DocumentHistory(state)
        history.commit(state.copy(sketches = emptyList()))
        assertEquals(state, history.undo())
        assertTrue(history.redo().sketches.isEmpty())
    }
    @Test fun existingVersionOneProjectStillLoads() {
        assertNotNull(ProjectIO.read(Path.of("samples/bracket.kcad")).document.mesh)
    }
}
