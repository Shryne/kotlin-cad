package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class SketchRegionTest {
    private fun rect(id:String,x:String,y:String,w:String,h:String)=SketchElement(id=id,u=x,v=y,width=w,height=h)
    private fun circle(id:String,x:String,y:String,r:String)=SketchElement(id=id,shape=SketchShape.CIRCLE,u=x,v=y,width=r)
    private fun sketch(vararg elements:SketchElement)=Sketch(name="Regionen",elements=elements.toList())

    @Test fun `inner circles become holes and remain independently selectable`() {
        val regions=sketch(rect("plate","0 mm","0 mm","60 mm","40 mm"),circle("left","15 mm","20 mm","4 mm"),circle("right","45 mm","20 mm","4 mm")).regions()
        assertEquals(listOf("left","right"),regions.single { it.id=="plate" }.holeElementIds)
        assertTrue(regions.any { it.id=="left" && it.holeElementIds.isEmpty() })
        assertTrue(regions.any { it.id=="right" && it.holeElementIds.isEmpty() })
    }

    @Test fun `concave profile remains a valid region`() {
        val concave=SketchElement(id="L",vertices=listOf(
            SketchCoordinate("0 mm","0 mm"),SketchCoordinate("30 mm","0 mm"),SketchCoordinate("30 mm","10 mm"),
            SketchCoordinate("10 mm","10 mm"),SketchCoordinate("10 mm","30 mm"),SketchCoordinate("0 mm","30 mm")),kind=SketchElementKind.POLYLINE,isClosed=true)
        assertEquals("L",sketch(concave).regions().single().id)
    }

    @Test fun `overlapping contours are rejected with a useful diagnostic`() {
        val failure=assertFails { sketch(rect("a","0 mm","0 mm","20 mm","20 mm"),rect("b","10 mm","10 mm","20 mm","20 mm")).regions() }
        assertTrue(failure.message.orEmpty().contains("überlappen"))
    }

    @Test fun `self intersecting closed polyline is diagnosed`() {
        val bowTie=SketchElement(id="bow",kind=SketchElementKind.POLYLINE,isClosed=true,vertices=listOf(
            SketchCoordinate("0 mm","0 mm"),SketchCoordinate("20 mm","20 mm"),SketchCoordinate("0 mm","20 mm"),SketchCoordinate("20 mm","0 mm")))
        val failure=assertFails { sketch(bowTie).regions() }
        assertTrue(failure.message.orEmpty().contains("schneidet sich selbst"))
    }

    @Test fun `open sketch geometry cannot be referenced as an extrusion region`() {
        val line=SketchElement(id="open",kind=SketchElementKind.POLYLINE,vertices=listOf(SketchCoordinate("0 mm","0 mm"),SketchCoordinate("10 mm","10 mm")))
        val s=sketch(line)
        val state=DocumentState(sketches=listOf(s),features=listOf(ExtrusionFeature("bad",RegionReference(s.id,"open"),"5 mm")))
        val failure=assertFails { state.featureOrder() }
        assertTrue(failure.message.orEmpty().contains("ist offen"))
    }

    @Test fun `hole region produces an actually perforated extrusion`(@TempDir folder: Path) {
        assumeTrue(Files.isExecutable(Path.of("build/native-runtime/cad-kernel")))
        System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString())
        val s=sketch(rect("plate","0 mm","0 mm","60 mm","40 mm"),circle("left","15 mm","20 mm","4 mm"),circle("right","45 mm","20 mm","4 mm"))
        val region=s.regions().single { it.id=="plate" }
        val state=DocumentState(sketches=listOf(s),features=listOf(ExtrusionFeature("plate-body",RegionReference(s.id,region.id),"5 mm")))
        val project=folder.resolve("region.kcad")
        ProjectIO.write(project,ProjectArchive(state))
        val saved=ProjectIO.read(project).document
        val feature=saved.features.single() as ExtrusionFeature
        assertEquals("plate",feature.region.regionId)
        val body=saved.sceneBodies().single()
        val signedVolume=body.mesh.indices.asSequence().chunked(3).sumOf { tri ->
            val a=body.mesh.vertices[tri[0]];val b=body.mesh.vertices[tri[1]];val c=body.mesh.vertices[tri[2]]
            (a.x*(b.y*c.z-b.z*c.y)+a.y*(b.z*c.x-b.x*c.z)+a.z*(b.x*c.y-b.y*c.x))/6.0
        }
        val polygonCircleArea=48*16*sin(2*PI/96)
        val expected=5*(2400-2*polygonCircleArea)
        assertEquals(expected,signedVolume,0.2)
    }

    @Test fun `independent sketch wires persist with stable region ids`() {
        val source=sketch(rect("plate","0 mm","0 mm","60 mm","40 mm"),circle("hole","20 mm","20 mm","5 mm"))
        val restored=Sketch.read(source.json())
        assertEquals(source.regions(),restored.regions())
    }
}
