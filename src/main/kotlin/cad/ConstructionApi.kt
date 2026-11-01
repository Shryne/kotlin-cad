package cad

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.*

internal fun Vec3.xyzJson()=buildJsonObject { put("x",x);put("y",y);put("z",z) }
internal fun Vec3.arrayJson()=JsonArray(listOf(x,y,z).map(::JsonPrimitive))
internal fun translatedShape(shape:JsonObject,p:Vec3)=buildJsonObject { put("type","translate");put("shape",shape);put("offset",p.arrayJson()) }
internal fun DocumentState.withConstruction(feature:NativeShapeFeature,additions:List<Parameter>,update:Boolean):DocumentState {
    require(additions.size<=64 && additions.map { it.name }.distinct().size==additions.size) { "Höchstens 64 eindeutige neue Parameter." }
    require(additions.none { p -> parameters.any { it.name==p.name } }) { "Parameter existiert bereits; verwende cad_set_parameter zum Ändern." }
    val previous=effectiveFeatures().firstOrNull { it.id==feature.id }
    require(if(update) previous is NativeShapeFeature else previous==null) { "Konstruktionsfeature fehlt oder ID ist bereits vergeben." }
    val next=copy(parameters=parameters+additions,features=if(update) effectiveFeatures().map { if(it.id==feature.id) feature else it } else effectiveFeatures()+feature)
    next.canonicalFeatures().bodyMeshes() // All dependent bodies must succeed before commit.
    return next
}
internal fun DocumentState.withoutFeature(id:String):DocumentState {
    require(id!="import" && effectiveFeatures().any { it.id==id }) { "Wähle eine vorhandene generierte Feature-ID; Import über cad_delete_import entfernen." }
    val next=canonicalFeatures().let { it.copy(bodyColors=it.bodyColors-id,features=it.features.filterNot { f -> f.id==id },display=it.display.copy(hiddenBodyIds=it.display.hiddenBodyIds-id)) }
    try { next.featureOrder();next.bodyMeshes() } catch(e:Exception) { throw IllegalArgumentException("Feature '$id' wird noch referenziert oder das verbleibende Modell ist ungültig. Abhängige Features/Skizzen zuerst ändern: ${e.message}",e) }
    return next
}
internal fun DocumentState.nativeBodyShape(id:String):JsonObject? {
    val values=ParameterEvaluator.evaluate(parameters)
    val feature=effectiveFeatures().firstOrNull { it.id==id } ?: return null
    val shape=when(feature) {
        is NativeShapeFeature -> resolveNativeShape(feature.shape,values)
        is LoftFeature -> feature.shape(values)
        is InstanceFeature -> {
            featureOrder()
            val source=effectiveFeatures().first { it.id==feature.sourceFeatureId }
            val sourceWorld=nativeBodyShape(source.id) ?: return null
            rotatedShape(translatedShape(sourceWorld,source.position(values)*(-1.0)),feature.angles(values))
        }
        is PrimitiveFeature -> {
            fun length(s:String):Double { val q=ParameterEvaluator.expression(s,values);require(q.dimension==Dimension(1));return q.value }
            buildJsonObject { when(feature.kind) {
                PrimitiveKind.BOX -> { put("type","box");put("x",length(feature.width));put("y",length(feature.depth));put("z",length(feature.height)) }
                PrimitiveKind.CYLINDER -> { put("type","cylinder");put("radius",length(feature.radius));put("height",length(feature.height)) }
                PrimitiveKind.SPHERE -> { put("type","sphere");put("radius",length(feature.radius)) }
            } }
        }
        else -> return null // Existing sketch/face/mirror bodies are checked as their closed faceted geometry.
    }
    return translatedShape(shape,feature.position(values))
}
internal fun DocumentState.constructionInfo(id:String):JsonObject {
    val feature=effectiveFeatures().filterIsInstance<NativeShapeFeature>().firstOrNull { it.id==id } ?: error("Konstruktionsfeature fehlt.")
    val info=CadNativeGeometry.kernel.request("solid_info",buildJsonObject { put("shape",nativeBodyShape(id)!!);put("singleSolid",feature.singleSolid) }).get(60,TimeUnit.SECONDS)
    return JsonObject(info+mapOf("featureId" to JsonPrimitive(id),"bodyId" to JsonPrimitive(id),"feature" to feature.json()))
}
internal fun DocumentState.checkInterference(ids:List<String>,contactTolerance:Double,volumeTolerance:Double):JsonObject {
    require(ids.size in 2..8 && ids.distinct().size==ids.size) { "Wähle 2 bis 8 unterschiedliche Körper." }
    require(contactTolerance in 1e-6..1.0 && volumeTolerance in 1e-6..1.0) { "Toleranzen müssen zwischen 0,000001 und 1 liegen." }
    val bodies=sceneBodies().associateBy { it.id }
    val inputs=ids.associateWith { id ->
        val body=bodies[id] ?: error("Körper '$id' fehlt.")
        nativeBodyShape(id)?.let { buildJsonObject { put("shape",it) } } ?: buildJsonObject {
            require(body.mesh.triangleCount<=10000) { "Facettierter Körper '$id' überschreitet 10000 Dreiecke. Für exakte komplexe Prüfungen native Konstruktion verwenden." }
            put("mesh",buildJsonObject { put("vertices",JsonArray(body.mesh.vertices.map { it.arrayJson() }));put("indices",JsonArray(body.mesh.indices.map(::JsonPrimitive))) })
        }
    }
    val pairs=ids.indices.flatMap { i -> (i+1 until ids.size).map { j ->
        AssemblyWork.check()
        val result=CadNativeGeometry.kernel.request("interference",buildJsonObject { put("a",inputs.getValue(ids[i]));put("b",inputs.getValue(ids[j]));put("contactToleranceMm",contactTolerance);put("volumeToleranceMm3",volumeTolerance) }).get(60,TimeUnit.SECONDS)
        JsonObject(result+mapOf("bodyA" to JsonPrimitive(ids[i]),"bodyB" to JsonPrimitive(ids[j])))
    } }
    return buildJsonObject { put("pairs",JsonArray(pairs));put("modelChanged",false) }
}
/** Transforms export meshes only, around a supplied world origin. */
internal fun DocumentState.exportParts(ids:List<String>,orientation:JsonObject):List<MeshData> {
    require(ids.size in 1..64 && ids.distinct().size==ids.size) { "Wähle 1 bis 64 unterschiedliche Körper." }
    require(orientation.keys.all { it in setOf("rotationX","rotationY","rotationZ","originX","originY","originZ","x","y","z","placeOnBed") }) { "Unbekanntes Feld in der Exportorientierung." }
    val values=ParameterEvaluator.evaluate(parameters)
    fun quantity(key:String,angle:Boolean=false):Double {
        val s=orientation[key]?.jsonPrimitive?.content ?: if(angle) "0 deg" else "0 mm"
        val q=ParameterEvaluator.expression(s,values)
        require(q.dimension==(if(angle) Dimension(angle=1) else Dimension(1)) && q.value.isFinite() && abs(q.value)<=1e9) { "Exportorientierung '$key' benötigt eine gültige ${if(angle) "Winkelformel" else "Längenformel"}." };return q.value
    }
    val a=quantity("rotationX",true);val b=quantity("rotationY",true);val c=quantity("rotationZ",true)
    val origin=Vec3(quantity("originX"),quantity("originY"),quantity("originZ"));val translation=Vec3(quantity("x"),quantity("y"),quantity("z"))
    fun transform(v:Vec3):Vec3 { val d=v-origin;val p=Vec3(d.x,cos(a)*d.y-sin(a)*d.z,sin(a)*d.y+cos(a)*d.z);val q=Vec3(cos(b)*p.x+sin(b)*p.z,p.y,-sin(b)*p.x+cos(b)*p.z);return Vec3(cos(c)*q.x-sin(c)*q.y,sin(c)*q.x+cos(c)*q.y,q.z)+origin+translation }
    val bodies=sceneBodies().associateBy { it.id }
    val rotated=ids.map { id -> val m=bodies[id]?.mesh ?: error("Körper '$id' fehlt.");m.copy(vertices=m.vertices.map(::transform)) }
    return if(orientation.optionalBoolean("placeOnBed")==true) { val min=rotated.minOf { it.bounds.low.z };rotated.map { it.translated(Vec3(0.0,0.0,-min)) } } else rotated
}
internal fun exportedPartsInfo(result:MeshExportResult,ids:List<String>,parts:List<MeshData>)=buildJsonObject {
    put("path",result.path.toString());put("format",result.format.name);put("bodyIds",JsonArray(ids.map(::JsonPrimitive)));put("modelChanged",false)
    put("bodies",JsonArray(ids.zip(parts).map { (id,m)->buildJsonObject { put("bodyId",id);put("bounds",buildJsonObject { put("min",m.bounds.low.xyzJson());put("max",m.bounds.high.xyzJson()) });put("dimensions",m.bounds.size.xyzJson()) } }))
}
