package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ConstructionApiTest {
    init { System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString()) }
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun part(id:String,shape:String,x:String="0 mm",z:String="0 mm")=NativeShapeFeature(id,id,obj(shape),x=x,z=z,singleSolid=true)
    private val cube="""{"type":"box","x":10,"y":10,"z":10}"""
    private val plate=part("plate","""{"type":"cut","a":{"type":"box","x":30,"y":20,"z":4},"b":{"type":"translate","offset":[10,5,-1],"shape":{"type":"box","x":{"expression":"8 mm + clearance"},"y":10,"z":6}}}""")
    private val tongue=part("tongue","""{"type":"box","x":{"expression":"8 mm - clearance"},"y":10,"z":4}""",x="50 mm")
    private fun JsonObject.number(key:String)=getValue(key).jsonPrimitive.double
    @Test fun `direct slot and tongue recompute measurements preserve identity and round trip`(@TempDir dir:Path) {
        val empty=DocumentState();val a=empty.withConstruction(plate,listOf(Parameter("clearance","0.2 mm")),false)
        val created=a.withConstruction(tongue,emptyList(),false);assertNull(created.mesh)
        val before=created.constructionInfo("tongue");assertEquals(7.8,before.getValue("dimensions").jsonObject.number("x"),1e-5)
        assertEquals(312.0,before.number("volumeMm3"),1e-5)
        assertEquals(1,before.getValue("solidCount").jsonPrimitive.int)
        val changed=created.copy(parameters=listOf(Parameter("clearance","0.5 mm")));changed.bodyMeshes()
        assertEquals(300.0,changed.constructionInfo("tongue").number("volumeMm3"),1e-5)
        assertEquals(2060.0,changed.constructionInfo("plate").number("volumeMm3"),1e-5)
        val edited=changed.withConstruction(tongue.copy(name="Mating tongue",shape=obj("""{"type":"box","x":{"expression":"8 mm - clearance"},"y":12,"z":4}""")),emptyList(),true)
        assertEquals("tongue",edited.constructionInfo("tongue").getValue("bodyId").jsonPrimitive.content)
        assertEquals(50.0,edited.sceneBodies().first { it.id=="tongue" }.mesh.bounds.low.x,1e-5)
        val history=DocumentHistory(created);history.commit(changed);history.commit(edited);assertEquals(changed,history.undo());assertEquals(created,history.undo());history.redo();assertEquals(edited,history.redo())
        val file=dir.resolve("parts.kcad");ProjectIO.write(file,ProjectArchive(edited));val loaded=ProjectIO.read(file).document
        assertEquals(edited.features,loaded.features);assertEquals(edited.parameters,loaded.parameters);assertEquals(360.0,loaded.constructionInfo("tongue").number("volumeMm3"),1e-5)
        assertTrue(assistantModelInfo(before).containsKey("solidCount"));assertTrue(assistantTools.any { it.name=="cad_create_construction" })
    }
    @Test fun `invalid update parameter edit and dependency deletion remain atomic`() {
        val state=DocumentState().withConstruction(plate,listOf(Parameter("clearance","0.2 mm")),false).withConstruction(tongue,emptyList(),false)
        val history=DocumentHistory(state)
        val invalid=tongue.copy(shape=obj("""{"type":"fillet","shape":{"type":"box","x":10,"y":10,"z":10},"radius":100,"edges":[{"at":[0,0,5]}]}"""))
        assertFails { history.commit(state.withConstruction(invalid,emptyList(),true)) }
        assertFails { history.commit(state.copy(parameters=listOf(Parameter("clearance","20 mm")))) }
        assertFails { state.withConstruction(tongue.copy(id="duplicate"),listOf(Parameter("clearance","2 mm")),false) }
        assertFails { state.withConstruction(part("split","""{"type":"union","a":$cube,"b":{"type":"translate","offset":[20,0,0],"shape":$cube}}"""),emptyList(),false) }
        assertFails { state.withConstruction(part("empty","""{"type":"cut","a":$cube,"b":$cube}"""),emptyList(),false) }
        assertEquals(state,history.current);assertFalse(history.canUndo)
        val dependent=state.copy(features=state.features+MirrorFeature("mirror","tongue",SketchPlane.YZ))
        assertFails { dependent.withoutFeature("tongue") }
        val removed=state.withoutFeature("tongue");history.commit(removed);assertEquals(1,removed.features.size);assertEquals(state.parameters,removed.parameters)
        assertEquals(state,history.undo());assertEquals(removed,history.redo())
    }
    @Test fun `interference distinguishes gap contact overlap and uses world positions`() {
        fun check(x:String)=DocumentState(features=listOf(part("a",cube),part("b",cube,x=x))).checkInterference(listOf("a","b"),.00001,.000001).getValue("pairs").jsonArray.single().jsonObject
        val separate=check("12 mm");assertEquals("separated",separate.getValue("status").jsonPrimitive.content);assertEquals(2.0,separate.number("distanceMm"),1e-6)
        val contact=check("10 mm");assertEquals("contact",contact.getValue("status").jsonPrimitive.content);assertEquals(0.0,contact.number("intersectionVolumeMm3"),1e-6)
        val overlap=check("8 mm");assertEquals("overlap",overlap.getValue("status").jsonPrimitive.content);assertEquals(200.0,overlap.number("intersectionVolumeMm3"),1e-5)
        assertEquals("native-brep",overlap.getValue("method").jsonPrimitive.content)
        val sketch=Sketch(id="s",width="10 mm",height="10 mm",depth="10 mm").syncLegacyElement()
        val faceted=DocumentState(sketches=listOf(sketch),features=listOf(part("b",cube,x="8 mm"))).canonicalFeatures()
        val r=faceted.checkInterference(listOf(faceted.features.first { it is ExtrusionFeature }.id,"b"),.00001,.000001).getValue("pairs").jsonArray.single().jsonObject
        assertEquals("faceted-solid",r.getValue("method").jsonPrimitive.content);assertEquals(200.0,r.number("intersectionVolumeMm3"),1e-4)
    }
    @Test fun `selected flat export preserves original assembly and rejects overwrite`(@TempDir dir:Path) {
        val state=DocumentState(features=listOf(part("wall","""{"type":"box","x":20,"y":4,"z":30}""",x="100 mm",z="50 mm"),part("other",cube)))
        val before=state.sceneBodies().map { it.mesh.bounds }
        val parts=state.exportParts(listOf("wall"),obj("""{"rotationX":"90 deg","placeOnBed":true}"""))
        assertEquals(1,parts.size);assertEquals(4.0,parts.single().bounds.size.z,1e-5);assertEquals(0.0,parts.single().bounds.low.z,1e-5)
        for(ext in listOf("stl","obj")) {
            val file=dir.resolve("wall.$ext");MeshExporter.writeParts(file,parts)
            val mesh=MeshImporter.read(file);assertEquals(4.0,mesh.bounds.size.z,1e-4);assertEquals(20.0,mesh.bounds.size.x,1e-4)
            assertFails { MeshExporter.writeParts(file,parts) }
        }
        assertEquals(before,state.sceneBodies().map { it.mesh.bounds });assertEquals("50 mm",(state.features.first() as NativeShapeFeature).z)
    }
}
