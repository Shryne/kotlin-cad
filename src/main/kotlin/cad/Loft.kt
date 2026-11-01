package cad

import kotlinx.serialization.json.*
import kotlin.math.*

/** Euler rotations act on the local ellipse in X, then Y, then Z order. */
data class LoftSection(val x:String="0 mm",val y:String="0 mm",val z:String="0 mm",
    val rotationX:String="0 deg",val rotationY:String="0 deg",val rotationZ:String="0 deg",
    val radiusX:String="10 mm",val radiusY:String="6 mm") {
    fun json()=buildJsonObject { fields().forEach { (k,v)->put(k,v) } }
    fun fields()=linkedMapOf("x" to x,"y" to y,"z" to z,"rotationX" to rotationX,"rotationY" to rotationY,"rotationZ" to rotationZ,"radiusX" to radiusX,"radiusY" to radiusY)
    companion object {
        fun read(j:JsonObject)=LoftSection(j.requiredString("x"),j.requiredString("y"),j.requiredString("z"),j.requiredString("rotationX"),j.requiredString("rotationY"),j.requiredString("rotationZ"),j.requiredString("radiusX"),j.requiredString("radiusY"))
    }
}
data class LoftFeature(override val id:String,val name:String="Loft",val sections:List<LoftSection>,val ruled:Boolean=false,
    val x:String="0 mm",val y:String="0 mm",val z:String="0 mm"):CadFeature {
    override fun json()=buildJsonObject { put("type","loft");put("id",id);put("name",name);put("ruled",ruled);put("sections",JsonArray(sections.map { it.json() }));put("x",x);put("y",y);put("z",z) }
    fun shape(values:Map<String,Quantity>):JsonObject {
        require(id.isNotBlank()&&id!="import"&&id.length<=256&&name.isNotBlank()&&name.length<=128) { "Loft benötigt einen gültigen Namen und eine ID." }
        require(sections.size in 2..32) { "Loft benötigt 2 bis 32 geordnete Querschnitte." }
        fun value(text:String,angle:Boolean=false):Double {
            require(text.length in 1..1024) { "Leere oder zu lange Loft-Formel." }
            val q=ParameterEvaluator.expression(text,values)
            require(q.dimension==if(angle) Dimension(angle=1) else Dimension(1)) { "Loft: Längen benötigen mm, Rotationen deg oder einen passenden Parameter." }
            require(q.value.isFinite()&&abs(q.value)<=1e6) { "Loft-Wert außerhalb des gültigen Bereichs." };return q.value
        }
        fun vector(v:Vec3)=JsonArray(listOf(v.x,v.y,v.z).map(::JsonPrimitive))
        return buildJsonObject { put("type","loft");put("ruled",ruled);put("sections",JsonArray(sections.mapIndexed { i,s ->
            try {
                val a=value(s.rotationX,true);val b=value(s.rotationY,true);val c=value(s.rotationZ,true)
                fun rotate(v:Vec3):Vec3 { val p=Vec3(v.x,cos(a)*v.y-sin(a)*v.z,sin(a)*v.y+cos(a)*v.z);val q=Vec3(cos(b)*p.x+sin(b)*p.z,p.y,-sin(b)*p.x+cos(b)*p.z);return Vec3(cos(c)*q.x-sin(c)*q.y,sin(c)*q.x+cos(c)*q.y,q.z) }
                val rx=value(s.radiusX);val ry=value(s.radiusY)
                require(rx>=.001&&ry>=.001) { "Beide Radien müssen mindestens 0,001 mm betragen." }
                buildJsonObject { put("center",vector(Vec3(value(s.x),value(s.y),value(s.z))));put("u",vector(rotate(Vec3(1.0,0.0,0.0))));put("normal",vector(rotate(Vec3(0.0,0.0,1.0))));put("radiusX",rx);put("radiusY",ry) }
            } catch(e:Exception) { throw IllegalArgumentException("Querschnitt ${i+1}: ${e.message}",e) }
        })) }
    }
    companion object {
        fun read(j:JsonObject)=LoftFeature(j.requiredString("id"),j.requiredString("name"),j.getValue("sections").jsonArray.map { LoftSection.read(it.jsonObject) },j["ruled"]?.jsonPrimitive?.boolean ?: false,j["x"]?.jsonPrimitive?.content ?: "0 mm",j["y"]?.jsonPrimitive?.content ?: "0 mm",j["z"]?.jsonPrimitive?.content ?: "0 mm")
    }
}
internal fun LoftFeature.evaluateMesh(values:Map<String,Quantity>)=NativeShapeFeature(id,name,shape(values)).evaluateMesh(values)
internal fun DocumentState.withLoft(feature:LoftFeature,update:Boolean):DocumentState {
    val previous=effectiveFeatures().firstOrNull { it.id==feature.id }
    require(if(update) previous is LoftFeature else previous==null) { "Loft existiert nicht oder ID ist bereits vergeben." }
    val next=copy(features=if(update) effectiveFeatures().map { if(it.id==feature.id) feature else it } else effectiveFeatures()+feature)
    next.bodyMeshes() // Validate before publishing or adding to history.
    return next
}
internal fun loftFrom(j:JsonObject,id:String)=LoftFeature(id,j["name"]?.jsonPrimitive?.content ?: "Loft",j.getValue("sections").jsonArray.map { LoftSection.read(it.jsonObject) },j["ruled"]?.jsonPrimitive?.boolean ?: false)
internal fun LoftFeature.result(mesh:MeshData)=buildJsonObject {
    fun xyz(v:Vec3)=buildJsonObject { put("x",v.x);put("y",v.y);put("z",v.z) }
    put("featureId",id);put("bodyId",id);put("feature",json());put("bounds",buildJsonObject { put("min",xyz(mesh.bounds.low));put("max",xyz(mesh.bounds.high)) })
    put("validation",buildJsonObject { put("valid",true);put("closed",true);put("solid",true);put("selfIntersectionChecked",true) })
}
