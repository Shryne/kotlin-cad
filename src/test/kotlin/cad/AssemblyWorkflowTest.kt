package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.*

class AssemblyWorkflowTest {
    init { System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString()) }
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun ops(vararg values:JsonObject)=JsonArray(values.toList())
    private fun put(f:CadFeature)=buildJsonObject { put("op","put_feature");put("feature",f.json()) }
    private fun box(id:String="part",width:String="10",y:Double=4.0,z:Double=3.0)=NativeShapeFeature(id,id,obj("""{"type":"box","x":$width,"y":$y,"z":$z}"""),singleSolid=true)
    private fun base()=DocumentState(features=listOf(box()))
    @Test fun `batch validates final state only and preserves one history transaction`(@TempDir dir:Path) {
        val start=base().withBodyColor("part","#123456");val history=DocumentHistory(start)
        val earlier=start.withBodyColor("part","#654321");history.commit(earlier)
        val shape=box(width="""{"expression":"span - 2 mm"}""")
        val candidate=earlier.assemblyBatch(ops(
            buildJsonObject { put("op","set_parameter");put("name","span");put("expression","1 mm") },put(shape),
            buildJsonObject { put("op","set_parameter");put("name","span");put("expression","20 mm") },
            put(InstanceFeature("copy","Linked","part",x="25 mm",rotationZ="90 deg")),
            buildJsonObject { put("op","set_body_color");put("bodyId","copy");put("color","#FF6600") }
        )).document
        assertEquals(18.0,candidate.sceneBodies().first().mesh.bounds.size.x,1e-6)
        assertEquals("#654321",candidate.bodyColors.getValue("part").color);assertEquals("#FF6600",candidate.bodyColors.getValue("copy").color)
        history.commit(candidate);assertEquals(earlier,history.undo());assertEquals(start,history.undo());history.redo();assertEquals(candidate,history.redo())
        val file=dir.resolve("instances.kcad");ProjectIO.write(file,ProjectArchive(candidate));val loaded=ProjectIO.read(file).document
        assertEquals(candidate.features,loaded.features);assertEquals(candidate.bodyColors,loaded.bodyColors);assertEquals(candidate.sceneBodies().map { it.mesh.bounds },loaded.sceneBodies().map { it.mesh.bounds })
        assertFails { earlier.assemblyBatch(ops(put(box(width="-1")))) };assertEquals(earlier,history.undo())
        assertFails { candidate.assemblyBatch(ops(buildJsonObject { put("op","delete_feature");put("id","part") })) }
        val empty=candidate.assemblyBatch(ops(buildJsonObject { put("op","delete_feature");put("id","part") },buildJsonObject { put("op","delete_feature");put("id","copy") })).document
        assertTrue(empty.features.isEmpty())
    }
    @Test fun `incremental cache avoids native rebuilds for colors placements and unrelated parameters`() {
        val start=DocumentState(parameters=listOf(Parameter("extent","17.123 mm")),features=listOf(box("a","""{"expression":"extent"}"""),box("b","17.123"),box("c","23.432")))
        val before=GeometryCache.builds.get();start.bodyMeshes();val first=GeometryCache.builds.get();assertTrue(first-before<=2)
        start.withBodyColor("a","#FF0000").bodyMeshes();assertEquals(first,GeometryCache.builds.get())
        val moved=start.copy(features=start.features.map { it.withPosition("100 mm","0 mm","0 mm") });moved.bodyMeshes();assertEquals(first,GeometryCache.builds.get())
        moved.copy(parameters=start.parameters+Parameter("unused","9 mm")).bodyMeshes();assertEquals(first,GeometryCache.builds.get())
        val changed=moved.copy(parameters=listOf(Parameter("extent","18.123 mm")));changed.bodyMeshes();assertEquals(first+1,GeometryCache.builds.get())
        assertEquals(18.123,changed.sceneBodies().first().mesh.bounds.size.x,1e-6);assertEquals(17.123,changed.sceneBodies()[1].mesh.bounds.size.x,1e-6)
    }
    @Test fun `instances inherit geometry and face colors rotate independently and reject cycles`() {
        val source=base().withBodyColor("part","#2244CC");val mesh=source.colorMesh(source.sceneBodies().single())
        val area=ColorArea("face","Face","#FF8800",mesh.colorPatches().first(),colorRevision(mesh))
        val painted=source.withColorArea("part",area)
        val state=painted.copy(features=painted.features+InstanceFeature("i","Turned","part",x="30 mm",rotationZ="90 deg"))
        val colors=state.renderColors();val n=mesh.triangleCount;assertEquals(colors.take(n),colors.drop(n))
        val instance=state.sceneBodies().last();assertEquals(26.0,instance.mesh.bounds.low.x,1e-6);assertEquals(10.0,instance.mesh.bounds.size.y,1e-6)
        val moved=state.copy(features=state.features.map { if(it.id=="part") it.withPosition("200 mm","0 mm","0 mm") else it });assertEquals(instance.mesh.bounds,moved.sceneBodies().last().mesh.bounds)
        val changed=state.copy(features=listOf(box(width="20"),state.features.last()));assertEquals(20.0,changed.sceneBodies().last().mesh.bounds.size.y,1e-6)
        assertTrue(state.withBodyColor("i","#00FF00").renderColors().drop(n).all { it=="#00FF00" })
        assertFails { state.copy(features=listOf(InstanceFeature("i","Loop","j"),InstanceFeature("j","Loop","i"))).bodyMeshes() }
        val pairs=state.copy(features=state.features+box("other").copy(x="26 mm")).checkInterference(listOf("i","other"),.00001,.000001)
        assertEquals("native-brep",pairs.getValue("pairs").jsonArray.single().jsonObject.getValue("method").jsonPrimitive.content)
    }
    @Test fun `private cancellation preserves document and does not kill shared kernel`() {
        val state=base();val work=AssemblyWork(10);work.cancel()
        assertFailsWith<CancellationException> { work.run { state.assemblyBatch(ops(put(box("new")))) } };work.close()
        assertEquals(1,state.features.size)
        val response=CadNativeGeometry.kernel.request("ping").get();assertTrue(response.isNotEmpty())
        val progress=mutableListOf<String>();AssemblyWork(10) { message,_->progress.add(message) }.use { it.run { state.assemblyBatch(ops(put(box("progress","16.12345")))) } }
        assertTrue(progress.any { it.startsWith("Operation") });assertTrue(progress.any { it.startsWith("Körper") })
    }
    @Test fun `path detects collision missed by final pose and certifies widely separated travel`() {
        val state=DocumentState(features=listOf(box("moving","2",2.0,2.0),box("wall","1",6.0,6.0).copy(x="5 mm",y="-2 mm",z="-2 mm")))
        val path=obj("""{"bodyId":"moving","obstacleIds":["wall"],"stepMm":1,"waypoints":[{"x":0,"y":0,"z":0},{"x":10,"y":0,"z":0}]}""")
        val result=state.assemblyAnalysis("path",path);assertEquals("blocked",result.getValue("status").jsonPrimitive.content)
        val clear=state.assemblyAnalysis("path",obj("""{"bodyId":"moving","obstacleIds":["wall"],"stepMm":1,"waypoints":[{"x":0,"y":20,"z":0},{"x":10,"y":20,"z":0}]}"""));assertTrue(clear.getValue("continuousClearanceCertified").jsonPrimitive.boolean)
        val contact=state.assemblyAnalysis("path",obj("""{"bodyId":"moving","obstacleIds":["wall"],"stepMm":1,"waypoints":[{"x":3,"y":0,"z":0},{"x":3,"y":1,"z":0}]}"""));assertEquals("inconclusive",contact.getValue("status").jsonPrimitive.content)
        assertEquals(0.0,state.sceneBodies().first().mesh.bounds.low.x,1e-6)
    }
    @Test fun `manufacturing screening reports thin walls and configurable gaps`() {
        val state=DocumentState(features=listOf(box("thin","10",10.0,.4),box("near","10",10.0,.4).copy(x="10.1 mm")))
        val result=state.assemblyAnalysis("manufacturing",obj("""{"bodyIds":["thin","near"],"minWallMm":0.8,"minFeatureMm":1.2,"minClearanceMm":0.2}"""))
        assertTrue(result.getValue("warnings").jsonArray.any { it.jsonObject.getValue("thicknessMm").jsonPrimitive.double<.5 })
        assertEquals(1,result.getValue("smallClearances").jsonArray.size)
        assertEquals(3,result.getValue("smallClearances").jsonArray.first().jsonObject.getValue("closestA").jsonArray.size)
        val relaxed=state.assemblyAnalysis("manufacturing",obj("""{"bodyIds":["thin","near"],"minWallMm":0.2,"minFeatureMm":0.2,"minClearanceMm":0.05}"""))
        assertTrue(relaxed.getValue("warnings").jsonArray.isEmpty());assertTrue(relaxed.getValue("smallClearances").jsonArray.isEmpty())
    }
    @Test fun `counts exploded views and revision comparisons preserve saved assembly`() {
        val before=base();val after=before.copy(features=before.features+InstanceFeature("i","Second","part",x="30 mm"))
        val report=after.assemblyReport();assertEquals(2,report.getValue("bodyCount").jsonPrimitive.int);assertEquals(1,report.getValue("definitionCount").jsonPrimitive.int)
        val original=after.sceneBodies().map { it.mesh.bounds };val (meshes,exploded)=after.explodedMeshes(20.0)
        assertNotEquals(original,meshes.map { it.bounds });assertEquals(original,after.sceneBodies().map { it.mesh.bounds });assertFalse(exploded.getValue("modelChanged").jsonPrimitive.boolean)
        val diff=after.compareAssembly(before);assertEquals(listOf(JsonPrimitive("i")),diff.getValue("added").jsonArray.toList())
        assertTrue(assistantTools.map { it.name }.containsAll(listOf("cad_apply_batch","cad_create_instance","cad_check_assembly_path","cad_get_assembly")))
    }
}
