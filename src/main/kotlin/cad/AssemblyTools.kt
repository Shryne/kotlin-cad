package cad

import kotlinx.serialization.json.*

private fun field(type:String,description:String)=buildJsonObject { put("type",type);put("description",description) }
private fun strings(description:String)=buildJsonObject { put("type","array");put("description",description);put("items",field("string","Existing stable ID.")) }
private fun pointSchema()=buildJsonObject { put("type","object");put("properties",buildJsonObject { for(axis in listOf("x","y","z")) put(axis,field("number","Millimeters.")) });put("required",JsonArray(listOf("x","y","z").map(::JsonPrimitive)));put("additionalProperties",false) }
private fun instanceProperties()=buildJsonObject {
    put("id",field("string","Stable instance ID. Optional for creation; required for update."));put("name",field("string","Instance label."));put("sourceFeatureId",field("string","Existing generated source body. Linked geometry uses source-local coordinates, excluding the source position. Nested instances allowed; cycles rejected."))
    for(axis in listOf("x","y","z")) put(axis,field("string","Placement length expression, e.g. 30 mm or pitch."))
    for(axis in listOf("rotationX","rotationY","rotationZ")) put(axis,field("string","Angle expression, e.g. 90 deg. Rotations X then Y then Z about source-local origin, then translation."))
    put("inheritColors",field("boolean","Default true. Source triangle colors follow rotation; explicit instance base color replaces inheritance, instance areas override. Omit instance base color to inherit again."))
}
private fun pathProperties(retention:Boolean)=buildJsonObject {
    put("bodyId",field("string","Moving body ID."));put("obstacleIds",strings("1..16 fixed bodies, excluding moving body."))
    put("stepMm",field("number","Sample spacing .01..1000 mm, default 1. Max 201 samples and 256 body-pair tests per path."))
    if(retention) put("travelMm",field("number","Probe travel .01..10000 mm, default 20 along each of six world axes."))
    else put("waypoints",buildJsonObject { put("type","array");put("items",pointSchema());put("minItems",2);put("maxItems",32);put("description","Ordered translation offsets relative to saved placement, in world millimeters. For insertion use an outside offset ending at zero; removal begins at zero. No rotation or deformation.") })
}
internal val assemblyTools=listOf(
    CadTool("cad_apply_batch","Atomically stage 1..256 geometry, parameter, placement and color changes, validate the final dependency graph, and publish once as one undo step. Returns an asynchronous job; poll cad_get_assembly_job. Failure, cancellation or concurrent document edits do not commit the candidate. Intermediate definitions need not be valid. put_feature creates/replaces full canonical feature JSON with caller-chosen stable ID; use cad_get_feature for edits. Existing IDs cannot change feature type. Area selectors resolve against final geometry and apply in their listed order. Expensive identical geometry is cached; color/placement changes avoid native rebuilds.",buildJsonObject {
        put("operations",buildJsonObject { put("type","array");put("minItems",1);put("maxItems",256);put("items",buildJsonObject { put("type","object");put("properties",buildJsonObject {
            put("op",buildJsonObject { put("type","string");put("enum",JsonArray(listOf("set_parameter","delete_parameter","put_feature","delete_feature","set_position","set_body_color","set_area_color","remove_area_color").map(::JsonPrimitive))) })
            put("feature",field("object","Full canonical primitive, nativeShape, loft, extrusion, faceExtrusion, mirror or instance feature JSON; required for put_feature."))
            for(key in listOf("id","name","expression","bodyId","color","areaId","sourceRevision","x","y","z")) put(key,field("string","Operation field. Parameter: name/expression; feature deletion/position: id; colors: same fields as cad_set_body_color/cad_set_area_color/cad_remove_area_color."))
            put("regionIds",strings("Current color patch IDs."));put("min",pointSchema());put("max",pointSchema())
        });put("required",JsonArray(listOf(JsonPrimitive("op"))));put("additionalProperties",false) }) })
    },listOf("operations")),
    CadTool("cad_get_assembly_job","Read progress and result. Status running/committing/complete/failed/cancelled/stale. Only a completed batch changes the document. Cancellation is accepted while running, never during atomic commit. Reports are scoped to their snapshot; inspect stale status before using them.",buildJsonObject { put("jobId",field("string","Returned job ID.")) },listOf("jobId")),
    CadTool("cad_cancel_assembly_job","Cancel a running assembly job and its private geometry worker without affecting other jobs or the document. Idempotent; completed or committing jobs cannot be cancelled.",buildJsonObject { put("jobId",field("string","Returned job ID.")) },listOf("jobId")),
    CadTool("cad_create_instance","Create a linked, independently placed/rotated instance of an existing generated part. Stable ID, inherited/overridden colors, undo/redo and persistence. Returns a batch job; poll until complete before depending on the new body.",instanceProperties(),listOf("sourceFeatureId")),
    CadTool("cad_update_instance","Edit an existing linked instance, preserving its ID. Omitted fields retain values. Returns a batch job; poll until complete.",instanceProperties(),listOf("id")),
    CadTool("cad_check_assembly_path","Start insertion/removal path screening. Reports first sampled collision; otherwise certifies segments only when exact distances exceed the displacement bound, or returns inconclusive. No sampled collision alone is NOT proof of clear motion. Uses native solids or closed faceted fallback. Progress/cancellation via assembly job tools; no document changes.",pathProperties(false),listOf("bodyId","obstacleIds","waypoints")),
    CadTool("cad_check_retention","Start six-axis straight escape-path probes. Reports directional blocking, not a proof of mechanical retention, strength, snap fit, friction or rotational locking. No deformation simulation. Progress/cancellation via assembly job tools.",pathProperties(true),listOf("bodyId","obstacleIds")),
    CadTool("cad_check_manufacturing","Start configurable sampled mesh-thickness and clearance screening. Flags thin sections and small-feature candidates with positions; does not certify manufacturing or strength. Up to 8 bodies, 20000 triangles/body. Use cad_highlight_manufacturing to show warnings. Progress/cancellation via assembly job tools.",buildJsonObject {
        put("bodyIds",strings("1..8 selected body IDs."));put("minWallMm",field("number",".01..100, default .8."));put("minFeatureMm",field("number",".01..100, default 1.2; candidate fragility threshold."));put("minClearanceMm",field("number",".00001..10, default .2; includes contact/overlap which may be intentional."));put("samplesPerBody",field("integer","8..512, default 128; bounded sampling can miss features."))
    },listOf("bodyIds")),
    CadTool("cad_highlight_manufacturing","Show red viewport markers for the completed manufacturing job's thickness warnings. Rejects changed geometry or placement. No geometry/history changes. Clear with cad_clear_assembly_preview.",buildJsonObject { put("jobId",field("string","Completed manufacturing job ID.")) },listOf("jobId")),
    CadTool("cad_get_assembly","Read part counts grouped by linked source definition, instances, visible counts and stable IDs. Includes the source body in counts; independent identical bodies are separate definitions. No mutation.",buildJsonObject {}),
    CadTool("cad_compare_assembly","Compare current project with a saved .kcad revision by stable IDs, definitions, parameters, colors and evaluated geometry/bounds. Read-only asynchronous job; does not open or overwrite the saved file.",buildJsonObject { put("path",field("string","Absolute path of saved .kcad revision.")) },listOf("path")),
    CadTool("cad_preview_exploded","Show a transient radial exploded view preserving colors and saved assembly positions/history. Returns per-body offsets; illustrative only, not insertion/removal paths. Snapshot with cad_get_preview; clear with cad_clear_assembly_preview. Model picking is disabled during this preview.",buildJsonObject { put("distanceMm",field("number","Separation distance 0..10000 mm, default 20.")) }),
    CadTool("cad_clear_assembly_preview","Restore assembly view/camera and remove analysis markers, without changing document or history.",buildJsonObject {})
)
