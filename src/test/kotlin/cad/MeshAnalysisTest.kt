package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

class MeshAnalysisTest {
    private fun cylinder(name:String,radius:Double,height:Double,segments:Int=32):MeshData {
        val points=(0..1).flatMap { layer -> (0 until segments).map { i -> val a=2*Math.PI*i/segments;Vec3(radius*kotlin.math.cos(a),radius*kotlin.math.sin(a),layer*height) } }
        val faces=mutableListOf<Int>()
        for(i in 0 until segments) { val j=(i+1)%segments;val b0=i;val b1=j;val t0=segments+i;val t1=segments+j
            faces.addAll(listOf(b0,b1,t1,b0,t1,t0,0,b1,b0,segments, t0,t1))
        }
        return MeshData(name,points,faces.toIntArray())
    }
    private fun cube(name:String,offset:Double):MeshData {
        val points=listOf(Vec3(offset,0.0,0.0),Vec3(offset+10,0.0,0.0),Vec3(offset+10,10.0,0.0),Vec3(offset,10.0,0.0),
            Vec3(offset,0.0,10.0),Vec3(offset+10,0.0,10.0),Vec3(offset+10,10.0,10.0),Vec3(offset,10.0,10.0))
        return MeshData(name,points,intArrayOf(0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,1,2,6,1,6,5,2,3,7,2,7,6,3,0,4,3,4,7))
    }
    @Test fun `local mesh analysis reports dimensions components topology and paginated planar patches`() {
        val a=cube("plate-a",0.0);val b=cube("plate-b",20.0)
        val mesh=MeshData("fixture",a.vertices+b.vertices,a.indices+b.indices.map { it+a.vertices.size }.toIntArray())
        val job=MeshAnalysisJobs.start(mesh,"mm")
        var page:JsonObject?=null
        repeat(200) {
            page=MeshAnalysisJobs.read(job,0,1)
            if(page?.get("status")?.jsonPrimitive?.content=="complete") return@repeat
            Thread.sleep(5)
        }
        val report=checkNotNull(page)
        assertEquals("complete",report.getValue("status").jsonPrimitive.content)
        assertEquals("mm",report.getValue("unit").jsonPrimitive.content)
        assertEquals(2,report.getValue("componentCount").jsonPrimitive.int)
        assertEquals(1,report.getValue("components").jsonArray.size)
        assertEquals(8,report.getValue("planarPatchCount").jsonPrimitive.int)
        assertEquals(0,report.getValue("quality").jsonObject.getValue("openEdges").jsonPrimitive.int)
        assertEquals(24,report.getValue("quality").jsonObject.getValue("triangles").jsonPrimitive.int)
        assertTrue(report.getValue("capability").jsonPrimitive.content.contains("not certain holes"))
        assertEquals(30.0,report.getValue("bounds").jsonObject.getValue("dimensions").jsonObject.getValue("x").jsonPrimitive.double,1e-8)
    }
    @Test fun `analysis reports cylinder surface fits with radius axis span and residual`() {
        val shift=Vec3(1e7,-2e7,4e7)
        val mesh=cylinder("shaft",5.0,20.0).let { it.copy(vertices=it.vertices.map { p -> p+shift }) }
        val job=MeshAnalysisJobs.start(mesh,"mm")
        var report:JsonObject?=null
        repeat(200) { report=MeshAnalysisJobs.read(job,0,50);if(report?.get("status")?.jsonPrimitive?.content=="complete") return@repeat;Thread.sleep(5) }
        val result=checkNotNull(report)
        val fit=result.getValue("cylindricalPatches").jsonArray.first().jsonObject
        assertEquals(5.0,fit.getValue("radius").jsonPrimitive.double,1e-6)
        assertEquals(20.0,fit.getValue("axialSpan").jsonPrimitive.double,1e-6)
        assertEquals(0.0,fit.getValue("fitError").jsonPrimitive.double,1e-6)
        assertTrue(kotlin.math.abs(fit.getValue("axis").jsonObject.getValue("z").jsonPrimitive.double)>.999)
        assertEquals(shift.x,fit.getValue("center").jsonObject.getValue("x").jsonPrimitive.double,1e-5)
        assertEquals(shift.y,fit.getValue("center").jsonObject.getValue("y").jsonPrimitive.double,1e-5)
        assertEquals(shift.z+10.0,fit.getValue("center").jsonObject.getValue("z").jsonPrimitive.double,1e-5)
    }
    @Test fun `analysis groups repeated parallel cylinder surface candidates without asserting hole intent`() {
        val parts=(0 until 4).map { i -> cylinder("shaft-$i",4.0,18.0).let { mesh -> mesh.copy(vertices=mesh.vertices.map { it+Vec3(i*20.0,0.0,0.0) }) } }
        val vertices=parts.flatMap { it.vertices };val indices=parts.foldIndexed(IntArray(0)) { partIndex,acc,mesh -> acc+mesh.indices.map { it+parts.take(partIndex).sumOf { p -> p.vertices.size } }.toIntArray() }
        val job=MeshAnalysisJobs.start(MeshData("repeated",vertices,indices),"mm")
        var report:JsonObject?=null
        repeat(200) { report=MeshAnalysisJobs.read(job,0,50);if(report?.get("status")?.jsonPrimitive?.content=="complete") return@repeat;Thread.sleep(5) }
        val pattern=checkNotNull(report).getValue("repeatedCylindricalPatterns").jsonArray.single().jsonObject
        assertEquals(4,pattern.getValue("count").jsonPrimitive.int)
        assertEquals(8.0,pattern.getValue("meanDiameter").jsonPrimitive.double,1e-6)
        assertEquals(20.0,pattern.getValue("centerSpacing").jsonObject.getValue("minimum").jsonPrimitive.double,1e-6)
        assertTrue(pattern.getValue("interpretation").jsonPrimitive.content.contains("intent is unknown"))
    }
    @Test fun `mesh revision changes when an unsampled coordinate changes`() {
        val vertices=MutableList(20_001) { Vec3(it.toDouble(),0.0,0.0) }
        fun revision(mesh:MeshData):String {
            val job=MeshAnalysisJobs.start(mesh,"mm");var report:JsonObject?=null
            repeat(300) { report=MeshAnalysisJobs.read(job,0,1);if(report?.get("status")?.jsonPrimitive?.content=="complete") return@repeat;Thread.sleep(5) }
            return checkNotNull(report).getValue("revision").jsonPrimitive.content
        }
        val before=revision(MeshData("revision",vertices,intArrayOf(0,1,2)))
        vertices[1]=Vec3(1.25,0.0,0.0)
        val after=revision(MeshData("revision",vertices,intArrayOf(0,1,2)))
        assertNotEquals(before,after)
    }
    @Test fun `mesh analysis cancellation removes the job without a model result`() {
        val triangles=50_000
        val tri=intArrayOf(0,1,2)
        val mesh=MeshData("cancel",listOf(Vec3(0.0,0.0,0.0),Vec3(10.0,0.0,0.0),Vec3(0.0,10.0,0.0)),IntArray(triangles*3) { tri[it%3] })
        val id=MeshAnalysisJobs.start(mesh,"mm")
        assertTrue(MeshAnalysisJobs.cancel(id).getValue("cancelled").jsonPrimitive.boolean)
        assertFailsWith<IllegalArgumentException> { MeshAnalysisJobs.read(id,0,1) }
    }
    @Test fun `monitor mount reference exposes the central bore candidate and repeated fastener cylinders`() {
        val kernelPath=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernelPath))
        System.setProperty("kotlin.cad.kernel",kernelPath.toString())
        val mesh=NativeKernel().use { TechnicalMeshFixtures.monitorMount(it) }
        val id=MeshAnalysisJobs.start(mesh,"mm");var report:JsonObject?=null
        repeat(500) { report=MeshAnalysisJobs.read(id,0,100);if(report?.get("status")?.jsonPrimitive?.content=="complete") return@repeat;Thread.sleep(5) }
        val result=checkNotNull(report)
        val cylinders=result.getValue("cylindricalPatches").jsonArray.map { it.jsonObject }
        assertTrue(cylinders.any { abs(it.getValue("radius").jsonPrimitive.double-10.0)<.7 },"Expected the Ø20 mm center-bore wall; got ${cylinders.map { it.getValue("radius").jsonPrimitive.double }}")
        val patterns=result.getValue("repeatedCylindricalPatterns").jsonArray.map { it.jsonObject }
        assertTrue(patterns.any { it.getValue("count").jsonPrimitive.int>=4 && abs(it.getValue("meanRadius").jsonPrimitive.double-4.0)<.6 },"Expected four repeated Ø8 mm fastener cylinders; got $patterns")
        val contours=result.getValue("contours").jsonArray.map { it.jsonObject }
        assertTrue(contours.any { it.getValue("kind").jsonPrimitive.content=="circular-contour" && abs(it.getValue("radius").jsonPrimitive.double-10.0)<.7 },"Expected the central Ø20 mm planar contour; got $contours")
        assertTrue(contours.any { it.getValue("kind").jsonPrimitive.content=="closed-contour" },"Expected a noncircular closed contour for the slot")
        val candidates=result.getValue("featureCandidates").jsonArray.map { it.jsonObject }
        assertEquals(result.getValue("featureCandidateCount").jsonPrimitive.int,candidates.size)
        assertTrue(candidates.any { it.getValue("id").jsonPrimitive.content.startsWith(result.getValue("revision").jsonPrimitive.content) && it.getValue("kind").jsonPrimitive.content=="repeated-cylindrical-surfaces" })
    }
    @Test fun `fused squat rack preserves repeated cross-hole cylinder candidates`() {
        val kernelPath=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernelPath));System.setProperty("kotlin.cad.kernel",kernelPath.toString())
        val mesh=NativeKernel().use { TechnicalMeshFixtures.squatRack(it) }
        val id=MeshAnalysisJobs.start(mesh,"mm");var report:JsonObject?=null
        repeat(500) { report=MeshAnalysisJobs.read(id,0,100);if(report?.get("status")?.jsonPrimitive?.content=="complete") return@repeat;Thread.sleep(5) }
        val result=checkNotNull(report);val cylinders=result.getValue("cylindricalPatches").jsonArray.map { it.jsonObject }
        assertTrue(cylinders.any { abs(it.getValue("radius").jsonPrimitive.double-6.0)<.8 },"Expected Ø12 mm rack holes; got ${cylinders.map { it.getValue("radius").jsonPrimitive.double }}")
        val patterns=result.getValue("repeatedCylindricalPatterns").jsonArray.map { it.jsonObject }
        assertTrue(patterns.any { it.getValue("count").jsonPrimitive.int>=8 && abs(it.getValue("meanRadius").jsonPrimitive.double-6.0)<.8 },"Expected repeated rack cross-hole candidates; got $patterns")
    }
}
