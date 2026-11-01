package cad

import kotlinx.serialization.json.*
import kotlin.math.*

/** Linked geometry, in source-local coordinates; Euler X/Y/Z then instance translation. */
data class InstanceFeature(override val id:String,val name:String,val sourceFeatureId:String,
    val x:String="0 mm",val y:String="0 mm",val z:String="0 mm",
    val rotationX:String="0 deg",val rotationY:String="0 deg",val rotationZ:String="0 deg",val inheritColors:Boolean=true):CadFeature {
    init { require(id!="import" && id.isNotBlank() && id.length<=256 && sourceFeatureId!="import" && sourceFeatureId.isNotBlank() && sourceFeatureId.length<=256 && name.isNotBlank() && name.length<=128);require(listOf(x,y,z,rotationX,rotationY,rotationZ).all { it.length in 1..1024 }) }
    override fun json()=buildJsonObject { put("type","instance");put("id",id);put("name",name);put("sourceFeatureId",sourceFeatureId);put("x",x);put("y",y);put("z",z);put("rotationX",rotationX);put("rotationY",rotationY);put("rotationZ",rotationZ);put("inheritColors",inheritColors) }
    fun angles(values:Map<String,Quantity>)=listOf(rotationX,rotationY,rotationZ).map { expression ->
        val q=ParameterEvaluator.expression(expression,values);require(q.dimension==Dimension(angle=1) && abs(q.value)<=1e6) { "Instanzrotation benötigt deg/rad oder einen Winkelparameter." };q.value
    }
    companion object { fun read(j:JsonObject)=InstanceFeature(j.requiredString("id"),j.requiredString("name"),j.requiredString("sourceFeatureId"),j["x"]?.jsonPrimitive?.content ?: "0 mm",j["y"]?.jsonPrimitive?.content ?: "0 mm",j["z"]?.jsonPrimitive?.content ?: "0 mm",j["rotationX"]?.jsonPrimitive?.content ?: "0 deg",j["rotationY"]?.jsonPrimitive?.content ?: "0 deg",j["rotationZ"]?.jsonPrimitive?.content ?: "0 deg",j["inheritColors"]?.jsonPrimitive?.boolean ?: true) }
}
internal fun rotatePoint(v:Vec3,a:List<Double>):Vec3 {
    val p=Vec3(v.x,cos(a[0])*v.y-sin(a[0])*v.z,sin(a[0])*v.y+cos(a[0])*v.z)
    val q=Vec3(cos(a[1])*p.x+sin(a[1])*p.z,p.y,-sin(a[1])*p.x+cos(a[1])*p.z)
    return Vec3(cos(a[2])*q.x-sin(a[2])*q.y,sin(a[2])*q.x+cos(a[2])*q.y,q.z)
}
internal fun rotatedShape(shape:JsonObject,angles:List<Double>):JsonObject {
    var current=shape
    angles.forEachIndexed { i,angle -> if(abs(angle)>1e-14) current=buildJsonObject {
        put("type","rotate");put("shape",current);put("angle",angle)
        put("axis",JsonArray((0..2).map { JsonPrimitive(if(it==i) 1 else 0) }));put("origin",Vec3(0.0,0.0,0.0).arrayJson())
    } }
    return current
}
