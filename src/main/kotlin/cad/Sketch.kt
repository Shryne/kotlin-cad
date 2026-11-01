package cad

import kotlinx.serialization.json.*
import kotlin.math.*
import java.util.UUID

enum class SketchPlane { XY, XZ, YZ;
    fun point(u: Double, v: Double) = when (this) {
        XY -> Vec3(u, v, 0.0); XZ -> Vec3(u, 0.0, v); YZ -> Vec3(0.0, u, v)
    }
}
enum class SketchShape(val label: String) { RECTANGLE("Rechteck"), CIRCLE("Kreis"), TRIANGLE("Dreieck") }
enum class SketchDrawingTool(val label: String) { RECTANGLE("Rechteck"), CENTER_RECTANGLE("Mittelpunkt-Rechteck"), CIRCLE("Kreis"), TRIANGLE("Dreieck"), LINE("Linie"), POLYLINE("Polylinie"), ARC("Bogen") }
/** One closed profile, with expressions stored instead of sampled geometry. */
data class Sketch(val id: String = UUID.randomUUID().toString(), val name: String = "Skizze",
    val plane: SketchPlane = SketchPlane.XY, val shape: SketchShape = SketchShape.RECTANGLE,
    val u: String = "0 mm", val v: String = "0 mm", val width: String = "60 mm", val height: String = "40 mm", val depth: String? = null,
    val mirror: SketchPlane? = null, val mirrorOffset: String = "0 mm",
    val frame: SketchFrame? = null, val support: FaceReference? = null, val angle: String = "0 deg", val centered: Boolean = false, val visible: Boolean = true, val elements: List<SketchElement> = emptyList(),val constraints: List<SketchConstraint> = emptyList()) {
    init { require(constraints.size<=512 && constraints.map { it.id }.distinct().size==constraints.size) { "Constraints benötigen eindeutige IDs (maximal 512)." } }
    fun points(values: Map<String, Quantity>): List<Vec3> {
        require(!centered || shape==SketchShape.RECTANGLE) { "Ein Mittelpunkt-Rechteck benötigt die Form RECTANGLE." }
        require(id.isNotBlank() && id.length <= 128 && name.isNotBlank() && name.length <= 128) { "Ungültiger Skizzenname." }
        fun length(text: String): Double {
            val q = ParameterEvaluator.expression(text, values)
            require(q.dimension == Dimension(1) && abs(q.value) <= 1e9) { "Skizzenmaße benötigen eine Länge bis 1 000 000 000 mm." }
            return q.value
        }
        val x = length(u); val y = length(v); val w = length(width)
        val rotation = rotation(values)
        fun point(u: Double,v: Double): Vec3 {
            val du=u-x;val dv=v-y
            val ru=x+du*cos(rotation)-dv*sin(rotation)
            val rv=y+du*sin(rotation)+dv*cos(rotation)
            return frame?.point(ru,rv) ?: plane.point(ru,rv)
        }
        require(w > 0) { "Breite oder Radius muss positiv sein." }
        return if (shape != SketchShape.CIRCLE) {
            val h = length(height); require(h > 0) { "Höhe muss positiv sein." }
            if (shape == SketchShape.TRIANGLE) listOf(point(x,y), point(x+w,y), point(x+w/2,y+h))
            else if(centered) listOf(point(x-w/2,y-h/2),point(x+w/2,y-h/2),point(x+w/2,y+h/2),point(x-w/2,y+h/2))
            else listOf(point(x,y), point(x+w,y), point(x+w,y+h), point(x,y+h))
        } else List(96) { val a = it * 2 * PI / 96; point(x+w*cos(a), y+w*sin(a)) }
    }
    fun rotation(values: Map<String, Quantity>): Double {
        val q=ParameterEvaluator.expression(angle,values)
        require(q.dimension==Dimension(angle=1) && abs(q.value)<=2*PI*1000) { "Drehung benötigt einen Winkel bis 360 000 deg, zum Beispiel 30 deg." }
        return q.value % (2*PI)
    }
    fun json() = buildJsonObject {
        put("id",id); put("name",name); put("plane",plane.name); put("shape",shape.name)
        put("elements",JsonArray(editableElements().map { it.json() }))
        put("constraints",JsonArray(constraints.map { it.json() }))
        put("frame",frame?.json() ?: JsonNull);put("support",support?.json() ?: JsonNull)
        put("depth", depth?.let(::JsonPrimitive) ?: JsonNull)
        put("mirror",mirror?.name?.let(::JsonPrimitive) ?: JsonNull);put("mirrorOffset",mirrorOffset)
        put("u",u); put("v",v); put("width",width); put("height",height); put("angle",angle);put("centered",centered);put("visible",visible)
    }
    companion object {
        fun read(j: JsonObject) = Sketch(j.requiredString("id"),j.requiredString("name"),
            SketchPlane.valueOf(j.requiredString("plane")),SketchShape.valueOf(j.requiredString("shape")),
            j.requiredString("u"),j.requiredString("v"),j.requiredString("width"),j.requiredString("height"), j["depth"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            j["mirror"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(SketchPlane::valueOf),
            j["mirrorOffset"]?.jsonPrimitive?.content ?: "0 mm",
            j["frame"]?.takeUnless { it==JsonNull }?.jsonObject?.let(SketchFrame::read),
            j["support"]?.takeUnless { it==JsonNull }?.jsonObject?.let(FaceReference::read),
            j["angle"]?.jsonPrimitive?.content ?: "0 deg", j["centered"]?.jsonPrimitive?.boolean ?: false, j["visible"]?.jsonPrimitive?.boolean ?: true, j["elements"]?.jsonArray?.map { SketchElement.read(it.jsonObject) } ?: emptyList(),j["constraints"]?.jsonArray?.map { SketchConstraint.read(it.jsonObject) } ?: emptyList())
    }
}
fun DocumentState.sketchPoints(): List<List<Vec3>> {
    val values = ParameterEvaluator.evaluate(parameters)
    return evaluatedSketches().filter { it.visible }.flatMap { it.profileCopies(values) }
}

/** All visible sketch wires, including open construction geometry, for the viewport. */
fun DocumentState.sketchGeometry(): List<SketchElementGeometry> {
    val values=ParameterEvaluator.evaluate(parameters)
    return evaluatedSketches().filter { it.visible }.flatMap { sketch ->
        sketch.elementGeometries(values).flatMap { element ->
            if(element.kind==SketchElementKind.PROFILE && sketch.mirror!=null) {
                val distance=sketch.mirrorDistance(values)
                listOf(element,element.copy(id="mirror:${element.id}",points=element.points.map { sketch.mirror.reflect(it,distance) }))
            } else listOf(element)
        }
    }
}
