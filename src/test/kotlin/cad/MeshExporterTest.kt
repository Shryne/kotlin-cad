package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.test.*

class MeshExporterTest {
    @TempDir lateinit var directory: Path
    private fun scene(): DocumentState {
        val body=Sketch(id="body",u="5 mm",width="20 mm",height="10 mm",depth="12 mm",mirror=SketchPlane.YZ)
        val state=DocumentState(sketches=listOf(body))
        val face=state.selectFace(1)
        val child=Sketch(id="child",frame=face.frame,support=face.reference,width="3 mm",height="2 mm",depth="4 mm")
        return state.copy(sketches=state.sketches+child)
    }
    @Test fun binaryStlPreservesBodiesMirrorsAndFaceAttachments() {
        val state=scene();val original=state.renderMesh()!!
        val path=directory.resolve("assembly.stl")
        val result=MeshExporter.write(path,state)
        val bytes=Files.readAllBytes(path)
        assertEquals(3,result.parts);assertEquals(original.triangleCount,result.triangles)
        assertEquals(84L+50L*original.triangleCount,result.bytes)
        assertEquals(original.triangleCount,ByteBuffer.wrap(bytes,80,4).order(ByteOrder.LITTLE_ENDIAN).int)
        val loaded=MeshImporter.read(path)
        assertEquals(original.bounds,loaded.bounds)
        assertEquals(original.triangleCount,loaded.triangleCount)
        assertEquals(original.indices.toList().map { original.vertices[it] },loaded.indices.toList().map { loaded.vertices[it] })
    }
    @Test fun objUsesCorrectPartOffsetsAndLocaleIndependentExactCoordinates() {
        val before=Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val import=MeshData("unsafe\nname",listOf(Vec3(.125,0.0,0.0),Vec3(3.141592653589793,0.0,0.0),Vec3(.125,2.25,0.0)),intArrayOf(0,1,2))
            val state=scene().copy(mesh=import);val original=state.renderMesh()!!
            val path=directory.resolve("assembly.OBJ")
            val result=MeshExporter.write(path,state)
            val loaded=MeshImporter.read(path)
            assertEquals(4,result.parts)
            assertEquals(original.vertices,loaded.vertices)
            assertContentEquals(original.indices,loaded.indices)
            assertEquals(4,Files.readAllLines(path).count { it.startsWith("o part_") })
        } finally { Locale.setDefault(before) }
    }
    @Test fun overwriteProtectionAndFailedExportPreserveTheDestination() {
        val path=directory.resolve("part.stl")
        MeshExporter.write(path,scene());val before=Files.readAllBytes(path)
        assertFails { MeshExporter.write(path,scene()) };assertContentEquals(before,Files.readAllBytes(path))
        val huge=MeshData("huge",listOf(Vec3(1e100,0.0,0.0),Vec3(0.0,1.0,0.0),Vec3(0.0,0.0,1.0)),intArrayOf(0,1,2))
        assertFails { MeshExporter.write(path,DocumentState(mesh=huge),true) }
        assertContentEquals(before,Files.readAllBytes(path))
        MeshExporter.write(path,DocumentState(sketches=listOf(Sketch(depth="2 mm"))),true)
        assertEquals(12,MeshImporter.read(path).triangleCount)
        Files.list(directory).use { files -> assertFalse(files.anyMatch { it.fileName.toString().startsWith(".cad-export-") }) }
    }
    @Test fun flatSketchesAreExcludedAndProjectStateDoesNotChange() {
        val state=scene().copy(sketches=scene().sketches+Sketch(name="Flat"),display=DisplaySettings(visible=false))
        val history=DocumentHistory(state)
        val result=MeshExporter.write(directory.resolve("hidden-but-committed.obj"),state)
        assertEquals(3,result.parts)
        assertSame(state,history.current);assertFalse(history.canUndo)
        assertFails { MeshExporter.write(directory.resolve("flat.stl"),DocumentState(sketches=listOf(Sketch()))) }
        assertFails { MeshExporter.write(directory.resolve("part.step"),state) }
        assertFails { MeshExporter.write(directory.resolve("missing/part.stl"),state) }
    }
}
