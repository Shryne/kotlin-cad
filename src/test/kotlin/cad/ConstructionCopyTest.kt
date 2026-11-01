package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.test.*

class ConstructionCopyTest {
    init { System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString()) }
    @TempDir lateinit var temp:Path
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private val box=obj("""{"type":"box","x":20,"y":20,"z":20}""")
    private fun evaluate(shape:JsonObject)=CadNativeGeometry.kernel.request("evaluate",buildJsonObject { put("shape",shape) }).get(30,TimeUnit.SECONDS)
    private fun edge(at:String)=obj("""{"at":$at}""")
    private fun finish(type:String,size:JsonElement=JsonPrimitive(2),edges:List<JsonObject> = listOf(edge("[0,0,10]")))=buildJsonObject {
        put("type",type);put("shape",box);put(if(type=="fillet") "radius" else "distance",size);put("edges",JsonArray(edges))
    }
    @Test fun `fillets chamfers and rotation produce exact solids and reject invalid selectors`() {
        val edges=CadNativeGeometry.kernel.request("inspect_edges",buildJsonObject { put("shape",box) }).get(10,TimeUnit.SECONDS).getValue("edges").jsonArray
        assertEquals(12,edges.size)
        assertTrue(edges.any { it.jsonObject.getValue("at").jsonArray.map { p -> p.jsonPrimitive.double }==listOf(0.0,0.0,10.0) })
        assertEquals(8000-(4-PI)*20,evaluate(finish("fillet")).getValue("volume").jsonPrimitive.double,1e-5)
        assertEquals(7960.0,evaluate(finish("chamfer")).getValue("volume").jsonPrimitive.double,1e-5)
        for(bad in listOf(finish("fillet",JsonPrimitive(100)),finish("chamfer",JsonPrimitive(-2)),finish("fillet",edges=listOf(edge("[1,1,1]"))),finish("fillet",edges=listOf(edge("[0,0,10]"),edge("[0,0,10]"))))) assertFails { evaluate(bad) }
        val rotated=obj("""{"type":"rotate","axis":[0,1,0],"origin":[0,0,0],"angle":{"angleExpression":"tilt"},"shape":{"type":"box","x":30,"y":20,"z":4}}""")
        val mesh=NativeShapeFeature("tilt","Schräge",rotated).evaluateMesh(ParameterEvaluator.evaluate(listOf(Parameter("tilt","90 deg"))))
        assertEquals(4.0,mesh.bounds.size.x,1e-6);assertEquals(30.0,mesh.bounds.size.z,1e-6)
        assertFails { resolveNativeShape(rotated,ParameterEvaluator.evaluate(listOf(Parameter("tilt","90 mm")))) }
        val overlap=obj("""{"type":"intersect","a":{"type":"box","x":20,"y":20,"z":20},"b":{"type":"translate","offset":[10,0,0],"shape":{"type":"box","x":20,"y":20,"z":20}}}""")
        assertEquals(4000.0,evaluate(overlap).getValue("volume").jsonPrimitive.double,1e-6)
    }
    @Test fun `general copy retains source previews parameters saves and undoes atomically`() {
        val radius=obj("""{"parameter":"rounding"}""")
        val shape=finish("fillet",radius)
        val source=NativeShapeFeature("source","Quelle",box).evaluateMesh(emptyMap())
        val document=DocumentState(mesh=source)
        val analysis=MeshAnalysisJobs.start(source,"mm")
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(runCatching { MeshAnalysisJobs.completedResult(analysis) }.isFailure && System.nanoTime()<deadline) Thread.sleep(10)
        val response=MeshReconstructionJobs.proposeConstruction(document,analysis,"Gerundete Kopie",shape,listOf(Parameter("rounding","2 mm")))
        val id=response.getValue("proposalId").jsonPrimitive.content
        assertEquals("construction",response.getValue("modelKind").jsonPrimitive.content)
        assertTrue(response.containsKey("surfaceDeviation"))
        assertTrue(document.features.isEmpty());assertSame(source,document.mesh)
        val (preview,mesh)=MeshReconstructionJobs.preview(document,id)
        assertSame(source,preview.mesh);assertEquals(1,preview.features.size)
        val history=DocumentHistory(document);history.commit(preview)
        history.undo();assertEquals(document,history.current)
        history.redo();assertEquals(preview,history.current)
        val path=temp.resolve("rounded-copy.kcad");ProjectIO.write(path,ProjectArchive(preview))
        val reopened=ProjectIO.read(path).document
        assertEquals(preview.features,reopened.features)
        assertEquals(mesh.bounds,reopened.bodyMeshes().single().bounds)
        val changed=reopened.copy(parameters=listOf(Parameter("rounding","4 mm")))
        assertNotEquals(reopened.bodyMeshes().single().vertices,changed.bodyMeshes().single().vertices)
        assertFails { MeshReconstructionJobs.get(id).apply(document.copy(parameters=listOf(Parameter("other","1 mm")))) }
        assertFails { MeshReconstructionJobs.get(id).apply(document.copy(mesh=source.translated(Vec3(1.0,0.0,0.0)))) }
        MeshReconstructionJobs.remove(id);assertFails { MeshReconstructionJobs.get(id) }
        assertTrue(document.features.isEmpty())
        assertTrue(assistantTools.any { it.name=="cad_propose_construction_copy" })
        assertTrue(assistantModelInfo(buildJsonObject { put("edges",JsonArray(emptyList())) }).containsKey("edges"))
    }
    @Test fun `angled plate and triangular ribs with a bore construct one bracket`() {
        val shape=obj("""{"type":"cut","a":{"type":"union","a":{"type":"rotate","axis":[0,1,0],"angle":0.3,"shape":{"type":"box","x":80,"y":60,"z":5}},"b":{"type":"extrude","outer":[[0,0,0],[70,0,0],[0,0,-60]],"vector":[0,5,0]}},"b":{"type":"translate","offset":[30,30,-40],"shape":{"type":"cylinder","radius":4,"height":80}}}""")
        val result=evaluate(shape)
        assertTrue(result.getValue("valid").jsonPrimitive.boolean)
        assertTrue(result.getValue("volume").jsonPrimitive.double>1000)
    }
}
