package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*
import kotlinx.serialization.json.*

class LoftTest {
    init { System.setProperty("kotlin.cad.kernel",Path.of("build/native-runtime/cad-kernel").toAbsolutePath().toString()) }
    private fun feature(sections:List<LoftSection>,ruled:Boolean=false)=LoftFeature("loft-test",sections=sections,ruled=ruled)
    private fun mesh(f:LoftFeature)=f.evaluateMesh(emptyMap())
    private fun closed(m:MeshData) {
        // Weld face-local tessellation vertices; every solid edge must have two opposite uses.
        val ids=mutableMapOf<List<Long>,Int>()
        val vertex=m.vertices.map { p->ids.getOrPut(listOf(p.x,p.y,p.z).map { (it*1e5).roundToLong() }) { ids.size } }
        val edges=mutableMapOf<Pair<Int,Int>,MutableList<Int>>()
        for(i in m.indices.indices step 3) {
            val t=(0..2).map { vertex[m.indices[i+it]] }
            assertEquals(3,t.distinct().size)
            for(j in 0..2) { val a=t[j];val b=t[(j+1)%3];edges.getOrPut(minOf(a,b) to maxOf(a,b)) { mutableListOf() }.add(if(a<b) 1 else -1) }
        }
        assertTrue(edges.values.all { it.size==2&&it.sum()==0 },"Mesh must be closed and consistently oriented")
    }
    @Test fun `straight tapered curved and twisted lofts are closed and aligned`() {
        val base=LoftSection()
        val straight=feature(listOf(base,base.copy(z="40 mm")))
        val m=mesh(straight);closed(m)
        assertEquals(20.0,m.bounds.size.x,.01);assertEquals(12.0,m.bounds.size.y,.01);assertEquals(40.0,m.bounds.size.z,.01)
        val tapered=feature(listOf(base,base.copy(z="20 mm",radiusX="8 mm",radiusY="5 mm"),base.copy(z="40 mm",radiusX="4 mm",radiusY="2 mm")))
        closed(mesh(tapered));closed(mesh(tapered.copy(ruled=true)))
        val curved=feature(listOf(base,base.copy(x="5 mm",z="20 mm",rotationY="15 deg"),base.copy(x="15 mm",z="40 mm",rotationY="25 deg")))
        closed(mesh(curved))
        val twisted=feature(listOf(base,base.copy(z="20 mm",rotationZ="30 deg"),base.copy(z="40 mm",rotationZ="60 deg")))
        closed(mesh(twisted))
        // Crossing the major/minor radius boundary must not rotate the profile seam.
        val crossed=mesh(feature(listOf(base.copy(radiusX="5 mm",radiusY="10 mm"),base.copy(z="40 mm",radiusX="10 mm",radiusY="5 mm")),true));closed(crossed)
        assertTrue(crossed.vertices.any { abs(it.z)<1e-6&&abs(it.x-5)<.001&&abs(it.y)<.001 })
    }
    @Test fun `loft edit parameters history persistence and exports`(@TempDir dir:Path) {
        val f=feature(listOf(LoftSection(radiusX="r"),LoftSection(z="height",radiusX="r / 2",rotationZ="twist")))
        val original=DocumentState(parameters=listOf(Parameter("r","12 mm"),Parameter("height","40 mm"),Parameter("twist","20 deg")))
        val created=original.withLoft(f,false);val history=DocumentHistory(original);history.commit(created)
        val updated=created.withLoft(f.copy(ruled=true,sections=f.sections+LoftSection(z="height + 20 mm",radiusX="3 mm")),true);history.commit(updated)
        assertEquals(created,history.undo());assertEquals(original,history.undo());history.redo();assertEquals(updated,history.redo())
        val file=dir.resolve("loft.kcad");ProjectIO.write(file,ProjectArchive(updated));val loaded=ProjectIO.read(file).document
        assertEquals(updated.features,loaded.features);assertEquals(updated.parameters,loaded.parameters)
        val changed=loaded.copy(parameters=loaded.parameters.map { if(it.name=="height") it.copy(expression="50 mm") else it })
        assertEquals(70.0,changed.sceneBodies().single().mesh.bounds.high.z,.01)
        for(ext in listOf("stl","obj")) { val out=dir.resolve("loft.$ext");MeshExporter.write(out,changed);closed(MeshImporter.read(out)) }
        val result=f.result(created.sceneBodies().single().mesh);assertEquals("loft-test",result["bodyId"]!!.jsonPrimitive.content)
        assertTrue(assistantModelInfo(result).containsKey("validation"))
        assertTrue(assistantTools.map { it.name }.containsAll(listOf("cad_create_loft","cad_preview_loft","cad_update_loft")))
    }
    @Test fun `invalid sections and solids fail before any history change`() {
        val base=LoftSection();val valid=feature(listOf(base,base.copy(z="40 mm")));val before=DocumentState().withLoft(valid,false);val history=DocumentHistory(before)
        for(bad in listOf(valid.copy(sections=listOf(base)),valid.copy(sections=listOf(base,base)),valid.copy(sections=listOf(base,base.copy(z="40 mm",radiusY="0 mm"))),valid.copy(sections=listOf(base,base.copy(z="40 mm",rotationY="5 mm"))),valid.copy(sections=listOf(base,base.copy(z="40 mm"),base.copy(z="-20 mm"))))) {
            assertFails { history.commit(before.withLoft(bad,true)) };assertEquals(before,history.current);assertFalse(history.canUndo)
        }
    }
}
