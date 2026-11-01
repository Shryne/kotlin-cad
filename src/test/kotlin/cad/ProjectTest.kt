package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ProjectTest {
    @TempDir lateinit var directory: Path
    private fun mesh() = MeshImporter.obj("sample.obj", "v 0 0 0\nv 12.125 0 0\nv 0 9.5 3\nf 1 2 3")
    @Test fun projectEmbedsGeometryAndPreservesCameraParametersAndUnits() {
        val source = directory.resolve("original.obj")
        Files.writeString(source, "original can be removed")
        val state = DocumentState(mesh(), source.toString(), DisplaySettings(true, false, false, "in"),
            listOf(Parameter("width", "2 in"), Parameter("half", "width / 2")))
        val camera = CameraPose(120.0, -34.0, 18.0, -22.0, 2.5)
        val path = directory.resolve("part.kcad")
        ProjectIO.write(path, ProjectArchive(state, camera))
        Files.delete(source)
        val loaded = ProjectIO.read(path)
        assertEquals(camera, loaded.camera)
        assertEquals(state.display.copy(wireframe=false), loaded.document.display)
        assertEquals(state.parameters, loaded.document.parameters)
        assertEquals(state.mesh!!.vertices, loaded.document.mesh!!.vertices)
        assertContentEquals(state.mesh.indices, loaded.document.mesh.indices)
        assertEquals(25.4, ParameterEvaluator.evaluate(loaded.document.parameters).getValue("half").value)
    }
    @Test fun emptyProjectCanStoreParameters() {
        val path = directory.resolve("empty.kcad")
        ProjectIO.write(path, ProjectArchive(DocumentState(parameters = listOf(Parameter("width", "20 mm")))))
        val loaded = ProjectIO.read(path)
        assertNull(loaded.document.mesh)
        assertEquals("width", loaded.document.parameters.single().name)
    }
    @Test fun saveRefusesAccidentalOverwriteAndFailedSavePreservesExistingFile() {
        val path = directory.resolve("part.kcad")
        ProjectIO.write(path, ProjectArchive(DocumentState(mesh())))
        val before = Files.readAllBytes(path)
        assertFailsWith<IllegalArgumentException> { ProjectIO.write(path, ProjectArchive(DocumentState())) }
        assertContentEquals(before, Files.readAllBytes(path))
        assertFailsWith<IllegalArgumentException> {
            ProjectIO.write(path, ProjectArchive(DocumentState(parameters = listOf(Parameter("bad", "missing + 1")))), true)
        }
        assertContentEquals(before, Files.readAllBytes(path))
        ProjectIO.write(path, ProjectArchive(DocumentState()), true)
        assertNull(ProjectIO.read(path).document.mesh)
    }
    @Test fun unsupportedVersionAndUnexpectedZipEntriesFail() {
        val path = directory.resolve("bad.kcad")
        fun archive(entry: String, text: String) {
            ZipOutputStream(Files.newOutputStream(path)).use {
                it.putNextEntry(ZipEntry(entry)); it.write(text.toByteArray()); it.closeEntry()
            }
        }
        archive("project.json", """{"format":"kotlin-cad","version":999}""")
        assertContains(assertFailsWith<IllegalArgumentException> { ProjectIO.read(path) }.message!!, "Projektformat")
        archive("../outside.txt", "unsafe")
        assertFailsWith<IllegalArgumentException> { ProjectIO.read(path) }
    }
    @Test fun historyBranchesAndRetainsOnlyBoundedSnapshots() {
        val initial = DocumentState(mesh())
        val history = DocumentHistory(initial, limit = 2)
        val one = initial.copy(parameters = listOf(Parameter("x", "1 mm")))
        val two = initial.copy(parameters = listOf(Parameter("x", "2 mm")))
        val three = initial.copy(parameters = listOf(Parameter("x", "3 mm")))
        history.commit(one); history.commit(two); history.commit(three)
        assertSame(two, history.undo())
        assertSame(one, history.undo())
        assertFalse(history.canUndo)
        assertSame(two, history.redo())
        history.commit(three)
        assertFalse(history.canRedo)
        history.reset(initial)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
