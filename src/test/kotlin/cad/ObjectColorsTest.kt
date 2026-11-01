package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ObjectColorsTest {
    init { System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString()) }
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private val cube=NativeShapeFeature("cube","Cube",obj("""{"type":"box","x":10,"y":10,"z":10}"""),singleSolid=true)
    private fun base()=DocumentState(features=listOf(cube,cube.copy(id="second",x="20 mm")))
    private fun top(state:DocumentState):ColorArea {
        val regions=state.colorRegions("cube")
        val region=regions.getValue("regions").jsonArray.first { it.jsonObject.getValue("normal").jsonObject.getValue("z").jsonPrimitive.double>.9 }.jsonObject
        return state.colorAreaFrom(buildJsonObject { put("bodyId","cube");put("areaId","lid");put("name","Top surface");put("color","#FF8800");put("sourceRevision",regions.getValue("sourceRevision"));put("regionIds",JsonArray(listOf(region.getValue("id")))) })
    }
    @Test fun `separate bodies surface overrides reset hide and project history`(@TempDir dir:Path) {
        val initial=base();val painted=initial.withBodyColor("cube","#1122aa").withBodyColor("second","#33CC66")
        val area=top(painted);val state=painted.withColorArea("cube",area)
        val colors=state.renderColors();val count=state.sceneBodies().first().mesh.triangleCount
        assertEquals(area.triangles.size,colors.take(count).count { it=="#FF8800" });assertTrue(colors.drop(count).all { it=="#33CC66" })
        assertEquals(initial.features,state.features);assertEquals(initial.sceneBodies().map { it.mesh.bounds },state.sceneBodies().map { it.mesh.bounds })
        assertTrue(state.withoutColorArea("cube","lid").renderColors().take(count).all { it=="#1122AA" })
        assertEquals(DEFAULT_BODY_COLOR,state.withoutColorArea("cube","lid").withBodyColor("cube",null).renderColors().first())
        assertTrue(state.setBodyVisibility("cube",false).renderColors().all { it=="#33CC66" })
        val history=DocumentHistory(initial);history.commit(state);assertEquals(initial,history.undo());assertEquals(state,history.redo())
        val file=dir.resolve("colors.kcad");ProjectIO.write(file,ProjectArchive(state));val loaded=ProjectIO.read(file).document
        assertEquals(state.bodyColors,loaded.bodyColors);assertEquals(colors,loaded.renderColors())
        assertFalse(state.withoutFeature("cube").bodyColors.containsKey("cube"))
    }
    @Test fun `surface selections follow translation but expire safely after geometry edits`() {
        val initial=base();val state=initial.withColorArea("cube",top(initial))
        val moved=state.copy(features=listOf(cube.copy(x="123.4 mm",y="-2 mm"),cube.copy(id="second",x="20 mm")))
        assertEquals(state.renderColors(),moved.renderColors())
        val edited=state.copy(features=listOf(cube.copy(shape=obj("""{"type":"box","x":15,"y":10,"z":10}""")),cube.copy(id="second",x="20 mm")))
        assertTrue(edited.renderColors().all { it==DEFAULT_BODY_COLOR })
        assertEquals(listOf(JsonPrimitive("lid")),edited.colorInfo(edited.sceneBodies().first()).getValue("staleAreaIds").jsonArray.toList())
        assertFailsWith<IllegalArgumentException> { edited.withColorArea("cube",top(initial)) }
        assertTrue(moved.colorInfo(moved.sceneBodies().first()).getValue("staleAreaIds").jsonArray.isEmpty())
    }
    @Test fun `spatial zones recompute overlap precedence and invalid input stays atomic`() {
        val initial=base().withBodyColor("cube","#111111")
        val zone=ColorArea("upper","Upper half","#FF0000",low=Vec3(-1.0,-1.0,5.0),high=Vec3(20.0,20.0,20.0))
        val state=initial.withColorArea("cube",zone)
        assertTrue(state.renderColors().contains("#FF0000"))
        val overlay=state.withColorArea("cube",zone.copy(id="overlay",color="#0000FF"))
        assertFalse(overlay.renderColors().contains("#FF0000"));assertTrue(overlay.renderColors().contains("#0000FF"))
        assertEquals(state.renderColors(),overlay.withoutColorArea("cube","overlay").renderColors())
        assertFailsWith<IllegalArgumentException> { initial.withBodyColor("cube","red") }
        assertFailsWith<IllegalArgumentException> { initial.withBodyColor("missing","#FF0000") }
        assertFailsWith<IllegalArgumentException> { zone.copy(low=Vec3(30.0,0.0,0.0)) }
        assertFailsWith<IllegalArgumentException> { zone.copy(color="#FFFFFFFF") }
        assertEquals(emptyList(),initial.bodyColors.getValue("cube").areas)
        val edited=state.copy(features=listOf(cube.copy(shape=obj("""{"type":"box","x":15,"y":10,"z":12}"""))))
        assertTrue(edited.renderColors().contains("#FF0000"));assertTrue(edited.colorInfo(edited.sceneBodies().first()).getValue("staleAreaIds").jsonArray.isEmpty())
    }
    @Test fun `regions paginate and AI retains appearance context`() {
        val state=base();val page=state.colorRegions("cube",0,2)
        assertEquals(6,page.getValue("total").jsonPrimitive.int);assertEquals(2,page.getValue("regions").jsonArray.size)
        assertEquals(2,page.getValue("nextOffset").jsonPrimitive.int)
        assertEquals(page,assistantModelInfo(page))
        assertTrue(assistantTools.map { it.name }.containsAll(listOf("cad_get_color_regions","cad_set_body_color","cad_set_area_color","cad_remove_area_color")))
        val bad=buildJsonObject { put("bodyId","cube");put("areaId","x");put("name","Bad");put("color","#123456");put("sourceRevision","old");put("regionIds",JsonArray(listOf(JsonPrimitive("patch-0")))) }
        assertFailsWith<IllegalArgumentException> { state.colorAreaFrom(bad) }
    }
}
