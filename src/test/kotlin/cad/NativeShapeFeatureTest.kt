package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NativeShapeFeatureTest {
    @TempDir lateinit var temp:Path
    private fun parameter(name:String)=buildJsonObject { put("parameter",name) }
    @Test fun `native shape feature evaluates parameterized cuts position persistence and history`() {
        val kernel=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernel));System.setProperty("kotlin.cad.kernel",kernel.toString())
        val box=buildJsonObject { put("type","box");put("x",20);put("y",20);put("z",20) }
        val cylinder=buildJsonObject { put("type","translate");put("shape",buildJsonObject { put("type","cylinder");put("radius",parameter("boreRadius"));put("height",22) });put("offset",JsonArray(listOf(JsonPrimitive(10),JsonPrimitive(10),JsonPrimitive(-1)))) }
        val shape=buildJsonObject { put("type","cut");put("a",box);put("b",cylinder) }
        val feature=NativeShapeFeature("rack-section","Testprofil",shape,x="assemblyX")
        val document=DocumentState(parameters=listOf(Parameter("boreRadius","3 mm"),Parameter("assemblyX","5 mm")),features=listOf(feature)).canonicalFeatures()
        val first=document.sceneBodies().single().mesh
        assertEquals(Vec3(5.0,0.0,0.0),first.bounds.low)
        val changed=document.copy(parameters=listOf(Parameter("boreRadius","5 mm"),Parameter("assemblyX","8 mm")))
        val second=changed.sceneBodies().single().mesh
        assertEquals(Vec3(8.0,0.0,0.0),second.bounds.low)
        assertTrue(volume(second)<volume(first))
        assertEquals(document.canonicalFeatures(),document.copy(features=document.features.map { CadFeature.read(it.json()) }).canonicalFeatures())
        val project=temp.resolve("native-feature.kcad");ProjectIO.write(project,ProjectArchive(document))
        val loaded=ProjectIO.read(project).document
        assertEquals(document.features,loaded.features)
        assertEquals(first.bounds,loaded.sceneBodies().single().mesh.bounds)
        val history=DocumentHistory(document)
        assertTrue(history.commit(changed));assertEquals(8.0,history.current.sceneBodies().single().mesh.bounds.low.x)
        history.undo();assertEquals(5.0,history.current.sceneBodies().single().mesh.bounds.low.x)
        history.redo();assertEquals(8.0,history.current.sceneBodies().single().mesh.bounds.low.x)
    }
    private fun volume(mesh:MeshData):Double { var value=0.0;for(i in mesh.indices.indices step 3) { val a=mesh.vertices[mesh.indices[i]];val b=mesh.vertices[mesh.indices[i+1]];val c=mesh.vertices[mesh.indices[i+2]];value+=a.dot(b.cross(c))/6.0 };return kotlin.math.abs(value) }
}
