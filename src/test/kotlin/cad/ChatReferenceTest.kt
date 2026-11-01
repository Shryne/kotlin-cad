package cad

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlinx.serialization.json.*

class ChatReferenceTest {
    private val mesh=MeshData("Import",listOf(Vec3(0.0,0.0,0.0),Vec3(40.0,0.0,0.0),Vec3(0.0,40.0,0.0)),intArrayOf(0,1,2))
    @Test fun `mark stores exact body point normal radius and revision without editing document`() {
        val document=DocumentState(mesh=mesh)
        val mark=document.chatReference(0,Vec3(5.0,6.0,0.0),10.0,"Bereich 1")
        assertEquals("import",mark.bodyId);assertEquals(Vec3(0.0,0.0,1.0),mark.normal);assertTrue(mark.imported)
        assertEquals(MeshAnalysisJobs.revisionOf(mesh),mark.revision)
        assertEquals(10.0,mark.json().getValue("radiusMm").jsonPrimitive.double)
        val context=assistantModelInfo(buildJsonObject {put("selection",buildJsonObject {put("markedAreas",JsonArray(listOf(mark.json())))})})
        assertEquals(mark.json(),context.getValue("selection").jsonObject.getValue("markedAreas").jsonArray.single())
        assertTrue(document.features.isEmpty());assertTrue(document.parameters.isEmpty())
        assertEquals(30.0,ParameterEvaluator.expression("3 cm",emptyMap()).value)
    }
    @Test fun `bad hits hidden bodies and invalid radii are rejected`() {
        val document=DocumentState(mesh=mesh)
        assertFails { document.chatReference(1,Vec3(0.0,0.0,0.0),10.0,"x") }
        assertFails { document.chatReference(0,Vec3(5.0,5.0,3.0),10.0,"x") }
        assertFails { document.chatReference(0,Vec3(5.0,5.0,0.0),Double.NaN,"x") }
        assertFails { document.copy(display=document.display.copy(hiddenBodyIds=setOf("import"))).chatReference(0,Vec3(5.0,5.0,0.0),10.0,"x") }
    }
}
