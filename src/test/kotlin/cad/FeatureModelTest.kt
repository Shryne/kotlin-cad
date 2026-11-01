package cad

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.*
import java.util.zip.ZipFile
import kotlinx.serialization.json.*
import kotlin.test.*

class FeatureModelTest {
    @TempDir lateinit var folder: Path
    @Test fun `extrusions are independent of their sketch and can share its region`() {
        val s=Sketch(id="source",width="20 mm",height="10 mm").syncLegacyElement()
        val a=ExtrusionFeature("a",RegionReference(s.id,"profile"),"5 mm")
        val b=ExtrusionFeature("b",RegionReference(s.id,"profile"),"12 mm")
        val document=DocumentState(sketches=listOf(s),features=listOf(a,b))
        assertNull(document.sketches.single().depth)
        assertEquals(listOf(5.0,12.0),document.bodyMeshes().map { it.bounds.size.z })
        val updated=document.copy(features=listOf(a,b.copy(depth="18 mm")))
        assertEquals(listOf(5.0,18.0),updated.bodyMeshes().map { it.bounds.size.z })
        val edited=updated.replaceSketch(s.copy(width="30 mm"),false)
        assertEquals(updated.features,edited.features)
        assertEquals(listOf(30.0,30.0),edited.bodyMeshes().map { it.bounds.size.x })
        assertEquals(listOf("a","b"),edited.sceneBodies().map { it.id })
    }
    @Test fun `generated bodies accept formula positions without moving their sketch or sibling bodies`() {
        val sketch=Sketch(id="plate",width="20 mm",height="10 mm").syncLegacyElement()
        val first=ExtrusionFeature("first",RegionReference("plate","profile"),"5 mm",x="width / 2",y="-3 mm",z="2 mm")
        val sibling=ExtrusionFeature("sibling",RegionReference("plate","profile"),"2 mm")
        val document=DocumentState(parameters=listOf(Parameter("width","30 mm")),sketches=listOf(sketch),features=listOf(first,sibling))
        val bodies=document.sceneBodies().associateBy { it.id }
        assertEquals(Vec3(15.0,-3.0,2.0),bodies.getValue("first").mesh.bounds.low)
        assertEquals(Vec3(0.0,0.0,0.0),bodies.getValue("sibling").mesh.bounds.low)
        assertEquals(0.0,document.evaluatedSketches().single().drawingFrame().origin.x,1e-9)
        val changed=document.copy(parameters=listOf(Parameter("width","42 mm")))
        assertEquals(21.0,changed.sceneBodies().first { it.id=="first" }.mesh.bounds.low.x,1e-9)
        assertEquals(changed.canonicalFeatures(),changed.copy(features=changed.features.map { CadFeature.read(it.json()) }).canonicalFeatures())
    }
    @Test fun `body position preview and confirmed formula edits preserve undo and primitive coordinates`() {
        val source=Sketch(id="base",width="20 mm",height="12 mm").syncLegacyElement()
        val original=DocumentState(parameters=listOf(Parameter("width","30 mm")),sketches=listOf(source),features=listOf(
            ExtrusionFeature("plate",RegionReference("base","profile"),"5 mm",x="width / 2")
        ))
        val before=original.sceneBodies().associateBy { it.id }
        assertEquals(15.0,before.getValue("plate").mesh.bounds.low.x,1e-6)
        val preview=original.copy(features=original.effectiveFeatures().map { if(it.id=="plate") it.withPosition("width / 2 + 12 mm","0 mm","2 mm") else it })
        assertEquals(27.0,preview.sceneBodies().first { it.id=="plate" }.mesh.bounds.low.x,1e-6)
        assertEquals(15.0,original.sceneBodies().first { it.id=="plate" }.mesh.bounds.low.x,1e-6)
        val history=DocumentHistory(original)
        assertTrue(history.commit(preview))
        assertEquals(27.0,history.current.sceneBodies().first { it.id=="plate" }.mesh.bounds.low.x,1e-6)
        assertNotNull(history.undo())
        assertEquals(original.canonicalFeatures(),history.current.canonicalFeatures())
        assertEquals(15.0,history.current.sceneBodies().first { it.id=="plate" }.mesh.bounds.low.x,1e-6)
    }
    @Test fun `support refers to the selected extrusion instead of the first body of a sketch`() {
        val s=Sketch(id="source").syncLegacyElement()
        val a=ExtrusionFeature("a",RegionReference("source","profile"),"5 mm")
        val b=ExtrusionFeature("b",RegionReference("source","profile"),"12 mm")
        val base=DocumentState(sketches=listOf(s),features=listOf(a,b))
        val face=base.selectFace(13)
        assertEquals("b",face.reference.featureId)
        assertEquals(13,base.sceneTriangle(face.reference))
        val child=Sketch(id="child",width="4 mm",height="4 mm",frame=face.frame,support=face.reference,depth="3 mm")
        val document=base.copy(sketches=base.sketches+child).canonicalFeatures()
        assertEquals(12.0,document.evaluatedSketches().last().frame!!.origin.z)
        val firstChanged=document.copy(features=document.features.map { if(it.id=="a") a.copy(depth="25 mm") else it })
        assertEquals(12.0,firstChanged.evaluatedSketches().last().frame!!.origin.z)
        val secondChanged=document.copy(features=document.features.map { if(it.id=="b") b.copy(depth="18 mm") else it })
        assertEquals(18.0,secondChanged.evaluatedSketches().last().frame!!.origin.z)
    }
    @Test fun `multi-element source has stable regions and rejects legacy replacement`() {
        val s=Sketch(id="multi").withElements(listOf(SketchElement("plate"),SketchElement("circle",SketchShape.CIRCLE,u="100 mm",width="8 mm")))
        val document=DocumentState(sketches=listOf(s),features=listOf(ExtrusionFeature("circle-body",RegionReference("multi","circle"),"7 mm")))
        assertEquals(listOf("plate","circle"),s.regions().map { it.id })
        assertEquals(2,document.sketchPoints().size)
        assertEquals(7.0,document.bodyMeshes().single().bounds.size.z)
        assertFailsWith<IllegalArgumentException> { document.replaceSketch(s.copy(width="10 mm"),true) }
        assertEquals(2,document.sketches.single().elements.size)
    }
    @Test fun `independent extrusions use separate regions and preserve source edits`() {
        val kernel=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernel))
        System.setProperty("kotlin.cad.kernel",kernel.toString())
        val source=Sketch(id="regions",name="Mehrere Regionen").withElements(listOf(
            SketchElement(id="plate",width="40 mm",height="30 mm"),
            SketchElement(id="opening",shape=SketchShape.CIRCLE,u="20 mm",v="15 mm",width="5 mm"),
            SketchElement(id="boss",shape=SketchShape.CIRCLE,u="60 mm",v="10 mm",width="4 mm")
        ))
        val regions=source.regions()
        assertEquals(listOf("plate","opening","boss"),regions.map { it.id })
        assertEquals(listOf("opening"),regions.first().holeElementIds)
        val features=listOf(
            ExtrusionFeature("ring-body",RegionReference(source.id,"plate"),"5 mm"),
            ExtrusionFeature("boss-body",RegionReference(source.id,"boss"),"12 mm")
        )
        val document=DocumentState(sketches=listOf(source),features=features)
        val bodies=document.sceneBodies()
        assertEquals(listOf("ring-body","boss-body"),bodies.map { it.id })
        assertEquals(listOf(5.0,12.0),bodies.map { it.mesh.bounds.size.z })
        val ringVolume=bodies[0].mesh.indices.toList().chunked(3).sumOf { f ->
            bodies[0].mesh.vertices[f[0]].dot(bodies[0].mesh.vertices[f[1]].cross(bodies[0].mesh.vertices[f[2]]))/6
        }
        assertEquals((40*30- kotlin.math.PI*25)*5,ringVolume,2.0)

        val edited=source.withElements(source.elements.map { if(it.id=="plate") it.copy(width="50 mm") else it })
        val updated=document.replaceSketch(edited,legacyExtrusion=false)
        assertEquals(features,updated.features)
        assertEquals(listOf(5.0,12.0),updated.bodyMeshes().map { it.bounds.size.z })
        assertEquals(50.0,updated.sceneBodies().first().mesh.bounds.size.x)
    }
    @Test fun `joint region extrusion previews every selected body and commits as one history step`() {
        val kernel=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernel))
        System.setProperty("kotlin.cad.kernel",kernel.toString())
        val source=Sketch(id="joint",name="Gemeinsame Extrusion").withElements(listOf(
            SketchElement(id="ring",width="30 mm",height="20 mm"),
            SketchElement(id="hole",shape=SketchShape.CIRCLE,u="15 mm",v="10 mm",width="3 mm"),
            SketchElement(id="boss",shape=SketchShape.CIRCLE,u="45 mm",v="10 mm",width="4 mm")
        ))
        val start=DocumentState(sketches=listOf(source))
        val features=listOf("ring" to "ring-feature","boss" to "boss-feature").map { (region,id) ->
            ExtrusionFeature(id,RegionReference(source.id,region),"15 mm")
        }
        val preview=start.prepareSketchPreview(source.id).bodies(source,features)
        assertEquals(2,preview.size)
        assertEquals(setOf(15.0),preview.map { it.bounds.size.z }.toSet())
        val committed=start.replaceSketchExtrusions(source.copy(visible=false),features)
        val history=DocumentHistory(start)
        assertTrue(history.commit(committed))
        assertEquals(setOf("ring-feature","boss-feature"),history.current.sceneBodies().map { it.id }.toSet())
        val ring=history.current.sceneBodies().first { it.id=="ring-feature" }.mesh
        val volume=ring.indices.toList().chunked(3).sumOf { f ->
            ring.vertices[f[0]].dot(ring.vertices[f[1]].cross(ring.vertices[f[2]]))/6
        }
        assertEquals((30*20-kotlin.math.PI*9)*15,volume,2.0)
        assertNotNull(history.undo())
        assertTrue(history.current.features.isEmpty())
        assertFalse(history.current.sketches.single().visible.not())
    }
    @Test fun `parametric planar face extrusion follows its face normal and leaves the source body intact`() {
        val kernel=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernel))
        System.setProperty("kotlin.cad.kernel",kernel.toString())
        val sketch=Sketch(id="base",width="30 mm",height="20 mm").syncLegacyElement()
        val source=DocumentState(sketches=listOf(sketch),features=listOf(ExtrusionFeature("base-body",RegionReference("base","profile"),"12 mm")))
        val face=source.selectFace(0)
        val side=source.selectFace(8)
        assertEquals("base-body",face.reference.featureId)
        val features=listOf(FaceExtrusionFeature("face-copy",face.reference,"7 mm"),FaceExtrusionFeature("side-copy",side.reference,"7 mm"))
        val result=source.copy(features=source.effectiveFeatures()+features)
        val bodies=result.sceneBodies()
        assertEquals(listOf("base-body","face-copy","side-copy"),bodies.map { it.id })
        assertEquals(source.sceneBodies().first().mesh.bounds,bodies.first().mesh.bounds)
        assertContentEquals(source.sceneBodies().first().mesh.indices,bodies.first().mesh.indices)
        listOf(face,side).zip(bodies.drop(1)).forEach { (sourceFace,extrudedBody) ->
            val projected=extrudedBody.mesh.vertices.map { (it-sourceFace.frame.origin).dot(sourceFace.frame.normal) }
            assertEquals(7.0,projected.max()-projected.min(),1e-5)
        }
        val project=folder.resolve("face-extrusion.kcad")
        ProjectIO.write(project,ProjectArchive(result))
        assertEquals(result.canonicalFeatures(),ProjectIO.read(project).document)
        val cylinder=DocumentState(sketches=listOf(Sketch(id="round",shape=SketchShape.CIRCLE).syncLegacyElement()),
            features=listOf(ExtrusionFeature("round-body",RegionReference("round","profile"),"12 mm")))
        assertFailsWith<IllegalArgumentException> { cylinder.selectFace(188) }
        val imported=DocumentState(mesh=MeshData("mesh",listOf(Vec3(0.0,0.0,0.0),Vec3(1.0,0.0,0.0),Vec3(0.0,1.0,0.0)),intArrayOf(0,1,2)))
        assertFailsWith<IllegalArgumentException> {
            imported.copy(features=listOf(FaceExtrusionFeature("bad-mesh-face",FaceReference(triangle=0,featureId="import",surfaceId="mesh-triangle:0"),"7 mm"))).sceneBodies()
        }
    }
    @Test fun `primitive bodies support formula dimensions native preview geometry and project roundtrip`() {
        val kernel=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(kernel))
        System.setProperty("kotlin.cad.kernel",kernel.toString())
        val parameters=listOf(Parameter("breite","30 mm"),Parameter("radius","6 mm"))
        val features=listOf(
            PrimitiveFeature("box","Block",PrimitiveKind.BOX,width="breite",depth="20 mm",height="10 mm",x="5 mm"),
            PrimitiveFeature("cylinder","Stift",PrimitiveKind.CYLINDER,radius="4 mm",height="14 mm",x="45 mm"),
            PrimitiveFeature("sphere","Kugel",PrimitiveKind.SPHERE,radius="radius",x="75 mm")
        )
        val document=DocumentState(parameters=parameters,features=features)
        assertEquals(features,features.map { CadFeature.read(it.json()) })
        assertEquals(cadTools.size,cadTools.map { it.name }.distinct().size)
        assertTrue(cadTools.any { it.name=="cad_preview_primitive" })
        assertTrue(assistantTools.any { it.name=="cad_create_primitive" })
        assertTrue(assistantTools.any { it.name=="cad_set_body_position" })
        assertTrue(assistantTools.any { it.name=="cad_suggest_parametric_copy" })
        assertTrue(assistantTools.any { it.name=="cad_preview_parametric_copy" })
        assertTrue(assistantTools.any { it.name=="cad_accept_parametric_copy" })
        val bodies=document.sceneBodies()
        assertEquals(listOf("box","cylinder","sphere"),bodies.map { it.id })
        assertEquals(30.0,bodies[0].mesh.bounds.size.x,1e-6)
        assertEquals(20.0,bodies[0].mesh.bounds.size.y,1e-6)
        assertEquals(10.0,bodies[0].mesh.bounds.size.z,1e-6)
        assertEquals(8.0,bodies[1].mesh.bounds.size.x,.02)
        assertEquals(14.0,bodies[1].mesh.bounds.size.z,1e-5)
        assertEquals(12.0,bodies[2].mesh.bounds.size.x,.02)
        fun volume(mesh:MeshData)=mesh.indices.toList().chunked(3).sumOf { f -> mesh.vertices[f[0]].dot(mesh.vertices[f[1]].cross(mesh.vertices[f[2]]))/6.0 }
        assertEquals(6000.0,volume(bodies[0].mesh),1e-4)
        assertEquals(kotlin.math.PI*16*14,volume(bodies[1].mesh),3.0)
        assertEquals(4*kotlin.math.PI*216/3,volume(bodies[2].mesh),7.0)
        val path=folder.resolve("primitives.kcad");ProjectIO.write(path,ProjectArchive(document))
        assertEquals(document.canonicalFeatures(),ProjectIO.read(path).document)
        val export=folder.resolve("primitives.stl");val exported=MeshExporter.write(export,document)
        assertEquals(3,exported.parts);assertEquals(bodies.sumOf { it.mesh.triangleCount },exported.triangles)
        val roundtrip=MeshImporter.read(export).bounds;val expected=document.renderMesh()!!.bounds
        assertEquals(expected.low.x,roundtrip.low.x,1e-5);assertEquals(expected.low.y,roundtrip.low.y,1e-5);assertEquals(expected.low.z,roundtrip.low.z,1e-5)
        assertEquals(expected.high.x,roundtrip.high.x,1e-5);assertEquals(expected.high.y,roundtrip.high.y,1e-5);assertEquals(expected.high.z,roundtrip.high.z,1e-5)
    }
    @Test fun `mixed support cycles missing regions and duplicate ids do not enter history`() {
        val base=DocumentState(sketches=listOf(Sketch(id="s")),features=listOf(ExtrusionFeature("a",RegionReference("s","profile"),"5 mm")))
        val history=DocumentHistory(base)
        val cycle=base.copy(features=listOf(MirrorFeature("a","b",SketchPlane.XY),MirrorFeature("b","a",SketchPlane.YZ)))
        assertFails { history.commit(cycle) };assertSame(base,history.current);assertFalse(history.canUndo)
        assertFails { base.copy(features=listOf(ExtrusionFeature("a",RegionReference("s","missing"),"5 mm"))).bodyMeshes() }
        assertFails { base.copy(features=base.features+base.features).bodyMeshes() }
        val attached=base.sketches.single().copy(support=FaceReference("s",false,1,SketchShape.RECTANGLE,"a","profile/cap:end"))
        assertFails { base.copy(sketches=listOf(attached)).bodyMeshes() }
    }
    @Test fun `all legacy version numbers migrate without changing input bytes`() {
        val input=Path.of("samples/extrusions.kcad")
        val entries=ZipFile(input.toFile()).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
        val raw=Json.parseToJsonElement(entries.getValue("project.json").decodeToString()).jsonObject
        for(version in 1..9) {
            val path=folder.resolve("legacy-$version.kcad")
            java.util.zip.ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (name,data) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(if(name=="project.json") JsonObject(raw+("version" to JsonPrimitive(version))).toString().toByteArray() else data);zip.closeEntry()
            } }
            val before=Files.readAllBytes(path);val restored=ProjectIO.read(path).document
            assertTrue(restored.sketches.all { it.depth==null && it.elements.isNotEmpty() })
            assertTrue(restored.features.isNotEmpty());assertContentEquals(before,Files.readAllBytes(path))
            val next=folder.resolve("converted-$version.kcad");ProjectIO.write(next,ProjectArchive(restored))
            assertEquals(restored,ProjectIO.read(next).document)
        }
    }
    @Test fun `semantic support keeps its feature and cap across parameter changes`() {
        val source=Sketch(id="source",width="w",depth="d")
        val legacy=DocumentState(parameters=listOf(Parameter("w","20 mm"),Parameter("d","10 mm")),sketches=listOf(source))
        val face=legacy.selectFace(1)
        val child=Sketch(id="child",width="5 mm",height="5 mm",frame=face.frame,support=face.reference,depth="3 mm")
        val document=legacy.copy(sketches=listOf(source,child)).canonicalFeatures()
        val support=document.sketches.last().support!!
        assertEquals("sketch:source",support.featureId);assertEquals("profile/cap:end",support.surfaceId)
        val moved=document.copy(parameters=listOf(Parameter("w","40 mm"),Parameter("d","25 mm")))
        assertEquals(25.0,moved.evaluatedSketches().last().frame!!.origin.z)
        assertEquals(support,moved.sketches.last().support)
        val path=folder.resolve("v10.kcad");ProjectIO.write(path,ProjectArchive(moved))
        ZipFile(path.toFile()).use { zip ->
            val metadata=Json.parseToJsonElement(zip.getInputStream(zip.getEntry("project.json")).reader().readText()).jsonObject
            assertEquals(12,metadata.getValue("version").jsonPrimitive.int)
            assertTrue(metadata.getValue("sketches").jsonArray.all { it.jsonObject["depth"]==JsonNull })
        }
        assertEquals(moved,ProjectIO.read(path).document)
    }
    @Test fun `open sketch entities retain parameterized paths without becoming extrusion regions`() {
        val elements=listOf(
            SketchElement("line",kind=SketchElementKind.LINE,vertices=listOf(SketchCoordinate("0 mm","0 mm"),SketchCoordinate("w","0 mm"))),
            SketchElement("polyline",kind=SketchElementKind.POLYLINE,vertices=listOf(SketchCoordinate("0 mm","0 mm"),SketchCoordinate("10 mm","0 mm"),SketchCoordinate("10 mm","h"))),
            SketchElement("arc",kind=SketchElementKind.ARC,u="20 mm",width="r",startAngle="0 deg",sweepAngle="90 deg")
        )
        val sketch=Sketch(id="curves").withElements(elements)
        val values=ParameterEvaluator.evaluate(listOf(Parameter("w","25 mm"),Parameter("h","15 mm"),Parameter("r","5 mm")))
        val paths=sketch.elementGeometries(values)
        assertEquals(listOf(SketchElementKind.LINE,SketchElementKind.POLYLINE,SketchElementKind.ARC),paths.map { it.kind })
        assertEquals(listOf(false,false,false),paths.map { it.closed })
        assertEquals(25.0,paths[0].points.last().x)
        assertEquals(3,paths[1].points.size)
        assertEquals(19,paths[2].points.size)
        assertEquals(25.0,paths[2].points.first().x,1e-8)
        assertEquals(5.0,paths[2].points.last().y,1e-8)
        assertTrue(sketch.regions().isEmpty())
        val path=folder.resolve("open-entities.kcad")
        ProjectIO.write(path,ProjectArchive(DocumentState(parameters=listOf(Parameter("w","25 mm"),Parameter("h","15 mm"),Parameter("r","5 mm")),sketches=listOf(sketch))))
        val restored=ProjectIO.read(path).document.sketches.single().elements
        assertEquals(elements,restored)
        assertFails { SketchElement("bad",kind=SketchElementKind.LINE).geometry(sketch,values) }
    }
}
