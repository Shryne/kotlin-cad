package cad

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HousePlacementTest {
    private fun assertBounds(mesh: MeshData,low: Vec3,high: Vec3) {
        assertEquals(low,mesh.bounds.low,"${mesh.name} minimum")
        assertEquals(high,mesh.bounds.high,"${mesh.name} maximum")
    }

    @Test fun `house coordinates place roof over body and details outside the front wall`() {
        val sketches=listOf(
            Sketch(id="body",name="Haus – Baukörper",plane=SketchPlane.XY,shape=SketchShape.RECTANGLE,u="0 mm",v="-80 mm",width="100 mm",height="80 mm",depth="60 mm"),
            Sketch(id="roof",name="Haus – Satteldach",plane=SketchPlane.XZ,shape=SketchShape.TRIANGLE,u="-5 mm",v="60 mm",width="110 mm",height="35 mm",depth="-80 mm"),
            Sketch(id="door",name="Haus – Tür",plane=SketchPlane.XZ,shape=SketchShape.RECTANGLE,u="42 mm",v="0 mm",width="16 mm",height="32 mm",depth="2 mm"),
            Sketch(id="left-window",name="Haus – Fenster links",plane=SketchPlane.XZ,shape=SketchShape.RECTANGLE,u="12 mm",v="28 mm",width="18 mm",height="18 mm",depth="2 mm"),
            Sketch(id="right-window",name="Haus – Fenster rechts",plane=SketchPlane.XZ,shape=SketchShape.RECTANGLE,u="70 mm",v="28 mm",width="18 mm",height="18 mm",depth="2 mm"),
            Sketch(id="gable-window",name="Haus – Giebelfenster",plane=SketchPlane.XZ,shape=SketchShape.CIRCLE,u="50 mm",v="73 mm",width="5 mm",depth="2 mm")
        )
        val bodies=DocumentState(sketches=sketches).bodyMeshes().associateBy { it.name }
        assertEquals(6,bodies.size)
        assertBounds(bodies.getValue("Haus – Baukörper"),Vec3(0.0,-80.0,0.0),Vec3(100.0,0.0,60.0))
        assertBounds(bodies.getValue("Haus – Satteldach"),Vec3(-5.0,-80.0,60.0),Vec3(105.0,0.0,95.0))
        assertBounds(bodies.getValue("Haus – Tür"),Vec3(42.0,0.0,0.0),Vec3(58.0,2.0,32.0))
        assertBounds(bodies.getValue("Haus – Fenster links"),Vec3(12.0,0.0,28.0),Vec3(30.0,2.0,46.0))
        assertBounds(bodies.getValue("Haus – Fenster rechts"),Vec3(70.0,0.0,28.0),Vec3(88.0,2.0,46.0))
        assertBounds(bodies.getValue("Haus – Giebelfenster"),Vec3(45.0,0.0,68.0),Vec3(55.0,2.0,78.0))
    }
}
