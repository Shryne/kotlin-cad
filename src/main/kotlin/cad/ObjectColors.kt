package cad

import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.math.round

const val DEFAULT_BODY_COLOR = "#A1AFBE"
internal fun checkedColor(value:String):String {
    require(Regex("#[0-9a-fA-F]{6}").matches(value)) { "Farbe als #RRGGBB angeben, z. B. #E67E22." }
    return value.uppercase()
}
/** Surface selections deliberately expire after geometry changes; spatial zones are re-evaluated. */
data class ColorArea(val id:String,val name:String,val color:String,val triangles:List<Int> = emptyList(),val sourceRevision:String?=null,val low:Vec3?=null,val high:Vec3?=null) {
    init {
        require(id.isNotBlank() && id.length<=128 && name.isNotBlank() && name.length<=128)
        checkedColor(color)
        require(triangles.size<=100000 && triangles.distinct().size==triangles.size && triangles.all { it>=0 })
        require((low!=null && high!=null && triangles.isEmpty() && sourceRevision==null) || (low==null && high==null && triangles.isNotEmpty() && sourceRevision!=null)) { "Bereich benötigt entweder lokale min/max-Grenzen oder aktuelle Flächenauswahl." }
        if(low!=null && high!=null) require(listOf(low.x,low.y,low.z,high.x,high.y,high.z).all { it.isFinite() && kotlin.math.abs(it)<=1e9 } && low.x<=high.x && low.y<=high.y && low.z<=high.z) { "Ungültige Bereichsgrenzen." }
    }
    fun json()=buildJsonObject {
        put("id",id);put("name",name);put("color",color)
        if(low!=null) { put("min",low.xyzJson());put("max",high!!.xyzJson()) }
        else { put("triangles",JsonArray(triangles.map(::JsonPrimitive)));put("sourceRevision",sourceRevision) }
    }
    companion object { fun read(j:JsonObject)=ColorArea(j.requiredString("id"),j.requiredString("name"),checkedColor(j.requiredString("color")),j["triangles"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),j["sourceRevision"]?.jsonPrimitive?.content,j["min"]?.jsonObject?.colorPoint(),j["max"]?.jsonObject?.colorPoint()) }
}
data class BodyColors(val color:String?=null,val areas:List<ColorArea> = emptyList()) {
    init { color?.let(::checkedColor);require(areas.size<=128 && areas.map { it.id }.distinct().size==areas.size) { "Maximal 128 eindeutig benannte Farbbereiche je Körper." } }
    fun json()=buildJsonObject { put("color",color?.let(::JsonPrimitive) ?: JsonNull);put("areas",JsonArray(areas.map { it.json() })) }
    companion object { fun read(j:JsonObject)=BodyColors(j["color"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content?.let(::checkedColor),j["areas"]?.jsonArray?.map { ColorArea.read(it.jsonObject) } ?: emptyList()) }
}
internal fun JsonObject.colorPoint()=Vec3(getValue("x").jsonPrimitive.double,getValue("y").jsonPrimitive.double,getValue("z").jsonPrimitive.double)
internal fun DocumentState.colorMesh(body:SceneBody):MeshData {
    val offset=effectiveFeatures().firstOrNull { it.id==body.id }?.position(ParameterEvaluator.evaluate(parameters)) ?: Vec3(0.0,0.0,0.0)
    return body.mesh.translated(offset*(-1.0))
}
internal fun colorRevision(mesh:MeshData):String {
    val digest=MessageDigest.getInstance("SHA-256")
    // Ignore names and body placement; round away floating-point translation noise.
    mesh.vertices.forEach { p -> listOf(p.x,p.y,p.z).forEach { digest.update("${round(it*1e7).toLong()},".toByteArray()) } }
    mesh.indices.forEach { digest.update("$it,".toByteArray()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun MeshData.colorPatches():List<List<Int>> = (0 until triangleCount).groupBy { t ->
    val a=vertices[indices[t*3]];val b=vertices[indices[t*3+1]];val c=vertices[indices[t*3+2]]
    val cross=(b-a).cross(c-a);val n=if(cross.norm()<1e-12) Vec3(0.0,0.0,0.0) else cross.normalized()
    listOf(n.x,n.y,n.z,n.dot(a)).map { round(it*1e6).toLong() }
}.values.toList()
internal fun DocumentState.colorInfo(body:SceneBody):JsonObject=buildJsonObject {
    val style=bodyColors[body.id] ?: BodyColors();val mesh=colorMesh(body);val revision=if(style.areas.any { it.sourceRevision!=null }) colorRevision(mesh) else null
    style.json().forEach { (key,value)->put(key,value) }
    val instance=effectiveFeatures().filterIsInstance<InstanceFeature>().firstOrNull { it.id==body.id && it.inheritColors }
    val source=instance?.let { i -> sceneBodies().firstOrNull { it.id==i.sourceFeatureId } }
    put("effectiveColor",style.color ?: source?.let { colorInfo(it)["effectiveColor"]?.jsonPrimitive?.content } ?: DEFAULT_BODY_COLOR)
    source?.let { put("inheritedFrom",it.id);put("inheritedAppearance",colorInfo(it)) }
    put("staleAreaIds",JsonArray(style.areas.filter { it.sourceRevision!=null && it.sourceRevision!=revision }.map { JsonPrimitive(it.id) }))
}
internal fun DocumentState.withBodyColor(id:String,color:String?):DocumentState {
    require(sceneBodies().any { it.id==id }) { "Körper existiert nicht." }
    return copy(bodyColors=bodyColors+(id to (bodyColors[id] ?: BodyColors()).copy(color=color?.let(::checkedColor))))
}
internal fun DocumentState.withColorArea(id:String,area:ColorArea):DocumentState {
    val body=sceneBodies().firstOrNull { it.id==id } ?: error("Körper existiert nicht.")
    val mesh=colorMesh(body)
    if(area.sourceRevision!=null) require(area.sourceRevision==colorRevision(mesh) && area.triangles.all { it<mesh.triangleCount }) { "Die Geometrie hat sich geändert. cad_get_color_regions erneut lesen und den Bereich neu auswählen." }
    val old=bodyColors[id] ?: BodyColors()
    return copy(bodyColors=bodyColors+(id to old.copy(areas=old.areas.filterNot { it.id==area.id }+area)))
}
internal fun DocumentState.withoutColorArea(id:String,areaId:String):DocumentState {
    require(sceneBodies().any { it.id==id }) { "Körper existiert nicht." }
    val style=bodyColors[id] ?: error("Kein Farbbereich vorhanden.")
    require(style.areas.any { it.id==areaId }) { "Farbbereich existiert nicht." }
    return copy(bodyColors=bodyColors+(id to style.copy(areas=style.areas.filterNot { it.id==areaId })))
}
internal fun DocumentState.renderColors():List<String> {
    val bodies=sceneBodies().associateBy { it.id };val instances=effectiveFeatures().filterIsInstance<InstanceFeature>().associateBy { it.id }
    val memo=mutableMapOf<String,List<String>>()
    fun colorsFor(body:SceneBody):List<String> {
    memo[body.id]?.let { return it }
    val style=bodyColors[body.id] ?: BodyColors();val mesh=colorMesh(body)
    val source=instances[body.id]?.takeIf { it.inheritColors }?.let { bodies.getValue(it.sourceFeatureId) }
    val colors=if(style.color==null && source!=null) colorsFor(source).toMutableList() else MutableList(mesh.triangleCount) { style.color ?: DEFAULT_BODY_COLOR }
    val revision=if(style.areas.any { it.sourceRevision!=null }) colorRevision(mesh) else null
    style.areas.forEach { area ->
        if(area.sourceRevision!=null) {
            if(area.sourceRevision==revision) area.triangles.filter { it<colors.size }.forEach { colors[it]=area.color }
        } else for(t in colors.indices) {
            val p=(mesh.vertices[mesh.indices[t*3]]+mesh.vertices[mesh.indices[t*3+1]]+mesh.vertices[mesh.indices[t*3+2]])*(1.0/3)
            val a=area.low!!;val b=area.high!!
            if(p.x in a.x..b.x && p.y in a.y..b.y && p.z in a.z..b.z) colors[t]=area.color
        }
    }
    memo[body.id]=colors;return colors
    }
    return bodies.values.filter { it.visible }.flatMap(::colorsFor)
}

internal fun DocumentState.colorRegions(id:String,offset:Int=0,limit:Int=100):JsonObject {
    require(offset>=0 && limit in 1..200)
    val body=sceneBodies().firstOrNull { it.id==id } ?: error("Körper existiert nicht.")
    val mesh=colorMesh(body);val patches=mesh.colorPatches()
    return buildJsonObject {
        put("bodyId",id);put("sourceRevision",colorRevision(mesh));put("appearance",colorInfo(body));put("total",patches.size)
        put("coordinateSpace","body-local millimeters, before feature position translation")
        put("regions",JsonArray(patches.drop(offset).take(limit).mapIndexed { index,triangles -> buildJsonObject {
            val points=triangles.flatMap { t -> (0..2).map { mesh.vertices[mesh.indices[t*3+it]] } }
            val t=triangles.first();val a=mesh.vertices[mesh.indices[t*3]];val b=mesh.vertices[mesh.indices[t*3+1]];val c=mesh.vertices[mesh.indices[t*3+2]];val cross=(b-a).cross(c-a)
            put("id","patch-${offset+index}");put("triangleCount",triangles.size)
            put("normal",(if(cross.norm()<1e-12) Vec3(0.0,0.0,0.0) else cross.normalized()).xyzJson())
            put("min",Vec3(points.minOf { it.x },points.minOf { it.y },points.minOf { it.z }).xyzJson())
            put("max",Vec3(points.maxOf { it.x },points.maxOf { it.y },points.maxOf { it.z }).xyzJson())
        } }))
        if(offset+limit<patches.size) put("nextOffset",offset+limit)
    }
}
internal fun DocumentState.colorAreaFrom(j:JsonObject):ColorArea {
    val id=j.requiredString("bodyId");val body=sceneBodies().firstOrNull { it.id==id } ?: error("Körper existiert nicht.")
    val patches=j["regionIds"]?.jsonArray
    require((patches!=null) != (j["min"]!=null || j["max"]!=null)) { "Entweder regionIds oder min/max angeben." }
    val triangles=if(patches!=null) {
        require(j.requiredString("sourceRevision")==colorRevision(colorMesh(body))) { "Flächen veraltet. cad_get_color_regions erneut lesen." }
        val regions=colorMesh(body).colorPatches()
        require(patches.isNotEmpty() && patches.size<=1000)
        patches.flatMap { value -> val key=value.jsonPrimitive.content;require(key.startsWith("patch-"));val index=key.removePrefix("patch-").toIntOrNull() ?: error("Ungültige Flächen-ID.");regions.getOrNull(index) ?: error("Fläche existiert nicht.") }.distinct()
    } else emptyList()
    return ColorArea(j.requiredString("areaId"),j.requiredString("name"),checkedColor(j.requiredString("color")),triangles,if(patches!=null) j.requiredString("sourceRevision") else null,j["min"]?.jsonObject?.colorPoint(),j["max"]?.jsonObject?.colorPoint())
}
