package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*
import java.util.Base64
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class MeshSurfaceComparisonTest {
    private fun plane(z:Double,flipped:Boolean=false):MeshData {
        val p=listOf(Vec3(0.0,0.0,z),Vec3(10.0,0.0,z),Vec3(10.0,10.0,z),Vec3(0.0,10.0,z))
        return MeshData("plane",p,if(flipped)intArrayOf(0,1,3,1,2,3)else intArrayOf(0,1,2,0,2,3))
    }
    @Test fun `comparison uses triangle interiors and bounds known translation despite triangulation changes`() {
        val a=plane(0.0);val b=plane(2.0,true);val result=MeshSurfaceComparison.compare(a,b,.02,10000)
        assertEquals(2.0,result.forward.lower,1e-8);assertTrue(result.forward.upper>=2.0);assertTrue(result.forward.upper-2<.02)
        assertEquals(2.0,result.reverse.rms,1e-8)
        val nearest=SurfaceIndex(a).nearest(Vec3(5.0,5.0,2.0));assertEquals(2.0,nearest.distance,1e-8)
        val report=JsonObject(result.json()+mapOf("measurementKind" to JsonPrimitive("surface-comparison")))
        val image=ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(DeviationMap.render(report,3.0).getValue("data").jsonPrimitive.content)))
        assertEquals(1200,image.width);assertEquals(760,image.height)
    }
    @Test fun `interior bump with same boundary vertices is detected bidirectionally and budgets are honest`() {
        val original=plane(0.0)
        val altered=MeshData("bump",original.vertices+Vec3(5.0,5.0,3.0),intArrayOf(0,1,4,1,2,4,2,3,4,3,0,4))
        val result=MeshSurfaceComparison.compare(original,altered,.001,100)
        assertEquals(3.0,result.reverse.lower,1e-8);assertTrue(result.reverse.upper>=3.0)
        assertTrue(result.forward.lower>0)
        val json=result.json();assertEquals(json.getValue("boundGapMm").jsonPrimitive.double<=.001,json.getValue("converged").jsonPrimitive.boolean)
        assertTrue(result.reverse.map.any { it.maximumSample>=3.0 })
    }
    @Test fun `closest point handles degenerate triangles and BVH agrees with exhaustive distances`() {
        val degenerate=SurfaceTriangle(Vec3(0.0,0.0,0.0),Vec3(10.0,0.0,0.0),Vec3(5.0,0.0,0.0),0)
        assertEquals(Vec3(5.0,0.0,0.0),closestOnTriangle(Vec3(5.0,3.0,0.0),degenerate))
        val m=plane(0.0);val tree=SurfaceIndex(m)
        for(i in -10..20) {val p=Vec3(i.toDouble(),i*.7,3.0);val expected=tree.triangles.minOf { (p-closestOnTriangle(p,it)).norm() };assertEquals(expected,tree.nearest(p).distance,1e-10)}
    }
}
