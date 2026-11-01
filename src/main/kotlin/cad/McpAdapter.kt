package cad

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.io.PrintStream
import java.nio.file.Path

data class CadTool(val name: String, val description: String, val properties: JsonObject, val required: List<String> = emptyList())

val cadTools: List<CadTool> = assemblyTools + listOf(
    CadTool("cad_set_body_color","Set a body's saved base display color (#RRGGBB). Omit color to reset to default. Area colors override this base. Undo/redo and .kcad persistence; no geometry changes. Returns model with appearance per body.",buildJsonObject { put("bodyId",property("string","Existing body ID."));put("color",property("string","Opaque sRGB #RRGGBB; omit to reset.")) },listOf("bodyId")),
    CadTool("cad_get_color_regions","Read colorable coplanar surface patches, local bounds/normals, sourceRevision and current appearance. Patches can include disconnected coplanar triangles. Curved surfaces have multiple faceted patches; select multiple IDs or use a spatial zone. Page with offset/limit. Read again after geometry changes.",buildJsonObject { put("bodyId",property("string","Existing body ID."));put("offset",property("integer","Zero-based page offset, default 0."));put("limit",property("integer","Page size 1..200, default 100.")) },listOf("bodyId")),
    CadTool("cad_set_area_color","Create or replace one named area color. Requires EITHER regionIds plus sourceRevision from cad_get_color_regions OR min/max coordinates (millimeters in body-local space, before feature position translation). Spatial zones color triangles whose centroids lie inside; boundaries follow tessellation, not a geometry cut. Newest/updated area wins overlaps. Patch colors expire safely after geometry edits and appear in staleAreaIds; spatial zones re-evaluate. Preserves geometry; supports undo/redo and save/load.",buildJsonObject {
        put("bodyId",property("string","Existing body ID."));put("areaId",property("string","Stable area ID chosen by caller, 1..128 characters; reuse to edit."));put("name",property("string","Area label, 1..128 characters."));put("color",property("string","Opaque sRGB #RRGGBB."))
        put("sourceRevision",property("string","Current revision from cad_get_color_regions; required with regionIds."))
        put("regionIds",buildJsonObject { put("type","array");put("items",property("string","Patch ID."));put("minItems",1);put("maxItems",1000) })
        for(key in listOf("min","max")) put(key,buildJsonObject { put("type","object");put("properties",buildJsonObject { for(axis in listOf("x","y","z")) put(axis,property("number","Body-local millimeters.")) });put("required",JsonArray(listOf("x","y","z").map(::JsonPrimitive)));put("additionalProperties",false) })
    },listOf("bodyId","areaId","name","color")),
    CadTool("cad_remove_area_color","Remove a saved color area and reveal underlying areas or the body color. Undo/redo supported.",buildJsonObject { put("bodyId",property("string","Existing body ID."));put("areaId",property("string","Saved area ID.")) },listOf("bodyId","areaId")),
    CadTool("cad_get_model", "Read every body's stable id, world-space minimum/maximum bounds and dimensions, plus sketches, features, parameters, units and display state in the running local CAD viewer.", buildJsonObject { }),
    CadTool("cad_get_sketch", "Read one canonical sketch with stable element and region IDs and its extrusion features. Sketch depth is null; extrusion depth lives on features. No mutation.",buildJsonObject { put("id",property("string","Existing sketch id.")) },listOf("id")),
    CadTool("cad_add_sketch_constraint", "Add one validated geometric relation or dimensional constraint to a committed sketch. Returns solver status, degrees of freedom and conflicting constraint IDs. Failed constraints do not change the project; successful changes support undo/redo and project save/load.",buildJsonObject {
        put("sketchId",property("string","Existing committed sketch id."))
        put("type",buildJsonObject { put("type","string");put("enum",JsonArray(SketchConstraintType.entries.map { JsonPrimitive(it.name) })) })
        put("elementIds",buildJsonObject { put("type","array");put("items",buildJsonObject { put("type","string") });put("minItems",1);put("maxItems",2) })
        put("indices",buildJsonObject { put("type","array");put("items",buildJsonObject { put("type","integer") });put("description","Point or segment indices as required by the constraint.") })
        put("value",property("string","Optional dimensional expression, such as 20 mm, Lochabstand, or 30 deg."))
        put("internal",property("boolean","Use internal circle tangency. Default false."))
    },listOf("sketchId","type","elementIds")),
    CadTool("cad_remove_sketch_constraint", "Remove one constraint by its stable ID from a committed sketch. Returns recalculated solver status. Supports undo/redo and project persistence.",buildJsonObject {
        put("sketchId",property("string","Existing committed sketch id."));put("constraintId",property("string","Constraint ID returned by cad_get_sketch."))
    },listOf("sketchId","constraintId")),
    CadTool("cad_get_feature", "Read an independent extrusion, face extrusion, primitive or mirror feature, its body and stable semantic face IDs where available. Unreconstructable mesh faces are not offered as planar references. No mutation.",buildJsonObject { put("id",property("string","Feature id from cad_get_model features.")) },listOf("id")),
    CadTool("cad_preview_loft","Preview a capped elliptical solid loft without modifying the project. Sections are ordered; each center and radius is a length expression, rotations are angle expressions applied X then Y then Z. Local +X seam and quarter-profile alignment are preserved. Smooth by default; ruled=true uses straight spans. Returns featureId/bodyId (provisional), world mesh bounds and solid validation. No imported mesh required.",loftProperties(),listOf("sections")),
    CadTool("cad_create_loft","Create a validated, editable solid loft independently of imports. Supports parameters, undo/redo, save/load and export. Same sections as cad_preview_loft. Returns featureId, bodyId, world mesh bounds and validation. Invalid solids leave the document unchanged.",loftProperties(),listOf("sections")),
    CadTool("cad_update_loft","Replace all ordered sections of an existing loft by id; preserves id and body position. Supply the complete sections; omitted name and ruled mode retain their current values. Validate before atomic update. Returns featureId, bodyId, world mesh bounds and validation.",loftProperties(true),listOf("id","sections")),
    CadTool("cad_preview_primitive","Build a temporary parametric box, cylinder or sphere and return its proposed bounds and mesh counts. Does not change the document.",primitiveProperties(),listOf("kind")),
    CadTool("cad_create_primitive","Create one separate parametric box, cylinder or sphere. Dimensions and position accept length expressions and project parameters; the result is saved in the document and supports undo/redo.",primitiveProperties(),listOf("kind")),
    CadTool("cad_set_body_position","Set a parametric body's world-space X/Y/Z position. Values accept length formulas and project parameters; the update is one undoable transaction and leaves other bodies unchanged.",buildJsonObject {
        put("id",property("string","Stable generated body/feature id from cad_get_model. Imported meshes cannot be moved by this feature tool."))
        listOf("x","y","z").forEach { put(it,property("string","Length formula in mm, e.g. 10 mm or width / 2. Omitted axes remain unchanged.")) }
    },listOf("id")),
    CadTool("cad_measure_mesh_sections","Measure actual STL/OBJ triangle-plane intersections at up to 32 offsets, including outer boundaries and openings between rounded surfaces. Returns a jobId; poll cad_get_measurement. Reports raw closed/open contours, line/arc/cubic-Bezier fits with residuals, section-radius and chamfer candidates with transition measurements. Coplanar/branched contours are flagged; never infer exact design intent from candidates.",buildJsonObject {
        put("origin",measurementVectorProperty());put("normal",measurementVectorProperty());put("offsets",buildJsonObject {put("type","array");put("items",property("number","Signed offset in mm along normalized normal."));put("minItems",1);put("maxItems",32)});put("fitToleranceMm",property("number","Curve fit residual tolerance, default 0.03 mm."))
    },listOf("origin","normal","offsets")),
    CadTool("cad_measure_bore_profile","Measure coaxial inner circular sections along a supplied bore axis, including side bores. Returns a jobId; poll cad_get_measurement. Profiles report radius versus depth, cylindrical runs, possible countersinks/counterbores and depth uncertainty. Missing or ambiguous sections are unknown, never filled in. Refine depth intervals around transitions.",buildJsonObject {
        put("origin",measurementVectorProperty());put("axis",measurementVectorProperty());put("startDepthMm",property("number","Start depth along normalized axis."));put("endDepthMm",property("number","End depth, greater than start."));put("steps",property("integer","3 to 64 sections, default 17."));put("fitToleranceMm",property("number","Curve fit tolerance in mm, default .03."))
    },listOf("origin","axis","startDepthMm","endDepthMm")),
    CadTool("cad_compare_mesh_surface","Start a bidirectional adaptive point-to-triangle surface comparison of the import against exactly one proposalId or featureId. Returns jobId. Poll cad_get_measurement for maximum lower/upper bounds, convergence, centroid RMS and paginated per-triangle deviation map. Detects local errors missed by vertex-only comparison. Bounds apply to triangle meshes, not unknown original smooth surfaces; converged=false means budget exhausted. Use cad_get_deviation_map for a color image.",buildJsonObject {
        put("proposalId",property("string","Current reconstruction proposal."));put("featureId",property("string","Committed constructed feature."));put("toleranceMm",property("number","Requested maximum bound gap, default .1 mm."));put("cellBudget",property("integer","100 to 200000 cells per direction, default 50000; at least the largest mesh triangle count."))
    },emptyList()),
    CadTool("cad_get_measurement","Read a measurement job. status running/complete/failed/cancelled; stale=true if project changed. Comparison maps paginate with offset/limit and mapCount per direction; sections/bore profiles return completely. Never apply stale measurements.",buildJsonObject {put("jobId",property("string","Measurement job id."));put("offset",property("integer","Comparison map offset, default 0."));put("limit",property("integer","Comparison map page size 1 to 1000, default 100."))},listOf("jobId")),
    CadTool("cad_cancel_measurement","Cancel a measurement without changing the document.",buildJsonObject {put("jobId",property("string","Measurement job id."))},listOf("jobId")),
    CadTool("cad_get_deviation_map","Render a PNG color map for a completed current surface-comparison job. Both directions shown in isometric projection; colors are measured per-triangle maximum sample distance, not certified local maxima. Includes scale and convergence warning. Does not change the viewport.",buildJsonObject {put("jobId",property("string","Completed surface-comparison job."));put("rangeMm",property("number","Red end of color scale in mm, default 1."))},listOf("jobId")),
    CadTool("cad_analyze_mesh","Start a bounded, cancellable local analysis of the currently imported STL/OBJ. Returns a job id immediately; the source mesh and project remain unchanged. Reports include mesh quality, connected components, approximate planar patches, closed planar boundary loops, cylindrical surface fits and repeated parallel cylinder-patch groups. The report does not infer manufacturing intent.",buildJsonObject { }),
    CadTool("cad_get_mesh_analysis","Read local mesh quality, connected components, approximate planar patches, closed contour candidates, cylindrical surface fits, repeated parallel cylinder-patch groups and revision-scoped feature evidence candidates. Fits include measured residuals; loops at nesting depth zero remain unclassified because they may be openings or outside patch edges. Candidate geometry does not establish feature intent. Automatic template reconstruction is bounded to fitted thin plates with paired through-openings and one detected squat-rack profile: two repeated-hole hollow uprights, a crossbar and two foot plates. For other shapes, use this measured evidence with cad_propose_construction_copy to build an explicit constructive proposal with fillets, chamfers, rotations and Boolean operations; this is not automatic recognition. Arrays are paginated with offset and limit; consult capability and unknowns before drawing conclusions.",buildJsonObject {
        put("jobId",property("string","Id returned by cad_analyze_mesh."));put("offset",buildJsonObject { put("type","integer");put("minimum",0) });put("limit",buildJsonObject { put("type","integer");put("minimum",1);put("maximum",100) })
    },listOf("jobId")),
    CadTool("cad_cancel_mesh_analysis","Cancel a running local mesh analysis. Does not change the model.",buildJsonObject { put("jobId",property("string","Id returned by cad_analyze_mesh.")) },listOf("jobId")),
    CadTool("cad_preview_construction","Preview one closed connected solid from a constructive tree without an import or document mutation. Optional parameters are NEW project parameters evaluated only in the preview. Returns provisional featureId/bodyId, exact BRep world bounds/dimensions, solidCount, volumeMm3 and validation. "+constructionShapeHelp,directConstructionProperties(false),listOf("shape")),
    CadTool("cad_create_construction","Create one separate editable parametric solid directly in any project. Optional NEW parameters and geometry commit atomically; existing parameters are available in expressions. Disconnected results are rejected: one feature per physical component. Returns stable featureId/bodyId, exact BRep world bounds/dimensions, solidCount, volumeMm3 and validation. "+constructionShapeHelp,directConstructionProperties(false),listOf("shape")),
    CadTool("cad_update_construction","Replace the tree of an existing nativeShape construction feature, preserving feature/body ID and position. Optional name replaces its name. Optional parameters add NEW project parameters only; use cad_set_parameter for existing parameters. Full validation before atomic commit. Returns IDs, BRep bounds/dimensions, count, volume and validity. "+constructionShapeHelp,directConstructionProperties(true),listOf("id","shape")),
    CadTool("cad_delete_feature","Delete one generated feature/body, with undo/redo. Rejects deletion while another feature or attached sketch depends on it; no cascade. Keeps named parameters and source sketches. Import deletion uses cad_delete_import.",buildJsonObject { put("id",property("string","Generated feature ID from cad_get_model.")) },listOf("id")),
    CadTool("cad_check_interference","Read-only pairwise solid interference for 2 to 8 body IDs in world assembly positions, including hidden bodies. Returns intersectionVolumeMm3, distanceMm and status separated/contact/overlap. Overlap means volume > volumeToleranceMm3; contact means distance <= contactToleranceMm with volume below threshold (near-contact included). Native constructions, primitives and lofts use exact BRep; other bodies use closed faceted mesh solids (max 10000 triangles), explicitly labelled faceted-solid. Invalid/open meshes fail, never guessed from bounding boxes.",buildJsonObject {
        put("bodyIds",bodyIdsProperty(2,8));put("contactToleranceMm",boundedNumber("Distance tolerance for contact, default 0.00001 mm.",.000001,1.0));put("volumeToleranceMm3",boundedNumber("Overlap volume threshold, default 0.000001 mm³.",.000001,1.0))
    },listOf("bodyIds")),
    CadTool("cad_export_bodies","Export selected committed bodies to one STL/OBJ. For individual files, call once per body. Hidden bodies may be selected. Temporary print orientation transforms export meshes only; document, camera, assembly positions and history remain unchanged. Rotation X then Y then Z about origin, then translation, then optional placeOnBed sets combined min Z to zero. Returns exported bounds and dimensions per body. Separate selected bodies are not unioned.",buildJsonObject {
        put("bodyIds",bodyIdsProperty(1,64));put("path",property("string","Absolute .stl or .obj destination; parent directory must exist."));put("overwrite",property("boolean","Allow replacing destination; default false."))
        put("orientation",buildJsonObject { put("type","object");put("additionalProperties",false);put("properties",buildJsonObject {
            listOf("rotationX","rotationY","rotationZ").forEach { put(it,property("string","Angle expression, default 0 deg.")) }
            listOf("originX","originY","originZ","x","y","z").forEach { put(it,property("string","Length expression, default 0 mm. x/y/z are translation after rotation.")) }
            put("placeOnBed",property("boolean","After transforming, set combined min Z to zero. Default false."))
        }) })
    },listOf("bodyIds","path")),
    CadTool("cad_inspect_construction","Evaluate a bounded constructive solid tree and return exact BRep edge curve midpoints (at), endpoints, lengths and curve types for fillet/chamfer selection. Existing project parameters are resolved automatically; optional parameters define additional new names for inspection only. No document mutation. Inspect the exact child shape before selecting edges; indices are not persistent. "+constructionShapeHelp,constructionProperties(false),listOf("shape")),
    CadTool("cad_propose_construction_copy","Prepare a general editable reconstruction of an analyzed import from an explicit constructive solid tree: angled plates, ribs, openings, bores, fillets and chamfers. Returns deviation/volume comparison, not a claim of exact recognition. Preserves import. Follow with cad_preview_parametric_copy and cad_accept_parametric_copy or cad_discard_parametric_copy. "+constructionShapeHelp,constructionProperties(true),listOf("analysisJobId","name","shape")),
    CadTool("cad_suggest_parametric_copy","Prepare a separate parametric copy proposal for a fitted thin plate or a detected squat-rack assembly. The rack path returns five separate parametric hollow-profile and footplate bodies from repeated cross-hole and planar evidence. Requires a completed, current cad_analyze_mesh job in millimeters. Ambiguous plate openings return candidate contour IDs and do not create a proposal. Does not change the document.",buildJsonObject {
        put("analysisJobId",property("string","Completed job id from cad_analyze_mesh."))
        put("openingContourId",property("string","Plate path only: contourId selected from candidateOpenings when cad_suggest_parametric_copy requested a choice."))
        put("openingDiameter",numberProperty("Plate path only: optional desired center-opening diameter in mm; must be at least the measured diameter. The value becomes a named project parameter on acceptance.",0.0,100000.0))
    },listOf("analysisJobId")),
    CadTool("cad_preview_parametric_copy","Evaluate a proposed parametric plate, rack or general constructive copy, display all its separate bodies as a temporary viewport preview and report dimensions, bounds, parameters, net volume change and a symmetric deterministic nearest-vertex sample distance in both directions. The distance is approximate, not a certified surface or Hausdorff bound. The original imported mesh is untouched.",buildJsonObject {
        put("proposalId",property("string","Proposal id returned by cad_suggest_parametric_copy."))
    },listOf("proposalId")),
    CadTool("cad_accept_parametric_copy","Accept a reviewed, current reconstruction preview as separate parametric features and named parameters. Plate copies create a sketch and extrusion; rack copies create separate hollow-profile and footplate bodies; general construction copies retain their parametric operation tree. The imported source remains in the document. Supports one-step undo/redo, .kcad persistence and mesh export.",buildJsonObject {
        put("proposalId",property("string","Proposal id returned by cad_suggest_parametric_copy."))
    },listOf("proposalId")),
    CadTool("cad_discard_parametric_copy","Discard a pending reconstruction proposal and clear its preview. Does not change document geometry.",buildJsonObject {
        put("proposalId",property("string","Proposal id returned by cad_suggest_parametric_copy."))
    },listOf("proposalId")),
    CadTool("cad_open_file", "Open an existing STL or OBJ in the running viewer. Replaces the viewed model; does not modify the file.",
        buildJsonObject {
            put("path", property("string", "Absolute path to an existing STL or OBJ file."))
            put("discardChanges", property("boolean", "Explicitly discard unsaved document edits before opening. Default false."))
        }, listOf("path")),
    CadTool("cad_open_project", "Open a self-contained .kcad project. Unsaved changes are preserved unless discardChanges is explicitly true.",
        buildJsonObject {
            put("path", property("string", "Absolute path to an existing .kcad project."))
            put("discardChanges", property("boolean", "Explicitly discard unsaved document edits. Default false."))
        }, listOf("path")),
    CadTool("cad_save_project", "Save the geometry, parameters, display and camera in a self-contained .kcad file. Existing files require overwrite=true.",
        buildJsonObject {
            put("path", property("string", "Absolute destination path ending in .kcad."))
            put("overwrite", property("boolean", "Explicitly allow replacing an existing file. Default false."))
        }, listOf("path")),
    CadTool("cad_export_mesh", "Export committed imported and generated geometry as binary STL or OBJ in project coordinates. Format follows the extension. Excludes sketch drafts and view overlays; does not save or modify the project. Separate bodies are not unioned.",
        buildJsonObject {
            put("path",property("string","Absolute destination ending in .stl or .obj."))
            put("overwrite",property("boolean","Explicitly allow replacing an existing export. Default false."))
        },listOf("path")),
    CadTool("cad_set_sketch", "Create or replace one closed rectangular, circular or isosceles triangular sketch profile. Local coordinates and signed extrusion axes: XY maps (u,v) to (X,Y) and extrudes +Z; XZ maps to (X,Z) and extrudes +Y; YZ maps to (Y,Z) and extrudes +X. Positive depth follows that axis; negative depth reverses it. RECTANGLE u/v are its lower-left corner unless centered=true; CIRCLE u/v are its center and width is radius; TRIANGLE u/v are the left end of its base, width is base length and height is altitude with centered apex. Length expressions use project parameters. Omit id to create. Optional depth creates a separate body; omit depth to keep a flat profile. Optional mirror (XY/XZ/YZ) adds a linked reflected copy; mirrorOffset is a length expression, default 0 mm. Bodies are not boolean-unioned: if adding a thin door/window feature, place it just outside the wall rather than extruding inward where it will be hidden.",
        buildJsonObject {
            put("visible",property("boolean","Show the sketch contour; generated objects remain visible. Default true."))
            put("centered",property("boolean","RECTANGLE only: u/v specify the fixed center; width and height extend symmetrically. Default false."))
            put("supportFace",buildJsonObject { put("type","integer");put("minimum",0);put("description","Triangle index in the current scene. Attach to this planar body face; omit to retain an existing attachment or use an origin plane.") })
            listOf("id", "name", "plane", "shape", "u", "v", "width", "height", "depth", "mirror", "mirrorOffset", "angle").forEach {
                put(it, property("string", when(it) { "plane" -> "XY, XZ or YZ"; "shape" -> "RECTANGLE, CIRCLE or TRIANGLE (centered apex, width=base, height=altitude)"; "angle" -> "In-plane rotation around u/v; angle expression such as 30 deg or a named angle parameter, default 0 deg."; else -> "Sketch $it" }))
            }
        }, listOf("name", "plane", "shape", "u", "v", "width")),
    CadTool("cad_delete_import", "Remove the imported STL/OBJ object from the project. Keeps sketches and extrusions; sketches attached to imported faces retain their current world plane. Does not delete the source file. Supports undo, redo and project persistence.",buildJsonObject { }),
    CadTool("cad_fit_body", "Fit one visible body into the usable viewport while retaining orientation. Does not change geometry, visibility or undo history. Returns the resulting camera pose.",buildJsonObject {
        put("id",property("string","Stable visible body identifier from cad_get_model sceneBodies."))
    },listOf("id")),
    CadTool("cad_isolate_body", "Show only one imported, extruded or reflected body. Enables the global body display; leaves sketch visibility unchanged. Supports undo and persistence.",buildJsonObject {
        put("id",property("string","Stable body identifier from cad_get_model sceneBodies."))
    },listOf("id")),
    CadTool("cad_show_all_bodies", "Show every committed body and enable the global body display. Leaves sketch visibility unchanged. Supports undo and persistence.",buildJsonObject { }),
    CadTool("cad_set_body_visibility", "Show or hide one imported body, extrusion or reflected body independently of its sketch. Use import, sketch:<sketch id> or mirror:<sketch id>. Does not change geometry or exports. Supports undo and project persistence.",buildJsonObject {
        put("id",property("string","Stable body identifier from cad_get_model sceneBodies."));put("visible",property("boolean","Show this body."))
    },listOf("id","visible")),
    CadTool("cad_set_sketch_visibility", "Show or hide one sketch contour without hiding its generated objects. Supports undo and project persistence.",buildJsonObject {
        put("id",property("string","Existing sketch id."));put("visible",property("boolean","Show the sketch contour."))
    },listOf("id","visible")),
    CadTool("cad_rename_sketch", "Rename a sketch without changing its geometry or parameters. Supports undo.",buildJsonObject {
        put("id",property("string","Existing sketch id."));put("name",property("string","New name, 1 to 128 characters."))
    },listOf("id","name")),
    CadTool("cad_duplicate_sketch", "Duplicate a sketch profile with a new id and unique name. Preserves formulas, support and reflected contours, but creates no extrusion. Supports undo.",buildJsonObject {
        put("id",property("string","Existing sketch id."))
    },listOf("id")),
    CadTool("cad_delete_sketch", "Delete a sketch profile. Supports Undo.",
        buildJsonObject { put("id", property("string", "Existing sketch identifier.")) }, listOf("id")),
    CadTool("cad_get_parameters", "Read named project parameters, expressions, evaluated values and dimensions.", buildJsonObject { }),
    CadTool("cad_set_parameter", "Create or update a named parameter. Recomputes dependencies and validates dimensions. Does not yet change imported mesh geometry.",
        buildJsonObject {
            put("name", property("string", "Parameter identifier, e.g. width."))
            put("expression", property("string", "Arithmetic expression, e.g. 60 mm or width / 2."))
        }, listOf("name", "expression")),
    CadTool("cad_delete_parameter", "Delete a parameter unless another parameter depends on it. Supports Undo.",
        buildJsonObject { put("name", property("string", "Existing parameter name.")) }, listOf("name")),
    CadTool("cad_undo", "Undo the last parameter or display change. Camera navigation is not recorded.", buildJsonObject { }),
    CadTool("cad_redo", "Redo a previously undone document change.", buildJsonObject { }),
    CadTool("cad_align_to_sketch", "Look straight at a sketch plane or planar body face. Keeps pan, zoom, geometry and undo history unchanged. With no arguments uses the active sketch or selected face.",
        buildJsonObject {
            put("sketchId",property("string","Existing sketch id. Mutually exclusive with supportFace."))
            put("supportFace",buildJsonObject { put("type","integer");put("minimum",0);put("description","Triangle index of a planar face. Mutually exclusive with sketchId.") })
        }),
    CadTool("cad_set_view", "Set a standard camera orientation. Optionally fit the model in view.", buildJsonObject {
        put("view", buildJsonObject {
            put("type", "string"); put("enum", JsonArray(listOf("iso", "front", "top", "right", "back", "left", "bottom").map(::JsonPrimitive)))
        })
        put("fit", property("boolean", "Reset pan and zoom to fit the model."))
    }, listOf("view")),
    CadTool("cad_set_display", "Change wireframe, grid, visibility, or assign an import unit. Assigning a unit does not rescale the file coordinates.",
        buildJsonObject {
            put("wireframe", property("boolean", "Show triangle edges instead of filled faces."))
            put("grid", property("boolean", "Show the reference grid."))
            put("visible", property("boolean", "Show the imported model."))
            put("unit", buildJsonObject {
                put("type", "string"); put("enum", JsonArray(listOf("mm", "cm", "m", "in").map(::JsonPrimitive)))
            })
        }),
    CadTool("cad_navigate", "Adjust camera orbit (degrees), pan (screen pixels) and zoom (multiplier). Unspecified values are unchanged.",
        buildJsonObject {
            put("yaw", numberProperty("Horizontal orbit delta in degrees.", -360.0, 360.0))
            put("pitch", numberProperty("Vertical orbit delta in degrees.", -180.0, 180.0))
            put("panX", numberProperty("Horizontal movement in screen pixels.", -5000.0, 5000.0))
            put("panY", numberProperty("Vertical movement in screen pixels.", -5000.0, 5000.0))
            put("zoom", numberProperty("Zoom multiplier; 1 leaves zoom unchanged.", 0.1, 10.0))
        }),
    CadTool("cad_get_preview", "Return a PNG image of the current 3D viewport, including its orientation cube.",
        buildJsonObject {
            put("maxSize", buildJsonObject { put("type", "integer"); put("minimum", 256); put("maximum", 1600) })
        })
)

/** Entry point deliberately does not create Compose, AWT or JavaFX windows. */
fun runMcpAdapter(args: List<String>) = runBlocking {
    val socketIndex = args.indexOf("--socket")
    require(socketIndex < 0 || socketIndex + 1 < args.size) { "--socket benötigt einen Pfad." }
    val client = BridgeClient(socket = if (socketIndex >= 0) Path.of(args[socketIndex + 1]) else null)
    // stdout is reserved for protocol messages, including if a dependency logs with println.
    val protocolOutput = System.out
    System.setOut(PrintStream(System.err, true, Charsets.UTF_8))
    val server = Server(
        Implementation("kotlin-cad", AppBuild.version),
        ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
    )
    cadTools.forEach { tool ->
        server.addTool(
            name = tool.name, description = tool.description,
            inputSchema = Tool.Input(properties = tool.properties, required = tool.required),
            toolAnnotations = ToolAnnotations(
                title = tool.name,
                readOnlyHint = tool.name in setOf("cad_get_assembly_job","cad_check_assembly_path","cad_check_retention","cad_check_manufacturing","cad_get_assembly","cad_compare_assembly","cad_get_color_regions","cad_preview_construction","cad_check_interference","cad_preview_loft","cad_measure_mesh_sections","cad_measure_bore_profile","cad_compare_mesh_surface","cad_get_measurement","cad_get_deviation_map","cad_inspect_construction", "cad_propose_construction_copy", "cad_get_model", "cad_get_sketch", "cad_get_feature", "cad_get_preview", "cad_preview_primitive", "cad_get_parameters", "cad_get_mesh_analysis", "cad_suggest_parametric_copy"),
                destructiveHint = tool.name in setOf("cad_export_bodies","cad_delete_feature","cad_save_project","cad_export_mesh"),
                idempotentHint = tool.name !in setOf("cad_apply_batch","cad_create_instance","cad_check_assembly_path","cad_check_retention","cad_check_manufacturing","cad_compare_assembly","cad_create_construction","cad_delete_feature","cad_create_loft","cad_measure_mesh_sections","cad_measure_bore_profile","cad_compare_mesh_surface","cad_cancel_measurement","cad_propose_construction_copy", "cad_navigate", "cad_undo", "cad_redo", "cad_duplicate_sketch", "cad_create_primitive", "cad_analyze_mesh", "cad_cancel_mesh_analysis", "cad_suggest_parametric_copy", "cad_accept_parametric_copy", "cad_discard_parametric_copy"), openWorldHint = false
            )
        ) { request ->
            try {
                val response = withContext(Dispatchers.IO) {
                    client.call(buildJsonObject {
                        put("action", tool.name)
                        put("arguments", request.arguments ?: buildJsonObject { })
                    })
                }
                if (tool.name in setOf("cad_get_preview","cad_get_deviation_map")) CallToolResult(
                    content = listOf(ImageContent(
                        data = response.requiredString("data"), mimeType = "image/png"
                    )), isError = false
                ) else CallToolResult(
                    content = listOf(TextContent(response.toString())),
                    structuredContent = response, isError = response["isError"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                CallToolResult(content = listOf(TextContent(e.message ?: "CAD command failed.")), isError = true)
            }
        }
    }
    val finished = CompletableDeferred<Unit>()
    server.onClose { finished.complete(Unit) }
    val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOutput.asSink().buffered())
    try { server.connect(transport); finished.await() }
    finally { server.close() }
}

private fun property(type: String, description: String) = buildJsonObject {
    put("type", type); put("description", description)
}
private fun numberProperty(description: String, minimum: Double, maximum: Double) = buildJsonObject {
    put("type", "number"); put("description", description); put("minimum", minimum); put("maximum", maximum)
}
private fun primitiveProperties()=buildJsonObject {
    put("kind",buildJsonObject { put("type","string");put("enum",JsonArray(PrimitiveKind.entries.map { JsonPrimitive(it.name) }));put("description","BOX, CYLINDER oder SPHERE") })
    put("name",property("string","Optionaler Objektname."))
    put("width",property("string","Quaderbreite X als Länge/Formel; Standard 20 mm."))
    put("depth",property("string","Quadertiefe Y als Länge/Formel; Standard 20 mm."))
    put("height",property("string","Quader-/Zylinderhöhe Z als Länge/Formel; Standard 20 mm."))
    put("radius",property("string","Zylinder-/Kugelradius als Länge/Formel; Standard 10 mm."))
    put("x",property("string","Position X; Quader min-X, Zylinder Fußmittelpunkt, Kugel Mittelpunkt. Standard 0 mm."))
    put("y",property("string","Position Y; Quader min-Y, Zylinder Fußmittelpunkt, Kugel Mittelpunkt. Standard 0 mm."))
    put("z",property("string","Position Z; Quader/Zylinder Boden, Kugel Mittelpunkt. Standard 0 mm."))
}

private const val constructionShapeHelp="Shape nodes: box{x,y,z}, cylinder{radius,height}, sphere{radius}, cone{radius1,radius2,height} for countersinks, extrude{outer:[[x,y,z],...],holes:[[[x,y,z],...]],vector:[x,y,z]}, union/cut/intersect{a:shape,b:shape}, translate{shape,offset:[x,y,z]}, rotate{shape,axis:[x,y,z],origin:[x,y,z],angle:radians}, fillet{shape,radius,edges:[{at:[x,y,z],tolerance:0.001}]}, chamfer{shape,distance,edges:[{at:[x,y,z],tolerance:0.001}]}. extrudeCurves{outer:[segments],holes:[[segments]],vector:[x,y,z]} accepts connected closed planar wires of segments: line{kind,start,end}, arc{kind,start,mid,end}, circle{kind,center,normal,radius}, bezier{kind,controlPoints:[four XYZ points]}. Reconcile neighboring fitted endpoints before constructing a closed wire. Coordinates and lengths in mm; replace any length with {expression: 'width / 2'} or {parameter:'width'}. Rotation angle can use {angleExpression:'slope'} (angle parameter). Axis components are dimensionless; use {scalarExpression:'axisX'} for dimensionless parameter expressions. Edge at is measured curve parameter midpoint from cad_inspect_construction, in the child shape coordinates; selectors must match exactly one edge. Radii/distances must be positive; impossible operations fail atomically. Max 512 nodes, depth 32, 128 selected edges. No references to imported mesh triangles or external files."
private fun constructionProperties(copy:Boolean)=buildJsonObject {
    put("shape",buildJsonObject { put("type","object");put("description",constructionShapeHelp);put("additionalProperties",true) })
    put("parameters",buildJsonObject { put("type","array");put("maxItems",64);put("items",buildJsonObject {
        put("type","object");put("properties",buildJsonObject { put("name",property("string","Unique new parameter name."));put("expression",property("string","Dimensioned formula, e.g. 4 mm or 20 deg.")) });put("required",JsonArray(listOf("name","expression").map(::JsonPrimitive)))
    }) })
    if(copy) { put("analysisJobId",property("string","Completed, current analysis in mm."));put("name",property("string","Name of the new constructed body.")) }
}

private fun measurementVectorProperty()=buildJsonObject {put("type","array");put("items",property("number","XYZ component."));put("minItems",3);put("maxItems",3)}

private fun loftProperties(update:Boolean=false)=buildJsonObject {
    if(update) put("id",property("string","Existing loft feature ID."))
    put("name",property("string","Feature name, default Loft."));put("ruled",property("boolean","Straight spans instead of smooth interpolation; default false."))
    put("sections",buildJsonObject { put("type","array");put("minItems",2);put("maxItems",32);put("items",buildJsonObject {
        put("type","object");put("properties",buildJsonObject {
            listOf("x","y","z","radiusX","radiusY").forEach { put(it,property("string","Length expression in mm or named parameter; radii >= .001 mm.")) }
            listOf("rotationX","rotationY","rotationZ").forEach { put(it,property("string","Angle expression, e.g. 20 deg. Applied X then Y then Z.")) }
        });put("required",JsonArray(listOf("x","y","z","radiusX","radiusY","rotationX","rotationY","rotationZ").map(::JsonPrimitive)))
    }) })
}

private fun directConstructionProperties(update:Boolean)=buildJsonObject {
    constructionProperties(false).forEach { (key,value)->put(key,value) }
    put("name",property("string","Feature name. Default Konstruktion on create; retained when omitted on update."))
    if(update) put("id",property("string","Existing nativeShape feature ID."))
}
private fun bodyIdsProperty(min:Int,max:Int)=buildJsonObject { put("type","array");put("minItems",min);put("maxItems",max);put("uniqueItems",true);put("items",buildJsonObject { put("type","string") }) }
private fun boundedNumber(description:String,min:Double,max:Double)=buildJsonObject { put("type","number");put("minimum",min);put("maximum",max);put("description",description) }
