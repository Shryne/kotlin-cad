package cad

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture

/** UI and automation share the controller; external input is validated before any mutation. */
fun ViewerController.executeCommand(request: JsonObject): CompletableFuture<JsonObject> = uiFuture {
    val action = request.requiredString("action")
    val arguments = request["arguments"]?.jsonObject ?: buildJsonObject { }
    when (action) {
        "cad_set_body_color" -> setObjectColor(arguments.requiredString("bodyId"),arguments["color"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content).thenCompose { uiFuture { modelInfo() } }
        "cad_get_color_regions" -> CompletableFuture.completedFuture(document.colorRegions(arguments.requiredString("bodyId"),arguments["offset"]?.jsonPrimitive?.int ?: 0,arguments["limit"]?.jsonPrimitive?.int ?: 100))
        "cad_set_area_color" -> setAreaColor(arguments.requiredString("bodyId"),document.colorAreaFrom(arguments)).thenCompose { uiFuture { modelInfo() } }
        "cad_remove_area_color" -> removeAreaColor(arguments.requiredString("bodyId"),arguments.requiredString("areaId")).thenCompose { uiFuture { modelInfo() } }
        "cad_apply_batch" -> CompletableFuture.completedFuture(startAssemblyTask("batch",arguments))
        "cad_get_assembly_job" -> CompletableFuture.completedFuture(assemblyTask(arguments.requiredString("jobId")))
        "cad_cancel_assembly_job" -> CompletableFuture.completedFuture(cancelAssemblyTask(arguments.requiredString("jobId")))
        "cad_check_assembly_path" -> CompletableFuture.completedFuture(startAssemblyTask("path",arguments))
        "cad_check_retention" -> CompletableFuture.completedFuture(startAssemblyTask("retention",arguments))
        "cad_check_manufacturing" -> CompletableFuture.completedFuture(startAssemblyTask("manufacturing",arguments))
        "cad_compare_assembly" -> CompletableFuture.completedFuture(startAssemblyTask("compare",arguments))
        "cad_get_assembly" -> CompletableFuture.completedFuture(document.assemblyReport())
        "cad_create_instance", "cad_update_instance" -> {
            val update=action=="cad_update_instance"
            val id=if(update) arguments.requiredString("id") else arguments["id"]?.jsonPrimitive?.content ?: "instance-${java.util.UUID.randomUUID()}"
            val old=document.effectiveFeatures().firstOrNull { it.id==id }
            require(if(update) old is InstanceFeature else old==null) { "Instanz fehlt oder ID ist bereits vergeben." }
            val feature=InstanceFeature.read(JsonObject((old?.json() ?: buildJsonObject { put("name","Instanz") })+arguments+mapOf("id" to JsonPrimitive(id))))
            CompletableFuture.completedFuture(startAssemblyTask("batch",buildJsonObject { put("operations",JsonArray(listOf(buildJsonObject { put("op","put_feature");put("feature",feature.json()) }))) }))
        }
        "cad_preview_exploded" -> {
            require(!loading && !sketchEditor && !saving && !exporting)
            val (meshes,report)=document.explodedMeshes(arguments.number("distanceMm",20.0,0.0,10000.0))
            viewport.showAssemblyPreview(combineMeshes(meshes),document.renderColors()).thenApply { report }
        }
        "cad_clear_assembly_preview" -> viewport.clearAssemblyPreview().thenApply { buildJsonObject { put("modelChanged",false) } }
        "cad_highlight_manufacturing" -> {
            val job=assemblyTask(arguments.requiredString("jobId"));require(job.requiredString("status")=="complete") { "Prüfung ist noch nicht abgeschlossen." }
            val warnings=job.getValue("result").jsonObject["warnings"]?.jsonArray ?: error("Kein Fertigungsbericht.")
            val bodies=document.sceneBodies().associateBy { it.id }
            warnings.forEach { val w=it.jsonObject;val body=bodies[w.requiredString("bodyId")] ?: error("Körper fehlt.");require(colorRevision(body.mesh)==w.requiredString("sourceRevision")) { "Prüfung veraltet; erneut ausführen." } }
            val gaps=job.getValue("result").jsonObject["smallClearances"]?.jsonArray ?: JsonArray(emptyList())
            val points=warnings.map { it.jsonObject.getValue("point").jsonObject.colorPoint() }+gaps.map { gap ->
                fun point(key:String):Vec3 { val a=gap.jsonObject.getValue(key).jsonArray;return Vec3(a[0].jsonPrimitive.double,a[1].jsonPrimitive.double,a[2].jsonPrimitive.double) }
                (point("closestA")+point("closestB"))*.5
            }
            viewport.highlightAnalysis(points).thenApply { buildJsonObject { put("highlighted",points.size);put("modelChanged",false) } }
        }
        "cad_get_model" -> CompletableFuture.completedFuture(modelInfo())
        "cad_get_sketch" -> {
            val id=arguments.requiredString("id")
            val sketch=document.evaluatedSketches().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Skizze existiert nicht.")
            val solution=sketch.solveConstraints(parameterValues)
            CompletableFuture.completedFuture(buildJsonObject {
                put("sketch",sketch.copy(depth=null).json());put("regions",JsonArray(sketch.regions(parameterValues).map { r -> buildJsonObject {
                    put("id",r.id);put("outerElementIds",JsonArray(r.outerElementIds.map(::JsonPrimitive)));put("holeElementIds",JsonArray(r.holeElementIds.map(::JsonPrimitive)))
                } }))
                put("features",JsonArray(document.effectiveFeatures().filterIsInstance<ExtrusionFeature>().filter { it.region.sketchId==id }.map { it.json() }))
                put("constraintStatus",solution.status);put("degreesOfFreedom",solution.degreesOfFreedom);put("conflicts",JsonArray(solution.conflicts.distinct().map(::JsonPrimitive)))
            })
        }
        "cad_add_sketch_constraint" -> {
            val type=SketchConstraintType.valueOf(arguments.requiredString("type"))
            val elementIds=arguments["elementIds"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: throw IllegalArgumentException("elementIds fehlt.")
            val indices=arguments["indices"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList()
            val constraint=SketchConstraint(type=type,elementIds=elementIds,indices=indices,
                value=arguments["value"]?.jsonPrimitive?.content,internal=arguments.optionalBoolean("internal") ?: false)
            addCommittedSketchConstraint(arguments.requiredString("sketchId"),constraint).thenCompose { solution -> uiFuture {
                JsonObject(modelInfo()+mapOf(
                    "constraintStatus" to JsonPrimitive(solution.status),
                    "degreesOfFreedom" to JsonPrimitive(solution.degreesOfFreedom),
                    "conflicts" to JsonArray(solution.conflicts.distinct().map(::JsonPrimitive)),
                    "accepted" to JsonPrimitive(solution.status!="conflicting"),
                    "isError" to JsonPrimitive(solution.status=="conflicting")
                ))
            } }
        }
        "cad_remove_sketch_constraint" -> removeCommittedSketchConstraint(arguments.requiredString("sketchId"),arguments.requiredString("constraintId")).thenCompose { solution -> uiFuture {
            JsonObject(modelInfo()+mapOf("constraintStatus" to JsonPrimitive(solution.status),"degreesOfFreedom" to JsonPrimitive(solution.degreesOfFreedom),"conflicts" to JsonArray(solution.conflicts.distinct().map(::JsonPrimitive))))
        } }
        "cad_get_feature" -> {
            val id=arguments.requiredString("id")
            val feature=document.effectiveFeatures().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Feature existiert nicht.")
            val body=document.sceneBodies().first { it.id==id }
            val reliableFaces=body.sketch!=null
            val faces=if(reliableFaces) (0 until body.mesh.triangleCount).distinctBy { body.surfaceId(it) } else emptyList()
            CompletableFuture.completedFuture(buildJsonObject {
                if(feature is NativeShapeFeature) document.constructionInfo(id).forEach { (key,value)->put(key,value) }
                put("appearance",document.colorInfo(body));put("feature",feature.json());put("body",buildJsonObject { put("id",id);put("triangles",body.mesh.triangleCount);put("visible",body.visible);put("faceReferencesReliable",reliableFaces) })
                put("faces",JsonArray(faces.map { triangle -> buildJsonObject {
                    val planar=body.sketch?.shape!=SketchShape.CIRCLE || triangle<188
                    put("id",body.surfaceId(triangle));put("triangle",triangle);put("planar",planar)
                    if(planar) put("frame",body.mesh.faceFrame(triangle).json())
                } }))
            })
        }
        "cad_preview_loft", "cad_create_loft", "cad_update_loft" -> {
            val update=action=="cad_update_loft"
            val id=if(update) arguments.requiredString("id") else "loft-${java.util.UUID.randomUUID()}"
            val old=if(update) document.effectiveFeatures().filterIsInstance<LoftFeature>().firstOrNull { it.id==id } ?: error("Loft existiert nicht.") else null
            val feature=loftFrom(JsonObject((old?.json() ?: buildJsonObject {})+arguments),id).let { if(old==null) it else it.copy(x=old.x,y=old.y,z=old.z) }
            if(action=="cad_preview_loft") previewLoft(feature)
            else storeLoft(feature,update).thenCompose { uiFuture { feature.result(document.sceneBodies().first { it.id==id }.mesh) } }
        }
        "cad_preview_primitive" -> {
            val feature=primitiveFrom(arguments)
            val mesh=feature.evaluateMesh(parameterValues)
            CompletableFuture.completedFuture(buildJsonObject {
                put("feature",feature.json());put("preview",buildJsonObject {
                    put("triangles",mesh.triangleCount);put("dimensions",mesh.bounds.size.json());put("bounds",buildJsonObject { put("min",mesh.bounds.low.json());put("max",mesh.bounds.high.json()) })
                })
            })
        }
        "cad_create_primitive" -> {
            val feature=primitiveFrom(arguments)
            createPrimitive(feature).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_set_body_position" -> {
            val id=arguments.requiredString("id")
            val current=document.effectiveFeatures().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Der Körper besitzt keine parametrische Positionsdefinition.")
            val (x,y,z)=when(current) { is PrimitiveFeature -> Triple(current.x,current.y,current.z);is ExtrusionFeature -> Triple(current.x,current.y,current.z);is FaceExtrusionFeature -> Triple(current.x,current.y,current.z);is MirrorFeature -> Triple(current.x,current.y,current.z);is LoftFeature -> Triple(current.x,current.y,current.z);is NativeShapeFeature -> Triple(current.x,current.y,current.z);is InstanceFeature -> Triple(current.x,current.y,current.z) }
            setBodyPosition(id,arguments["x"]?.jsonPrimitive?.content ?: x,arguments["y"]?.jsonPrimitive?.content ?: y,arguments["z"]?.jsonPrimitive?.content ?: z)
                .thenCompose { uiFuture { modelInfo() } }
        }
        "cad_preview_construction", "cad_create_construction", "cad_update_construction" -> constructionCommand(arguments,action)
        "cad_delete_feature" -> deleteGeneratedFeature(arguments.requiredString("id")).thenCompose { uiFuture { modelInfo() } }
        "cad_check_interference" -> {
            val snapshot=document;val ids=arguments.getValue("bodyIds").jsonArray.map { it.jsonPrimitive.content }
            CompletableFuture.supplyAsync { snapshot.checkInterference(ids,arguments.number("contactToleranceMm",.00001,.000001,1.0),arguments.number("volumeToleranceMm3",.000001,.000001,1.0)) }.thenCompose { report -> uiFuture {
                require(document===snapshot) { "Projekt geändert; Kollisionsprüfung wiederholen." };report
            } }
        }
        "cad_export_bodies" -> {
            val path=Path.of(arguments.requiredString("path"));require(path.isAbsolute) { "Exportpfad muss absolut sein." }
            exportSelectedBodies(path,arguments.getValue("bodyIds").jsonArray.map { it.jsonPrimitive.content },arguments["orientation"]?.jsonObject ?: buildJsonObject {},arguments.optionalBoolean("overwrite") ?: false)
        }
        "cad_inspect_construction" -> {
            val shape=arguments.getValue("shape").jsonObject
            val parameters=constructionParameters(arguments)
            require(parameters.none { p -> document.parameters.any { it.name==p.name } }) { "Parametername bereits vorhanden." }
            val values=ParameterEvaluator.evaluate(document.parameters+parameters)
            // Constructor applies the same size/name limits as a persisted feature.
            NativeShapeFeature("inspection","Konstruktion",shape)
            CadNativeGeometry.kernel.request("inspect_edges",buildJsonObject { put("shape",resolveNativeShape(shape,values)) })
        }
        "cad_propose_construction_copy" -> MeshReconstructionJobs.proposeConstructionAsync(document,
            arguments.requiredString("analysisJobId"),arguments.requiredString("name"),arguments.getValue("shape").jsonObject,constructionParameters(arguments))
        "cad_suggest_parametric_copy" -> {
            val requested=arguments["openingDiameter"]?.jsonPrimitive?.doubleOrNull
            MeshReconstructionJobs.suggestAsync(document,arguments.requiredString("analysisJobId"),arguments["openingContourId"]?.jsonPrimitive?.content,requested)
        }
        "cad_preview_parametric_copy" -> previewMeshReconstruction(arguments.requiredString("proposalId"))
        "cad_accept_parametric_copy" -> acceptMeshReconstruction(arguments.requiredString("proposalId")).thenCompose { uiFuture { modelInfo() } }
        "cad_discard_parametric_copy" -> {
            val id=arguments.requiredString("proposalId");discardMeshReconstruction(id)
            CompletableFuture.completedFuture(buildJsonObject { put("proposalId",id);put("discarded",true);put("modelChanged",false) })
        }
        "cad_measure_mesh_sections", "cad_measure_bore_profile", "cad_compare_mesh_surface" -> {
            val snapshot=document;val mesh=requireNotNull(snapshot.mesh) { "Lade zuerst STL/OBJ." }
            require(snapshot.display.unit=="mm") { "Messungen erwarten Millimeter." }
            val result=when(action) {
                "cad_measure_mesh_sections" -> {
                    val origin=arguments.getValue("origin").measureVector();val normal=arguments.getValue("normal").measureVector().normalized()
                    val offsets=arguments.getValue("offsets").jsonArray.map { it.jsonPrimitive.double };val tolerance=arguments.number("fitToleranceMm",.03,.00001,5.0)
                    MeasurementJobs.start(mesh,snapshot) { MeshMeasurements.sections(mesh,origin,normal,offsets,tolerance) }
                }
                "cad_measure_bore_profile" -> {
                    val origin=arguments.getValue("origin").measureVector();val axis=arguments.getValue("axis").measureVector().normalized()
                    val start=arguments.number("startDepthMm",0.0,-1e9,1e9);val end=arguments.number("endDepthMm",10.0,-1e9,1e9)
                    val steps=arguments["steps"]?.jsonPrimitive?.int ?: 17;val tolerance=arguments.number("fitToleranceMm",.03,.00001,5.0)
                    MeasurementJobs.start(mesh,snapshot) { MeshMeasurements.bore(mesh,origin,axis,start,end,steps,tolerance) }
                }
                else -> {
                    val proposalId=arguments["proposalId"]?.jsonPrimitive?.content;val featureId=arguments["featureId"]?.jsonPrimitive?.content
                    require((proposalId==null)!=(featureId==null)) { "Genau proposalId oder featureId angeben." }
                    val proposal=proposalId?.let(MeshReconstructionJobs::get)
                    val candidate=proposal?.apply(snapshot) ?: snapshot
                    val ids=proposal?.features?.map { it.id } ?: listOf(featureId!!)
                    require(ids.none { it=="import" }) { "Vergleiche mit einer konstruierten Kopie." }
                    val tolerance=arguments.number("toleranceMm",.1,.0001,10.0);val budget=arguments["cellBudget"]?.jsonPrimitive?.int ?: 50000
                    MeasurementJobs.start(mesh,snapshot) {
                        val parts=candidate.sceneBodies().filter { it.id in ids }.map { it.mesh };require(parts.size==ids.size) { "Vergleichskörper fehlt." }
                        val copy=if(parts.size==1)parts.single() else combineMeshes("Vergleich",parts)
                        JsonObject(MeshSurfaceComparison.compare(mesh,copy,tolerance,budget).json()+mapOf("measurementKind" to JsonPrimitive("surface-comparison"),"targetRevision" to JsonPrimitive(MeshAnalysisJobs.revisionOf(copy))))
                    }
                }
            }
            CompletableFuture.completedFuture(result)
        }
        "cad_get_measurement" -> CompletableFuture.completedFuture(MeasurementJobs.read(arguments.requiredString("jobId"),document,arguments["offset"]?.jsonPrimitive?.int ?: 0,arguments["limit"]?.jsonPrimitive?.int ?: 100))
        "cad_cancel_measurement" -> CompletableFuture.completedFuture(MeasurementJobs.cancel(arguments.requiredString("jobId")))
        "cad_get_deviation_map" -> {
            val id=arguments.requiredString("jobId");MeasurementJobs.requireCurrent(id,document)
            val report=MeasurementJobs.result(id);val range=arguments.number("rangeMm",1.0,.0001,1000.0)
            CompletableFuture.supplyAsync { DeviationMap.render(report,range) }
        }
        "cad_analyze_mesh" -> {
            val mesh=document.mesh ?: throw IllegalArgumentException("Lade zuerst eine STL- oder OBJ-Datei.")
            val jobId=MeshAnalysisJobs.start(mesh,document.display.unit)
            CompletableFuture.completedFuture(buildJsonObject { put("jobId",jobId);put("status","running");put("unit",document.display.unit);put("modelChanged",false) })
        }
        "cad_get_mesh_analysis" -> {
            val offset=arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0
            val limit=arguments["limit"]?.jsonPrimitive?.intOrNull ?: 50
            CompletableFuture.completedFuture(MeshAnalysisJobs.read(arguments.requiredString("jobId"),offset,limit))
        }
        "cad_cancel_mesh_analysis" -> CompletableFuture.completedFuture(MeshAnalysisJobs.cancel(arguments.requiredString("jobId")))
        "cad_open_file" -> {
            val path = Path.of(arguments.requiredString("path"))
            require(path.isAbsolute && Files.isRegularFile(path)) { "path muss eine vorhandene, absolute STL-/OBJ-Datei sein." }
            require(path.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("stl", "obj")) {
                "Unterstützt werden STL und OBJ."
            }
            open(path, arguments.optionalBoolean("discardChanges") ?: false).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_open_project" -> {
            val path = Path.of(arguments.requiredString("path"))
            require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().lowercase().endsWith(".kcad")) {
                "path muss eine vorhandene, absolute .kcad-Datei sein."
            }
            open(path, arguments.optionalBoolean("discardChanges") ?: false).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_save_project" -> {
            val path = Path.of(arguments.requiredString("path"))
            require(path.isAbsolute) { "Projektpfad muss absolut sein." }
            saveProject(path, arguments.optionalBoolean("overwrite") ?: false).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_export_mesh" -> {
            val path=Path.of(arguments.requiredString("path"))
            require(path.isAbsolute) { "Exportpfad muss absolut sein." }
            exportMeshes(path,arguments.optionalBoolean("overwrite") ?: false).thenApply { exported -> buildJsonObject {
                put("path",exported.path.toString());put("format",exported.format.name);put("triangles",exported.triangles)
                put("parts",exported.parts);put("bytes",exported.bytes)
            } }
        }
        "cad_set_sketch" -> {
            val id = arguments["id"]?.let { arguments.requiredString("id") } ?: java.util.UUID.randomUUID().toString()
            val previous=document.sketches.firstOrNull { it.id==id }
            val face=arguments["supportFace"]?.let {
                val index=arguments.number("supportFace",0.0,0.0,999999.0)
                require(index==index.toInt().toDouble()) { "supportFace muss eine ganze Zahl sein." }
                document.selectFace(index.toInt())
            }
            val sketch = Sketch(id, arguments.requiredString("name"), SketchPlane.valueOf(arguments.requiredString("plane")),
                SketchShape.valueOf(arguments.requiredString("shape")), arguments.requiredString("u"), arguments.requiredString("v"),
                arguments.requiredString("width"), arguments["height"]?.let { arguments.requiredString("height") } ?: "40 mm",
                arguments["depth"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                arguments["mirror"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(SketchPlane::valueOf),
                arguments["mirrorOffset"]?.let { arguments.requiredString("mirrorOffset") } ?: "0 mm",
                face?.frame ?: previous?.frame, face?.reference ?: previous?.support,
                arguments["angle"]?.let { arguments.requiredString("angle") } ?: "0 deg", arguments.optionalBoolean("centered") ?: false, arguments.optionalBoolean("visible") ?: true)
            setSketch(sketch).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_delete_import" -> removeImportedBody().thenCompose { uiFuture { modelInfo() } }
        "cad_fit_body" -> fitBody(arguments.requiredString("id")).thenCompose { viewport.cameraPose() }.thenCompose { pose -> uiFuture {
            JsonObject(modelInfo().toMutableMap().apply { put("camera",buildJsonObject {
                put("yaw",pose.yaw);put("pitch",pose.pitch);put("panX",pose.panX);put("panY",pose.panY);put("zoom",pose.zoom)
            }) })
        } }
        "cad_isolate_body" -> isolateBody(arguments.requiredString("id")).thenCompose { uiFuture { modelInfo() } }
        "cad_show_all_bodies" -> showAllBodies().thenCompose { uiFuture { modelInfo() } }
        "cad_set_body_visibility" -> {
            val visible=arguments.optionalBoolean("visible") ?: throw IllegalArgumentException("visible fehlt.")
            setBodyVisibility(arguments.requiredString("id"),visible).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_set_sketch_visibility" -> {
            val visible=arguments.optionalBoolean("visible") ?: throw IllegalArgumentException("visible fehlt.")
            setSketchVisibility(arguments.requiredString("id"),visible).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_rename_sketch" -> renameSketch(arguments.requiredString("id"),arguments.requiredString("name")).thenCompose { uiFuture { modelInfo() } }
        "cad_duplicate_sketch" -> duplicateSketch(arguments.requiredString("id")).thenCompose { uiFuture { modelInfo() } }
        "cad_delete_sketch" -> {
            val id = arguments.requiredString("id")
            require(document.sketches.any { it.id == id }) { "Skizze existiert nicht." }
            removeSketch(id).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_get_parameters" -> CompletableFuture.completedFuture(modelInfo())
        "cad_set_parameter" -> {
            setParameter(arguments.requiredString("name"), arguments.requiredString("expression"))
            CompletableFuture.completedFuture(modelInfo())
        }
        "cad_delete_parameter" -> {
            removeParameter(arguments.requiredString("name"))
            CompletableFuture.completedFuture(modelInfo())
        }
        "cad_undo" -> undo().thenCompose { uiFuture { modelInfo() } }
        "cad_redo" -> redo().thenCompose { uiFuture { modelInfo() } }
        "cad_align_to_sketch" -> {
            require(!(arguments.containsKey("sketchId") && arguments.containsKey("supportFace"))) { "Nur sketchId oder supportFace angeben." }
            val frame = when {
                arguments.containsKey("sketchId") -> {
                    val id=arguments.requiredString("sketchId")
                    (document.evaluatedSketches().firstOrNull { it.id==id }
                        ?: throw IllegalArgumentException("Skizze existiert nicht.")).drawingFrame()
                }
                arguments.containsKey("supportFace") -> {
                    val index=arguments.number("supportFace",0.0,0.0,999999.0)
                    require(index==index.toInt().toDouble()) { "supportFace muss eine ganze Zahl sein." }
                    document.selectFace(index.toInt()).frame
                }
                else -> sketchDraft?.drawingFrame() ?: selectedFace?.frame
                    ?: throw IllegalArgumentException("Zuerst eine Fläche oder Skizze wählen.")
            }
            viewport.alignToPlane(frame).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_set_view" -> {
            val name = mapOf("iso" to "Iso", "front" to "Vorne", "top" to "Oben", "right" to "Rechts",
                "back" to "Hinten", "left" to "Links", "bottom" to "Unten")[arguments.requiredString("view")]
                ?: throw IllegalArgumentException("Unbekannte Ansicht.")
            val fitAfter = arguments.optionalBoolean("fit") ?: false
            view(name).thenCompose { if (fitAfter) fit() else CompletableFuture.completedFuture(Unit) }
                .thenCompose { uiFuture { modelInfo() } }
        }
        "cad_set_display" -> {
            val wire = arguments.optionalBoolean("wireframe")
            val grid = arguments.optionalBoolean("grid")
            val visible = arguments.optionalBoolean("visible")
            val unit = arguments["unit"]?.let {
                arguments.requiredString("unit").also { value -> require(value in setOf("mm", "cm", "m", "in")) { "Ungültige Einheit." } }
            }
            configureDisplay(wire, grid, visible, unit).thenCompose { uiFuture {
                if(wire==true) JsonObject(modelInfo()+ ("warnings" to JsonArray(listOf(JsonPrimitive("Drahtgitter wird nicht mehr unterstützt; Körper werden als Flächen dargestellt."))))) else modelInfo()
            } }
        }
        "cad_navigate" -> {
            val yaw = arguments.number("yaw", 0.0, -360.0, 360.0)
            val pitch = arguments.number("pitch", 0.0, -180.0, 180.0)
            val dx = arguments.number("panX", 0.0, -5000.0, 5000.0)
            val dy = arguments.number("panY", 0.0, -5000.0, 5000.0)
            val factor = arguments.number("zoom", 1.0, 0.1, 10.0)
            viewport.navigate(yaw, pitch, dx, dy, factor).thenCompose { uiFuture { modelInfo() } }
        }
        "cad_get_preview" -> {
            val size = arguments.number("maxSize", 1200.0, 256.0, 1600.0)
            require(size == size.toInt().toDouble()) { "maxSize muss eine ganze Zahl sein." }
            viewport.preview(size.toInt()).thenApply { image ->
                buildJsonObject {
                    put("mimeType", "image/png"); put("width", image.width); put("height", image.height)
                    put("data", Base64.getEncoder().encodeToString(image.png))
                }
            }
        }
        else -> throw IllegalArgumentException("Unbekannter CAD-Befehl: $action")
    }
}.thenCompose { it }

private fun ViewerController.modelInfo(): JsonObject = buildJsonObject {
    put("assemblyJob",assemblyTaskState ?: JsonNull)
    put("instance", ProcessHandle.current().pid())
    put("application", buildJsonObject { put("version", AppBuild.version); put("build", AppBuild.id) })
    put("bodies", JsonArray(document.bodyMeshes().map { body -> buildJsonObject {
        put("name",body.name);put("triangles",body.triangleCount)
        put("dimensions",buildJsonObject { put("x",body.bounds.size.x);put("y",body.bounds.size.y);put("z",body.bounds.size.z) })
        put("bounds",buildJsonObject { put("min",body.bounds.low.json());put("max",body.bounds.high.json()) })
    } }))
    put("sceneBodies",JsonArray(document.sceneBodies().map { body -> buildJsonObject {
        put("appearance",document.colorInfo(body));put("id",body.id);put("name",body.mesh.name);put("sketchId",body.sketch?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("mirrored",body.mirrored);put("visible",body.visible);put("effectiveVisible",body.visible && visible);put("triangles",body.mesh.triangleCount)
        put("dimensions",body.mesh.bounds.size.json());put("bounds",buildJsonObject { put("min",body.mesh.bounds.low.json());put("max",body.mesh.bounds.high.json()) })
    } }))
    put("schemaVersion",12)
    put("legacySketchDepthProjection",true)
    put("features",JsonArray(document.effectiveFeatures().map { it.json() }))
    put("sketches", JsonArray(document.evaluatedSketches().map { sketch ->
        JsonObject(sketch.copy(depth=document.primaryExtrusion(sketch.id)?.depth).json()+mapOf(
            "regions" to JsonArray(sketch.regions(parameterValues).map { r -> buildJsonObject { put("id",r.id);put("outerElementIds",JsonArray(r.outerElementIds.map(::JsonPrimitive)));put("holeElementIds",JsonArray(r.holeElementIds.map(::JsonPrimitive))) } }),
            "extrusionFeatureIds" to JsonArray(document.effectiveFeatures().filterIsInstance<ExtrusionFeature>().filter { it.region.sketchId==sketch.id }.map { JsonPrimitive(it.id) })
        ))
    }))
    put("sketchMode",buildJsonObject { put("previewBodies",sketchPreviewBodies);put("previewError",sketchPreviewError?.let(::JsonPrimitive) ?: JsonNull);put("editing",sketchEditor);put("selectingSupport",selectingSupport);put("draft",sketchDraft?.json() ?: JsonNull) })
    put("loading", loading); put("view", currentView)
    put("project", buildJsonObject {
        put("path", projectPath?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("dirty", dirty); put("saving", saving);put("exporting",exporting); put("canUndo", canUndo); put("canRedo", canRedo)
    })
    put("parameters", JsonArray(parameters.map { parameter ->
        val quantity = parameterValues.getValue(parameter.name)
        buildJsonObject {
            put("name", parameter.name); put("expression", parameter.expression); put("value", quantity.value)
            put("display", quantity.label())
            put("dimension", buildJsonObject { put("length", quantity.dimension.length); put("angle", quantity.dimension.angle) })
        }
    }))
    put("display", buildJsonObject {
        put("wireframe", wireframe); put("grid", grid); put("visible", visible); put("unit", unit)
        put("hiddenBodyIds",JsonArray(document.display.hiddenBodyIds.sorted().map(::JsonPrimitive)))
    })
    put("model", model?.let { data ->
        buildJsonObject {
            put("name", data.name); put("path", sourcePath?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("vertices", data.vertices.size); put("triangles", data.triangleCount)
            put("dimensions", data.bounds.size.json())
            put("bounds", buildJsonObject { put("min", data.bounds.low.json()); put("max", data.bounds.high.json()) })
        }
    } ?: JsonNull)
}

private fun primitiveFrom(arguments:JsonObject):PrimitiveFeature {
    val kind=PrimitiveKind.valueOf(arguments.requiredString("kind"))
    val defaultName=when(kind) { PrimitiveKind.BOX->"Quader";PrimitiveKind.CYLINDER->"Zylinder";PrimitiveKind.SPHERE->"Kugel" }
    fun optional(key:String,default:String)=arguments[key]?.let { arguments.requiredString(key) } ?: default
    return PrimitiveFeature("primitive-${java.util.UUID.randomUUID()}",optional("name",defaultName),kind,
        optional("width","20 mm"),optional("depth","20 mm"),optional("height","20 mm"),optional("radius","10 mm"),
        optional("x","0 mm"),optional("y","0 mm"),optional("z","0 mm"))
}
private fun Vec3.json() = buildJsonObject { put("x", x); put("y", y); put("z", z) }
internal fun JsonObject.requiredString(key: String): String {
    val value = get(key) as? JsonPrimitive
    require(value != null && value.isString && value.content.isNotBlank()) { "$key muss eine nichtleere Zeichenkette sein." }
    return value.content
}
internal fun JsonObject.optionalBoolean(key: String): Boolean? {
    val value = get(key) ?: return null
    require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "$key muss true oder false sein." }
    return value.booleanOrNull
}
internal fun JsonObject.number(key: String, default: Double, min: Double, max: Double): Double {
    val value = get(key) ?: return default
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    require(number != null && number.isFinite() && number in min..max) { "$key muss zwischen $min und $max liegen." }
    return number
}
internal fun <T> uiFuture(action: () -> T): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    onUi { try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) } }
    return future
}

internal fun constructionParameters(arguments:JsonObject):List<Parameter> {
    val entries=arguments["parameters"]?.jsonArray ?: return emptyList()
    require(entries.size<=64) { "Höchstens 64 Parameter sind erlaubt." }
    val parameters=entries.map { val p=it.jsonObject;Parameter(p.requiredString("name"),p.requiredString("expression")) }
    require(parameters.map { it.name }.distinct().size==parameters.size) { "Parameternamen müssen eindeutig sein." }
    return parameters
}
