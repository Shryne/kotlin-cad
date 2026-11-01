package cad

import kotlin.test.*
import org.junit.jupiter.api.Test

class GridGeometryTest {
    @Test fun `batch preserves box bounds and independent vertex indices`() {
        val mesh=gridGeometry(listOf(GridBox(0.0,70.0,20.0,600.0,.18,.5),GridBox(40.0,70.0,0.0,.5,.18,600.0)))
        assertEquals(48,mesh.points.size);assertEquals(144,mesh.faces.size)
        assertEquals(-300f,mesh.points.filterIndexed { i,_ -> i%3==0 }.min())
        assertEquals(300f,mesh.points.filterIndexed { i,_ -> i%3==2 }.max())
        assertTrue(mesh.faces.take(72).filterIndexed { i,_ -> i%2==0 }.all { it in 0..7 })
        assertTrue(mesh.faces.drop(72).filterIndexed { i,_ -> i%2==0 }.all { it in 8..15 })
        assertTrue(mesh.faces.filterIndexed { i,_ -> i%2==1 }.all { it==0 })
        // Each triangle faces outwards, preserving visibility from both sides of the floor.
        for (offset in mesh.faces.indices step 6) {
            val indices=(0..2).map { mesh.faces[offset+it*2]*3 }
            val a=indices[0];val b=indices[1];val c=indices[2];val p=mesh.points
            val ux=p[b]-p[a];val uy=p[b+1]-p[a+1];val uz=p[b+2]-p[a+2]
            val vx=p[c]-p[a];val vy=p[c+1]-p[a+1];val vz=p[c+2]-p[a+2]
            val box=if(a<24) GridBox(0.0,70.0,20.0,600.0,.18,.5) else GridBox(40.0,70.0,0.0,.5,.18,600.0)
            assertTrue((uy*vz-uz*vy)*(p[a]-box.x)+(uz*vx-ux*vz)*(p[a+1]-box.y)+(ux*vy-uy*vx)*(p[a+2]-box.z)>0)
        }
    }
    @Test fun `empty grid produces no geometry`() { val mesh=gridGeometry(emptyList());assertTrue(mesh.points.isEmpty());assertTrue(mesh.faces.isEmpty()) }
}
