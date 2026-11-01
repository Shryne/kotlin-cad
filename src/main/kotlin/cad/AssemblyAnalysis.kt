package cad

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.*

private fun DocumentState.analysisInput(id:String,offset:Vec3=Vec3(0.0,0.0,0.0)):JsonObject {
    nativeBodyShape(id)?.let { return buildJsonObject { put("shape",translatedShape(it,offset)) } }
    val mesh=sceneBodies().firstOrNull { it.id==id }?.mesh ?: error("Körper '$id' fehlt.")
    require(mesh.triangleCount<=10000) { "Facettierter Körper '$id' ist zu komplex für die Prüfung (max. 10000 Dreiecke)." }
    return buildJsonObject { put("mesh",buildJsonObject { put("vertices",JsonArray(mesh.vertices.map { (it+offset).arrayJson() }));put("indices",JsonArray(mesh.indices.map(::JsonPrimitive))) }) }
}
private fun interference(a:JsonObject,b:JsonObject)=CadNativeGeometry.kernel.request("interference",buildJsonObject { put("a",a);put("b",b);put("contactToleranceMm",.00001);put("volumeToleranceMm3",.000001) }).get(60,TimeUnit.SECONDS)
internal fun DocumentState.assemblyAnalysis(kind:String,args:JsonObject):JsonObject=when(kind) {
    "path" -> analyzePath(args)
    "retention" -> {
        val travel=args.number("travelMm",20.0,.01,10000.0)
        val probes=listOf(Vec3(travel,0.0,0.0),Vec3(-travel,0.0,0.0),Vec3(0.0,travel,0.0),Vec3(0.0,-travel,0.0),Vec3(0.0,0.0,travel),Vec3(0.0,0.0,-travel)).map { end ->
            AssemblyWork.check();analyzePath(JsonObject(args+mapOf("waypoints" to JsonArray(listOf(Vec3(0.0,0.0,0.0).xyzJson(),end.xyzJson())))))
        }
        buildJsonObject { put("probes",JsonArray(probes));put("geometricallyBlockedDirections",probes.count { it["status"]?.jsonPrimitive?.content=="blocked" });put("assessment","directional-screening-only");put("limitations","Six straight translation probes. Does not prove mechanical retention, strength, snap-fit elasticity, friction, rotational escape or assembly feasibility.");put("modelChanged",false) }
    }
    "manufacturing" -> analyzeManufacturing(args)
    "compare" -> { val path=Path.of(args.requiredString("path"));require(path.isAbsolute);compareAssembly(ProjectIO.read(path).document) }
    else -> error("Unbekannte Baugruppenprüfung.")
}
private fun DocumentState.analyzePath(args:JsonObject):JsonObject {
    val moving=args.requiredString("bodyId");val fixed=args.getValue("obstacleIds").jsonArray.map { it.jsonPrimitive.content }
    require(fixed.size in 1..16 && fixed.distinct().size==fixed.size && moving !in fixed) { "1 bis 16 unterschiedliche Hindernisse ohne den bewegten Körper wählen." }
    val waypoints=args.getValue("waypoints").jsonArray.map { it.jsonObject.colorPoint() }
    require(waypoints.size in 2..32 && waypoints.all { p -> listOf(p.x,p.y,p.z).all { it.isFinite() && abs(it)<=1e6 } })
    val step=args.number("stepMm",1.0,.01,1000.0)
    val samples=mutableListOf(waypoints.first())
    waypoints.zipWithNext().forEach { (a,b) -> val count=ceil((b-a).norm()/step).toInt().coerceAtLeast(1);require(samples.size+count<=201) { "Pfad benötigt mehr als 201 Proben. stepMm erhöhen oder kürzeren Pfad prüfen." };for(i in 1..count) samples.add(a+(b-a)*(i.toDouble()/count)) }
    require(samples.size*fixed.size<=256) { "Maximal 256 Körperpaar-Proben pro Pfad. Hindernisse reduzieren oder stepMm erhöhen." }
    val obstacles=fixed.associateWith { analysisInput(it) };val reports=mutableListOf<JsonObject>();var certified=true;var previousDistance:Double?=null
    for((index,offset) in samples.withIndex()) {
        AssemblyWork.check();val input=analysisInput(moving,offset)
        val pairs=obstacles.map { (id,other) -> AssemblyWork.check();JsonObject(interference(input,other)+mapOf("obstacleId" to JsonPrimitive(id))) }
        val gap=pairs.minOf { it.getValue("distanceMm").jsonPrimitive.double }
        if(index>0 && minOf(previousDistance!!,gap)<=(samples[index]-samples[index-1]).norm()/2+.00001) certified=false
        previousDistance=gap
        val blocked=pairs.any { it.getValue("status").jsonPrimitive.content=="overlap" }
        reports.add(buildJsonObject { put("sample",index);put("offset",offset.xyzJson());put("pairs",JsonArray(pairs));put("blocked",blocked) })
        AssemblyWork.step("Pfadprobe ${index+1}/${samples.size}")
        if(blocked) { certified=false;break }
    }
    val blocked=reports.any { it.getValue("blocked").jsonPrimitive.boolean }
    return buildJsonObject {
        put("bodyId",moving);put("status",if(blocked) "blocked" else if(certified) "clear" else "inconclusive")
        put("samples",JsonArray(reports));put("continuousClearanceCertified",!blocked && certified);put("modelChanged",false)
        put("method","Rigid translation samples plus distance/Lipschitz bound between samples. No rotations or deformation. Offsets are relative to saved assembly placement.")
        if(!blocked && !certified) put("recommendation","No sampled overlap, but intermediate motion is not certified. Reduce stepMm or revise the path; contact is not proof of an unobstructed insertion/removal.")
    }
}
private fun rayHit(origin:Vec3,direction:Vec3,a:Vec3,b:Vec3,c:Vec3):Double? {
    val e1=b-a;val e2=c-a;val p=direction.cross(e2);val determinant=e1.dot(p)
    if(abs(determinant)<1e-12) return null
    val inv=1/determinant;val delta=origin-a;val u=delta.dot(p)*inv;if(u<0 || u>1) return null
    val q=delta.cross(e1);val v=direction.dot(q)*inv;if(v<0 || u+v>1) return null
    return (e2.dot(q)*inv).takeIf { it>1e-6 }
}
private fun DocumentState.analyzeManufacturing(args:JsonObject):JsonObject {
    val ids=args.getValue("bodyIds").jsonArray.map { it.jsonPrimitive.content };require(ids.size in 1..8 && ids.distinct().size==ids.size)
    val wall=args.number("minWallMm",.8,.01,100.0);val fragile=args.number("minFeatureMm",1.2,.01,100.0);val clearance=args.number("minClearanceMm",.2,.00001,10.0)
    val samples=args["samplesPerBody"]?.jsonPrimitive?.int ?: 128;require(samples in 8..512)
    val bodies=sceneBodies().associateBy { it.id };val warnings=mutableListOf<JsonObject>();var tested=0;var missed=0
    for(id in ids) {
        val mesh=bodies[id]?.mesh ?: error("Körper '$id' fehlt.");require(mesh.triangleCount<=20000) { "Für Dickenprüfung max. 20000 Dreiecke pro Körper; gröbere Vorschau verwenden." }
        val sourceRevision=colorRevision(mesh)
        val stride=maxOf(1,ceil(mesh.triangleCount.toDouble()/samples).toInt())
        for(t in 0 until mesh.triangleCount step stride) {
            AssemblyWork.check();tested++
            val a=mesh.vertices[mesh.indices[t*3]];val b=mesh.vertices[mesh.indices[t*3+1]];val c=mesh.vertices[mesh.indices[t*3+2]];val cross=(b-a).cross(c-a)
            if(cross.norm()<1e-12) { missed++;continue }
            val p=(a+b+c)*(1.0/3);val direction=cross.normalized()*(-1.0)
            val hits=(0 until mesh.triangleCount).asSequence().filter { it!=t }.mapNotNull { j -> rayHit(p,direction,mesh.vertices[mesh.indices[j*3]],mesh.vertices[mesh.indices[j*3+1]],mesh.vertices[mesh.indices[j*3+2]]) }
            val thickness=hits.minOrNull();if(thickness==null) { missed++;continue }
            if(thickness<maxOf(wall,fragile)) warnings.add(buildJsonObject {
                put("bodyId",id);put("triangle",t);put("sourceRevision",sourceRevision);put("point",p.xyzJson());put("thicknessMm",thickness)
                put("kind",if(thickness<wall) "thin-section" else "small-feature-candidate");put("thresholdMm",if(thickness<wall) wall else fragile)
            })
        }
        AssemblyWork.step("Dickenprüfung: $id")
    }
    val gaps=if(ids.size>1) checkInterference(ids,.00001,.000001).getValue("pairs").jsonArray.filter { p -> p.jsonObject.getValue("distanceMm").jsonPrimitive.double<clearance } else emptyList()
    return buildJsonObject { put("warnings",JsonArray(warnings));put("smallClearances",JsonArray(gaps));put("samplesTested",tested);put("unresolvedSamples",missed);put("minWallMm",wall);put("minFeatureMm",fragile);put("minClearanceMm",clearance);put("modelChanged",false);put("assessment","screening-only");put("limitations","Sampled inward-normal mesh thickness; may miss narrow features, undercuts and oblique thin walls. Small features are candidates, not a strength prediction. No material, layer adhesion, load or print-process simulation. Contact/overlap may be intentional; review clearances in context.") }
}
internal fun DocumentState.assemblyReport():JsonObject {
    val features=effectiveFeatures().associateBy { it.id };featureOrder()
    fun definition(id:String):String { val f=features[id];return if(f is InstanceFeature) definition(f.sourceFeatureId) else id }
    val bodies=sceneBodies();val groups=bodies.groupBy { definition(it.id) }
    return buildJsonObject {
        put("bodyCount",bodies.size);put("definitionCount",groups.size)
        put("parts",JsonArray(groups.map { (source,parts) -> buildJsonObject { put("definitionId",source);put("name",parts.firstOrNull { it.id==source }?.mesh?.name ?: source);put("count",parts.size);put("visibleCount",parts.count { it.visible });put("bodyIds",JsonArray(parts.map { JsonPrimitive(it.id) })) } }))
        put("instances",JsonArray(features.values.filterIsInstance<InstanceFeature>().map { it.json() }));put("modelChanged",false)
    }
}
internal fun DocumentState.compareAssembly(previous:DocumentState):JsonObject {
    val old=previous.effectiveFeatures().associateBy { it.id };val current=effectiveFeatures().associateBy { it.id }
    return buildJsonObject {
        put("added",JsonArray((current.keys-old.keys).map(::JsonPrimitive)));put("removed",JsonArray((old.keys-current.keys).map(::JsonPrimitive)))
        put("changed",JsonArray((old.keys intersect current.keys).filter { old[it]!=current[it] || previous.bodyColors[it]!=bodyColors[it] }.map { id -> buildJsonObject { put("bodyId",id);put("before",old.getValue(id).json());put("after",current.getValue(id).json());put("appearanceChanged",previous.bodyColors[id]!=bodyColors[id]) } }))
        put("parametersBefore",JsonArray(previous.parameters.map { buildJsonObject { put("name",it.name);put("expression",it.expression) } }));put("parametersAfter",JsonArray(parameters.map { buildJsonObject { put("name",it.name);put("expression",it.expression) } }))
        val oldBounds=previous.sceneBodies().associate { it.id to it.mesh.bounds };val newBounds=sceneBodies().associate { it.id to it.mesh.bounds }
        put("geometryChangedIds",JsonArray((oldBounds.keys intersect newBounds.keys).filter { id -> colorRevision(previous.sceneBodies().first { it.id==id }.mesh)!=colorRevision(sceneBodies().first { it.id==id }.mesh) }.map(::JsonPrimitive)))
        put("boundsChanges",JsonArray((oldBounds.keys intersect newBounds.keys).filter { oldBounds[it]!=newBounds[it] }.map { id -> buildJsonObject { put("bodyId",id);put("beforeMin",oldBounds.getValue(id).low.xyzJson());put("beforeMax",oldBounds.getValue(id).high.xyzJson());put("afterMin",newBounds.getValue(id).low.xyzJson());put("afterMax",newBounds.getValue(id).high.xyzJson()) } }))
        put("modelChanged",false);put("comparison","Stable IDs, feature definitions, parameter definitions, explicit colors and evaluated bounds. Equal bounds do not imply equal geometry.")
    }
}
internal fun DocumentState.explodedMeshes(distance:Double):Pair<List<MeshData>,JsonObject> {
    require(distance.isFinite() && distance in 0.0..10000.0)
    val bodies=sceneBodies().filter { it.visible };require(bodies.isNotEmpty())
    val center=bodies.map { it.mesh.bounds.center }.reduce { a,b->a+b }*(1.0/bodies.size)
    val offsets=bodies.mapIndexed { index,body -> val delta=body.mesh.bounds.center-center;val direction=if(delta.norm()>1e-9) delta.normalized() else Vec3(if(index%2==0) 1.0 else -1.0,0.0,0.0);direction*distance }
    return bodies.zip(offsets).map { (body,offset)->body.mesh.translated(offset) } to buildJsonObject { put("placements",JsonArray(bodies.zip(offsets).map { (body,offset)->buildJsonObject { put("bodyId",body.id);put("offset",offset.xyzJson()) } }));put("modelChanged",false);put("meaning","Illustrative radial separation only; not a validated assembly/disassembly path.") }
}
internal fun combineMeshes(parts:List<MeshData>):MeshData {
    require(parts.isNotEmpty());val vertices=ArrayList<Vec3>();val indices=ArrayList<Int>()
    parts.forEach { val start=vertices.size;vertices.addAll(it.vertices);it.indices.forEach { i->indices.add(start+i) } }
    return MeshData("Assembly preview",vertices,indices.toIntArray())
}
