package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.test.*

class MeshMeasurementsTest {
    init { System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString()) }
    private fun shape(text:String)=Json.parseToJsonElement(text).jsonObject
    private fun mesh(text:String)=NativeShapeFeature("fixture","Fixture",shape(text)).evaluateMesh(emptyMap())
    private val normal=Vec3(0.0,0.0,1.0)
    @Test fun `sections recover true outer extent and through hole on oblique planes`() {
        val plate=mesh("""{"type":"cut","a":{"type":"box","x":40,"y":30,"z":10},"b":{"type":"translate","offset":[20,15,-1],"shape":{"type":"cylinder","radius":4,"height":12}}}""")
        val section=MeshSections.cut(plate,Vec3(0.0,0.0,5.0),normal)
        assertFalse(section.ambiguous);assertEquals(2,section.paths.size);assertTrue(section.paths.all { it.closed })
        val all=section.paths.flatMap { it.points };assertEquals(40.0,all.maxOf { it.x }-all.minOf { it.x },1e-6)
        val fits=section.paths.flatMap { ContourFitting.fit(it,normal,.03) };assertTrue(fits.any { it["kind"]?.jsonPrimitive?.content=="circle" })
        assertTrue(fits.filter { it["kind"]?.jsonPrimitive?.content=="circle" }.all { abs(it.getValue("radius").jsonPrimitive.double-4)<.03 })
        val oblique=MeshSections.cut(plate,Vec3(20.0,15.0,5.0),Vec3(1.0,0.0,1.0))
        assertTrue(oblique.paths.isNotEmpty());assertTrue(oblique.paths.flatMap { it.points }.all { abs((it-Vec3(20.0,15.0,5.0)).dot(Vec3(1.0,0.0,1.0).normalized()))<1e-6 })
        val coplanar=MeshSections.cut(plate,Vec3(0.0,0.0,0.0),normal);assertTrue(coplanar.coplanarTriangles>0)
        assertTrue(coplanar.paths.all { it.closed });assertFalse(coplanar.ambiguous)
        assertFails { MeshSections.cut(plate,Vec3(0.0,0.0,0.0),Vec3(0.0,0.0,0.0)) }
    }
    @Test fun `curve fits distinguish arcs freeform and coarse polygon and preserve residuals`() {
        val arc=SectionPath((0..64).map { i -> val a=PI*i/128;Vec3(10*cos(a),10*sin(a),0.0) },false)
        val fit=ContourFitting.fit(arc,normal,.01);assertEquals(1,fit.size);assertEquals("arc",fit.single().getValue("kind").jsonPrimitive.content)
        assertEquals(10.0,fit.single().getValue("radius").jsonPrimitive.double,1e-5)
        val square=SectionPath(listOf(Vec3(0.0,0.0,0.0),Vec3(10.0,0.0,0.0),Vec3(10.0,10.0,0.0),Vec3(0.0,10.0,0.0)),true)
        assertTrue(ContourFitting.fit(square,normal,.01).none { it["kind"]?.jsonPrimitive?.content=="circle" })
        val freeform=SectionPath((0..40).map { i -> val t=i/40.0;Vec3(20*t,10*sin(PI*t)*sin(PI*t),0.0) },false)
        val curves=ContourFitting.fit(freeform,normal,.005)
        assertTrue(curves.all { it.getValue("maxResidualMm").jsonPrimitive.double<=.005 })
        assertTrue(curves.any { it["kind"]?.jsonPrimitive?.content=="bezier" },curves.toString())
    }
    @Test fun `rounded and chamfered section measurements report transitions`() {
        val rounded=mesh("""{"type":"fillet","shape":{"type":"box","x":40,"y":30,"z":10},"radius":3,"edges":[{"at":[0,0,5]}]}""")
        val report=MeshMeasurements.sections(rounded,Vec3(0.0,0.0,0.0),normal,listOf(2.0,5.0,8.0),.02)
        val sections=report.getValue("sections").jsonArray
        assertTrue(sections.all { s -> s.jsonObject.getValue("contours").jsonArray.any { c -> c.jsonObject.getValue("fits").jsonArray.any { f -> f.jsonObject["kind"]?.jsonPrimitive?.content=="arc" } } })
        val chamfer=mesh("""{"type":"chamfer","shape":{"type":"box","x":40,"y":30,"z":10},"distance":3,"edges":[{"at":[0,0,5]}]}""")
        val details=MeshMeasurements.sections(chamfer,Vec3(0.0,0.0,0.0),normal,listOf(5.0),.001).getValue("sections").jsonArray.single().jsonObject.getValue("edgeDetails").jsonArray
        assertTrue(details.any { abs(it.jsonObject.getValue("widthMm").jsonPrimitive.double-3*sqrt(2.0))<.01 })
    }
    @Test fun `bore profiles measure counterbore depth and do not classify exterior cylinders as bores`() {
        val part=mesh("""{"type":"cut","a":{"type":"cut","a":{"type":"box","x":40,"y":30,"z":20},"b":{"type":"translate","offset":[20,15,-1],"shape":{"type":"cylinder","radius":3,"height":22}}},"b":{"type":"translate","offset":[20,15,14],"shape":{"type":"cylinder","radius":6,"height":7}}}""")
        val report=MeshMeasurements.bore(part,Vec3(20.0,15.0,0.0),normal,1.0,19.0,19,.03)
        val profiles=report.getValue("profiles").jsonArray.map { it.jsonObject }
        assertEquals(3.0,profiles.first().getValue("radiusMm").jsonPrimitive.double,.03)
        assertEquals(6.0,profiles.last().getValue("radiusMm").jsonPrimitive.double,.03)
        assertTrue(profiles.any { it["measured"]?.jsonPrimitive?.boolean==false },"Coplanar step must be flagged unknown")
        val solid=mesh("""{"type":"cylinder","radius":6,"height":20}""")
        assertTrue(MeshMeasurements.bore(solid,Vec3(0.0,0.0,0.0),normal,1.0,19.0,7,.03).getValue("profiles").jsonArray.all { !it.jsonObject.getValue("measured").jsonPrimitive.boolean })
    }
    @Test fun `side countersink measures conical angle and increasing radius`() {
        val part=mesh("""{"type":"rotate","axis":[0,1,0],"angle":1.5707963267948966,"shape":{"type":"cut","a":{"type":"box","x":30,"y":30,"z":20},"b":{"type":"translate","offset":[15,15,0],"shape":{"type":"cone","radius1":3,"radius2":7,"height":20}}}}""")
        val report=MeshMeasurements.bore(part,Vec3(0.0,15.0,-15.0),Vec3(1.0,0.0,0.0),1.0,19.0,13,.04)
        val segments=report.getValue("segments").jsonArray.map { it.jsonObject }
        val cone=segments.first { it.getValue("kind").jsonPrimitive.content=="conical-transition-candidate" }
        assertTrue(cone.getValue("supportedByMultipleSections").jsonPrimitive.boolean)
        assertEquals(2*atan(.2)*180/PI,cone.getValue("includedAngleDegrees").jsonPrimitive.double,.2)
        assertEquals(18.0,cone.getValue("depthMm").jsonPrimitive.double,.01)
    }
    @Test fun `rounded section fit can be rebuilt as connected curved extrusion`() {
        val source=mesh("""{"type":"fillet","shape":{"type":"box","x":40,"y":30,"z":10},"radius":3,"edges":[{"at":[0,0,5]}]}""")
        val section=MeshSections.cut(source,Vec3(0.0,0.0,5.0),normal)
        val curves=ContourFitting.fit(section.paths.single(),normal,.02)
        val shape=buildJsonObject {put("type","extrudeCurves");put("outer",JsonArray(curves));put("vector",Vec3(0.0,0.0,5.0).measureJson())}
        val copy=NativeShapeFeature("curves","Kurven",shape).evaluateMesh(emptyMap())
        assertEquals(40.0,copy.bounds.size.x,.05);assertEquals(30.0,copy.bounds.size.y,.05)
    }
    @Test fun `curved extrusion and cone evaluate true curves with expected volume`() {
        val circle=shape("""{"type":"extrudeCurves","outer":[{"kind":"circle","center":[0,0,0],"normal":[0,0,1],"radius":10}],"holes":[[{"kind":"circle","center":[0,0,0],"normal":[0,0,1],"radius":3}]],"vector":[0,0,5]}""")
        fun volume(s:JsonObject)=CadNativeGeometry.kernel.request("evaluate",buildJsonObject {put("shape",s)}).get(10,TimeUnit.SECONDS).getValue("volume").jsonPrimitive.double
        assertEquals(PI*(100-9)*5,volume(circle),1e-5)
        assertEquals(PI*7*(4+10+25)/3,volume(shape("""{"type":"cone","radius1":2,"radius2":5,"height":7}""")),1e-5)
        val curved=shape("""{"type":"extrudeCurves","outer":[{"kind":"bezier","controlPoints":[[0,0,0],[10,-5,0],[20,-5,0],[30,0,0]]},{"kind":"line","start":[30,0,0],"end":[30,10,0]},{"kind":"arc","start":[30,10,0],"mid":[15,25,0],"end":[0,10,0]},{"kind":"line","start":[0,10,0],"end":[0,0,0]}],"vector":[0,0,4]}""")
        assertTrue(volume(curved)>1200)
        assertFails { volume(shape("""{"type":"extrudeCurves","outer":[{"kind":"line","start":[0,0,0],"end":[5,0,0]}],"vector":[0,0,5]}""")) }
    }
    @Test fun `measurement jobs paginate cancel and reject stale project results`() {
        val source=mesh("""{"type":"box","x":10,"y":10,"z":10}""");val document=DocumentState(mesh=source)
        val id=MeasurementJobs.start(source,document) { buildJsonObject { put("answer",42) } }.getValue("jobId").jsonPrimitive.content
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(MeasurementJobs.read(id,document,0,10).getValue("status").jsonPrimitive.content=="running" && System.nanoTime()<deadline)Thread.sleep(5)
        assertEquals("complete",MeasurementJobs.read(id,document,0,10).getValue("status").jsonPrimitive.content)
        val changed=document.copy(parameters=listOf(Parameter("x","1 mm")))
        assertTrue(MeasurementJobs.read(id,changed,0,10).getValue("stale").jsonPrimitive.boolean)
        assertFails { MeasurementJobs.requireCurrent(id,changed) }
        MeasurementJobs.cancel(id);assertEquals("cancelled",MeasurementJobs.read(id,document,0,10).getValue("status").jsonPrimitive.content)
        assertTrue(assistantTools.any { it.name=="cad_measure_mesh_sections" })
    }
}
