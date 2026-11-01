package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.jupiter.api.io.TempDir

class MeshReconstructionTest {
    @TempDir lateinit var temp:Path
    @Test fun `monitor mount proposes and evaluates an isolated parametric plate copy`() {
        val kernelPath=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernelPath))
        System.setProperty("kotlin.cad.kernel",kernelPath.toString())
        val mesh=NativeKernel().use { TechnicalMeshFixtures.monitorMount(it) }
        val document=DocumentState(mesh=mesh)
        val analysisId=MeshAnalysisJobs.start(mesh,"mm")
        val deadline=System.nanoTime()+30_000_000_000L
        while(System.nanoTime()<deadline) {
            if(runCatching { MeshAnalysisJobs.completedResult(analysisId) }.isSuccess) break
            Thread.sleep(10)
        }
        assertEquals("complete",MeshAnalysisJobs.read(analysisId,0,100).getValue("status").jsonPrimitive.content)
        val proposalJson=MeshReconstructionJobs.suggest(document,analysisId,null,22.0)
        assertEquals("ready",proposalJson.getValue("status").jsonPrimitive.content)
        val proposalId=proposalJson.getValue("proposalId").jsonPrimitive.content
        val proposal=MeshReconstructionJobs.get(proposalId)
        assertEquals(20.0,proposal.originalOpeningDiameter,.7)
        assertEquals(22.0,proposal.proposedOpeningDiameter,.001)
        assertEquals(7,proposal.sketch.elements.size,"The copy should keep its outer rectangle, five circular openings and the oblong slot")
        assertEquals(1,proposal.sketch.elements.count { it.kind==SketchElementKind.POLYLINE && it.isClosed })
        val (previewDocument,previewMesh)=MeshReconstructionJobs.preview(document,proposalId)
        assertSame(mesh,document.mesh)
        assertEquals(0,document.features.size)
        assertEquals(1,previewDocument.features.size)
        assertEquals(1,previewDocument.sketches.size)
        assertEquals(3,previewDocument.parameters.size)
        assertEquals(mesh.bounds.size.x,previewMesh.bounds.size.x,.15)
        assertEquals(mesh.bounds.size.y,previewMesh.bounds.size.y,.15)
        assertEquals(mesh.bounds.size.z,previewMesh.bounds.size.z,.15)
        assertTrue(proposal.copyVolume<proposal.sourceVolume,"Enlarging the through-opening should reduce volume")
        assertTrue(abs(proposal.copyVolume-proposal.sourceVolume)>1)
        assertTrue(proposal.deviation.sampleCount>0)
        assertTrue(proposal.deviation.sourceToCopyRms>0.0,"The enlarged opening must produce a measurable sampled surface deviation")
        assertTrue(proposal.deviation.copyToSourceMax>=proposal.deviation.copyToSourceRms)
        assertEquals(1,previewDocument.sceneBodies().count { it.id==proposal.feature.id })
        val radiusParameter=proposal.parameters.first { it.name.startsWith("openingRadius_") }
        val changed=previewDocument.copy(parameters=previewDocument.parameters.map { if(it.name==radiusParameter.name) it.copy(expression="12 mm") else it })
        val changedBody=changed.sceneBodies().first { it.id==proposal.feature.id }.mesh
        assertEquals(previewMesh.bounds.size.x,changedBody.bounds.size.x,.15)
        assertEquals(previewMesh.bounds.size.y,changedBody.bounds.size.y,.15)
        assertTrue(volume(changedBody)<volume(previewMesh),"Increasing the parameterized radius should reduce the reconstructed volume")
        val history=DocumentHistory(document)
        assertTrue(history.commit(previewDocument));assertEquals(1,history.current.features.size)
        assertEquals(document,history.undo());assertEquals(previewDocument,history.redo())
        val project=temp.resolve("monitor-copy.kcad");ProjectIO.write(project,ProjectArchive(previewDocument))
        val restored=ProjectIO.read(project).document
        assertEquals(previewDocument.parameters,restored.parameters)
        assertEquals(previewDocument.features,restored.features)
        assertEquals(previewMesh.bounds.size,restored.sceneBodies().first { it.id==proposal.feature.id }.mesh.bounds.size)
        val export=MeshExporter.write(temp.resolve("monitor-copy.stl"),restored)
        assertEquals(2,export.parts);assertTrue(Files.size(export.path)>84)
        assertThrows(IllegalArgumentException::class.java) { proposal.apply(document.copy(mesh=mesh.translated(Vec3(1.0,0.0,0.0)))) }
    }
    @Test fun `fused squat rack proposes five editable parametric hollow profile and plate bodies`() {
        val kernelPath=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernelPath));System.setProperty("kotlin.cad.kernel",kernelPath.toString())
        val mesh=NativeKernel().use { TechnicalMeshFixtures.squatRack(it) }
        val document=DocumentState(mesh=mesh)
        val analysisId=MeshAnalysisJobs.start(mesh,"mm")
        val deadline=System.nanoTime()+60_000_000_000L
        while(System.nanoTime()<deadline) { if(runCatching { MeshAnalysisJobs.completedResult(analysisId) }.isSuccess) break;Thread.sleep(10) }
        assertEquals("complete",MeshAnalysisJobs.read(analysisId,0,100).getValue("status").jsonPrimitive.content)
        val proposalJson=MeshReconstructionJobs.suggest(document,analysisId,null,null)
        assertEquals("squat-rack",proposalJson.getValue("modelKind").jsonPrimitive.content)
        assertEquals(5,proposalJson.getValue("assembly").jsonObject.getValue("partCount").jsonPrimitive.int)
        val proposal=MeshReconstructionJobs.get(proposalJson.getValue("proposalId").jsonPrimitive.content)
        assertTrue(proposal.features.all { it is NativeShapeFeature })
        assertTrue(proposal.sketches.isEmpty())
        val parameterValues=ParameterEvaluator.evaluate(proposal.parameters)
        fun measured(prefix:String)=parameterValues.entries.single { it.key.startsWith(prefix) }.value.value
        assertTrue(proposal.parameters.size>=20)
        assertEquals(60.0,measured("rackPostWidth_"),.2)
        assertEquals(60.0,measured("rackPostDepth_"),.2)
        assertEquals(3.0,measured("rackWallThickness_"),.2)
        assertEquals(940.0,measured("rackPostSpacing_"),.2)
        assertEquals(150.0,measured("rackBaseLength_"),.2)
        assertEquals(100.0,measured("rackBaseDepth_"),.2)
        assertEquals(8.0,measured("rackBaseThickness_"),.2)
        assertEquals(6.0,measured("rackHoleRadius_"),.2)
        assertEquals(400.0,measured("rackHoleStart_"),.2)
        assertEquals(400.0,measured("rackHolePitch_"),.2)
        assertEquals(listOf(40.0,110.0),listOf(measured("rackFootHoleX1_"),measured("rackFootHoleX2_")).sorted().map { kotlin.math.round(it*10)/10 })
        assertEquals(listOf(25.0,75.0),listOf(measured("rackFootHoleY1_"),measured("rackFootHoleY2_")).sorted().map { kotlin.math.round(it*10)/10 })
        val (preview,combined)=MeshReconstructionJobs.preview(document,proposal.id)
        assertSame(mesh,document.mesh)
        assertTrue(document.features.isEmpty());assertSame(mesh,document.mesh)
        assertEquals(5,preview.features.size)
        assertEquals(5,preview.sceneBodies().count { it.id.startsWith("rack-") })
        val originalPost=preview.sceneBodies().first { it.id.startsWith("rack-left-post-") }.mesh
        val changedPitch=measured("rackHolePitch_")-50
        val pitchParameter=proposal.parameters.single { it.name.startsWith("rackHolePitch_") }
        val resized=preview.copy(parameters=preview.parameters.map { if(it.name==pitchParameter.name) it.copy(expression="$changedPitch mm") else it })
        val resizedPost=resized.sceneBodies().first { it.id.startsWith("rack-left-post-") }.mesh
        assertEquals(originalPost.bounds,resizedPost.bounds)
        assertNotEquals(originalPost.vertices,resizedPost.vertices,"The repeated cross-hole features must respond to their pitch parameter")
        assertEquals(mesh.bounds.size.x,combined.bounds.size.x,2.0)
        assertEquals(mesh.bounds.size.y,combined.bounds.size.y,2.0)
        assertEquals(mesh.bounds.size.z,combined.bounds.size.z,2.0)
        assertTrue(proposal.copyVolume>0)
        assertTrue(kotlin.math.abs(proposal.copyVolume-proposal.sourceVolume)/proposal.sourceVolume<.03,"Reconstructed rack volume must match the fused reference within 3%")
        assertTrue(proposal.deviation.sourceToCopyRms.isFinite())
        val project=temp.resolve("rack-copy.kcad");ProjectIO.write(project,ProjectArchive(preview))
        val loaded=ProjectIO.read(project).document
        assertEquals(preview.features,loaded.features)
        assertEquals(5,loaded.sceneBodies().count { it.id.startsWith("rack-") })
        val export=MeshExporter.write(temp.resolve("rack-copy.stl"),loaded)
        assertEquals(6,export.parts)
    }
    private fun volume(mesh:MeshData):Double { var value=0.0;for(i in mesh.indices.indices step 3) { val a=mesh.vertices[mesh.indices[i]];val b=mesh.vertices[mesh.indices[i+1]];val c=mesh.vertices[mesh.indices[i+2]];value+=a.dot(b.cross(c))/6 };return abs(value) }
}
