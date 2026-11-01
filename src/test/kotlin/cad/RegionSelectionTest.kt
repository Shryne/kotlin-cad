package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RegionSelectionTest {
    init { System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString()) }
    private val origin=Vec3(5.0,5.0,100.0)
    private val down=Vec3(0.0,0.0,-1.0)
    private fun square(id:String="square")=Sketch(id=id,width="20 mm",height="20 mm").syncLegacyElement()
    @Test fun `picking is stable across sketches and rejects invisible open and occluded regions`() {
        val a=square("a");val b=square("b")
        val open=Sketch(id="open",elements=listOf(SketchElement(id="line",kind=SketchElementKind.LINE,vertices=listOf(SketchCoordinate("0 mm","0 mm"),SketchCoordinate("10 mm","10 mm")))))
        val state=DocumentState(sketches=listOf(b,a,square("hidden").copy(visible=false),open))
        assertEquals(2,state.pickableRegions().size)
        assertEquals(RegionReference("a","profile"),pickRegion(state.pickableRegions(),origin,down,null))
        val blocker=MeshData("blocker",listOf(Vec3(0.0,0.0,10.0),Vec3(20.0,0.0,10.0),Vec3(0.0,20.0,10.0)),intArrayOf(0,1,2))
        assertNull(pickRegion(state.pickableRegions(),origin,down,blocker))
        val coplanar=blocker.copy(vertices=blocker.vertices.map { it.copy(z=0.0) })
        assertEquals(RegionReference("a","profile"),pickRegion(state.pickableRegions(),origin,down,coplanar))
        assertNull(pickRegion(state.pickableRegions(),Vec3(50.0,50.0,100.0),down,null))
    }
    @Test fun `hole belongs to its own selectable region not its surrounding plate`() {
        val s=Sketch(id="plate",elements=listOf(SketchElement(id="outer",width="20 mm",height="20 mm"),SketchElement(id="hole",shape=SketchShape.CIRCLE,u="10 mm",v="10 mm",width="3 mm")))
        val regions=DocumentState(sketches=listOf(s)).pickableRegions()
        assertNull(regions.single { it.reference.regionId=="outer" }.distance(Vec3(10.0,10.0,100.0),down))
        assertEquals(RegionReference("plate","hole"),pickRegion(regions,Vec3(10.0,10.0,100.0),down,null))
    }
    @Test fun `multi sketch extrusion preserves existing features and commits as one undo step`(@TempDir folder:Path) {
        val a=Sketch(id="a",shape=SketchShape.CIRCLE,width="10 mm").syncLegacyElement();val b=square("b").copy(plane=SketchPlane.XZ)
        val existing=ExtrusionFeature("existing",RegionReference("a","profile"),"2 mm")
        val before=DocumentState(sketches=listOf(a,b),features=listOf(existing),parameters=listOf(Parameter("tiefe","-10 mm"))).canonicalFeatures()
        val draft=RegionExtrusionDraft(listOf(RegionReference("a","profile"),RegionReference("b","profile")),listOf("new-a","new-b"),"tiefe")
        val after=draft.applyTo(before)
        assertEquals(before.sketches,after.sketches)
        assertEquals(existing,after.features.first())
        assertEquals(-10.0,after.sceneBodies().single { it.id=="new-a" }.mesh.bounds.low.z,1e-6)
        assertEquals(-10.0,after.sceneBodies().single { it.id=="new-b" }.mesh.bounds.low.y,1e-6)
        val history=DocumentHistory();history.reset(before);history.commit(after)
        assertEquals(before,history.undo());assertEquals(after,history.redo())
        val file=folder.resolve("regions.kcad");ProjectIO.write(file,ProjectArchive(after))
        val restored=ProjectIO.read(file).document
        assertEquals(after.features,restored.features)
        assertEquals(after.sketches,restored.sketches)
        assertEquals(after.parameters,restored.parameters)
        val changed=restored.copy(parameters=listOf(Parameter("tiefe","15 mm")))
        assertEquals(15.0,changed.sceneBodies().single { it.id=="new-a" }.mesh.bounds.size.z,1e-6)
        assertEquals(15.0,changed.sceneBodies().single { it.id=="new-b" }.mesh.bounds.size.y,1e-6)
        assertEquals(2.0,changed.sceneBodies().single { it.id=="existing" }.mesh.bounds.size.z,1e-6)
        // Retain the actual multi-source commit for the installed app's MCP acceptance check.
        val acceptance=Path.of("build/qa/region-persistence/regions.kcad")
        java.nio.file.Files.createDirectories(acceptance.parent)
        ProjectIO.write(acceptance,ProjectArchive(restored),overwrite=true)
        assertFails { draft.applyTo(before.copy(sketches=listOf(a))) }
        assertFails { draft.copy(depth="0 mm").applyTo(before) }
        assertEquals(1,before.features.size)
    }    @Test fun `preview retains holes without modifying the history`() {
        val source=Sketch(id="plate",elements=listOf(SketchElement(id="outer",width="20 mm",height="20 mm"),SketchElement(id="hole",shape=SketchShape.CIRCLE,u="10 mm",v="10 mm",width="3 mm")))
        val second=square("other").copy(plane=SketchPlane.XZ)
        val before=DocumentState(sketches=listOf(source,second))
        val history=DocumentHistory(before)
        val draft=RegionExtrusionDraft(listOf(RegionReference("plate","outer"),RegionReference("other","profile")),listOf("plate-body","other-body"),"5 mm")
        val preview=draft.applyTo(before)
        assertEquals(before,history.current);assertFalse(history.canUndo)
        val plate=preview.sceneBodies().single { it.id=="plate-body" }.mesh
        // A ray through the central hole reaches the underlying plane, not a cap.
        val probe=DocumentState(sketches=listOf(square("probe"))).pickableRegions()
        assertNotNull(pickRegion(probe,Vec3(10.0,10.0,100.0),down,plate))
        assertNull(pickRegion(probe,Vec3(2.0,2.0,100.0),down,plate))
        assertEquals(before,history.current) // Dropping the preview is cancellation.
    }

}
