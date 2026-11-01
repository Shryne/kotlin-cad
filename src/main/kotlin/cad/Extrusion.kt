package cad

import kotlinx.serialization.json.*
import kotlin.math.abs

/** Closed, consistently oriented triangle surface; the parametric profile remains the source. */
fun Sketch.extrudedMesh(values: Map<String, Quantity>): MeshData? {
    val expression = depth ?: return null
    val quantity = ParameterEvaluator.expression(expression, values)
    require(quantity.dimension == Dimension(1) && abs(quantity.value) in 1e-6..1e9) {
        "Extrusion benötigt eine Länge zwischen 0,000001 und 1 000 000 000 mm; negative Werte kehren die Richtung um."
    }
    val distance = quantity.value
    val offset = extrusionAxis()*distance
    val original = points(values)
    val base = if ((frame == null && plane == SketchPlane.XZ) xor (distance < 0)) original.reversed() else original
    val n = base.size
    val vertices = base + base.map { Vec3(it.x+offset.x,it.y+offset.y,it.z+offset.z) }
    val triangles = ArrayList<Int>()
    fun triangle(a: Int,b: Int,c: Int) { triangles.add(a);triangles.add(b);triangles.add(c) }
    for (i in 1 until n-1) { triangle(0,i+1,i);triangle(n,n+i,n+i+1) }
    for (i in 0 until n) {
        val j=(i+1)%n
        triangle(i,j,n+j);triangle(i,n+j,n+i)
    }
    return MeshData(name,vertices,triangles.toIntArray())
}

/** Extrudes a classified region through the bundled OpenCascade worker, retaining real inner wires. */
internal fun Sketch.extrudedMesh(values: Map<String,Quantity>,region: SketchRegion): MeshData? {
    val expression=depth ?: return null
    val q=ParameterEvaluator.expression(expression,values)
    require(q.dimension==Dimension(1) && abs(q.value) in 1e-6..1e9) { "Extrusion benötigt eine gültige Länge." }
    val byId=elementGeometries(values).filter { it.kind==SketchElementKind.PROFILE || it.kind==SketchElementKind.POLYLINE && it.closed }.associateBy { it.id }
    fun path(id: String)=JsonArray((byId[id] ?: error("Die Kontur '$id' fehlt.")).points.map { p -> JsonArray(listOf(p.x,p.y,p.z).map(::JsonPrimitive)) })
    val vector=extrusionAxis()*q.value
    val result=CadNativeGeometry.kernel.request("evaluate",buildJsonObject {
        put("shape",buildJsonObject { put("type","extrude");put("outer",path(region.outerElementIds.single()))
            put("holes",JsonArray(region.holeElementIds.map(::path)))
            put("vector",JsonArray(listOf(vector.x,vector.y,vector.z).map(::JsonPrimitive)))
        })
    }).get(30,java.util.concurrent.TimeUnit.SECONDS)
    val vertices=result.getValue("vertices").jsonArray.map { it.jsonArray.let { p -> Vec3(p[0].jsonPrimitive.double,p[1].jsonPrimitive.double,p[2].jsonPrimitive.double) } }
    val indices=result.getValue("indices").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
    return MeshData(name,vertices,indices)
}
internal fun MeshData.extrudedFace(triangle:Int,depth:String,values:Map<String,Quantity>):MeshData {
    val quantity=ParameterEvaluator.expression(depth,values)
    require(quantity.dimension==Dimension(1) && quantity.value.isFinite() && abs(quantity.value) in 1e-6..1e9) { "Extrusion benötigt eine gültige Länge." }
    val frame=faceFrame(triangle);val patch=facePatch(triangle)
    require(patch.isNotEmpty() && patch.size%3==0) { "Die ausgewählte Fläche ist nicht zuverlässig rekonstruierbar." }
    fun key(p:Vec3)="${kotlin.math.round(p.x*1e6).toLong()}:${kotlin.math.round(p.y*1e6).toLong()}:${kotlin.math.round(p.z*1e6).toLong()}"
    val points=linkedMapOf<String,Vec3>();val edges=mutableMapOf<Pair<String,String>,Int>()
    for(i in patch.indices step 3) {
        val tri=patch.subList(i,i+3).map { key(it).also { k -> points.putIfAbsent(k,it) } }
        for(j in 0..2) { val a=tri[j];val b=tri[(j+1)%3];val edge=if(a<b) a to b else b to a;edges[edge]=(edges[edge] ?: 0)+1 }
    }
    val boundary=edges.filterValues { it==1 }.keys
    require(boundary.size>=3) { "Die Flächenbegrenzung konnte nicht bestimmt werden." }
    val adjacency=mutableMapOf<String,MutableList<String>>()
    boundary.forEach { (a,b) -> adjacency.getOrPut(a){mutableListOf()}.add(b);adjacency.getOrPut(b){mutableListOf()}.add(a) }
    require(adjacency.values.all { it.size==2 }) { "Die Flächenbegrenzung ist offen oder verzweigt." }
    val unused=boundary.toMutableSet();val loops=mutableListOf<List<Vec3>>()
    while(unused.isNotEmpty()) {
        val first=unused.first();val start=first.first;var previous:String?=null;var current=start;val loop=mutableListOf<String>();var guard=0
        do {
            require(guard++<=boundary.size+1) { "Die Flächenbegrenzung ist ungültig." }
            loop.add(current)
            val next=adjacency.getValue(current).firstOrNull { it!=previous } ?: error("Die Flächenbegrenzung ist offen.")
            val edge=if(current<next) current to next else next to current
            require(unused.remove(edge) || next==start) { "Die Flächenbegrenzung ist nicht zusammenhängend." }
            previous=current;current=next
        } while(current!=start)
        require(loop.size>=3) { "Die Flächenbegrenzung ist zu kurz." }
        loops.add(loop.map(points::getValue))
    }
    val outer=loops.maxByOrNull { loop -> val p=loop.map(frame::project);abs(p.indices.sumOf { i -> val a=p[i];val b=p[(i+1)%p.size];a.u*b.v-b.u*a.v }) } ?: error("Die Außenkontur fehlt.")
    fun contains(point:Vec3,loop:List<Vec3>):Boolean {
        val p=frame.project(point);val polygon=loop.map(frame::project);var inside=false
        for(i in polygon.indices) { val a=polygon[i];val b=polygon[(i+1)%polygon.size]
            if((a.v>p.v)!=(b.v>p.v) && p.u<(b.u-a.u)*(p.v-a.v)/(b.v-a.v)+a.u) inside=!inside
        }
        return inside
    }
    require(loops.filter { it!==outer }.all { hole -> contains(hole.first(),outer) }) { "Die ebene Fläche enthält getrennte Bereiche und kann nicht sicher als eine Kontur extrudiert werden." }
    fun path(loop:List<Vec3>)=JsonArray(loop.map { p -> JsonArray(listOf(p.x,p.y,p.z).map(::JsonPrimitive)) })
    val vector=frame.normal*quantity.value
    val result=CadNativeGeometry.kernel.request("evaluate",buildJsonObject {
        put("shape",buildJsonObject { put("type","extrude");put("outer",path(outer));put("holes",JsonArray(loops.filter { it!==outer }.map(::path)));put("vector",JsonArray(listOf(vector.x,vector.y,vector.z).map(::JsonPrimitive))) })
    }).get(30,java.util.concurrent.TimeUnit.SECONDS)
    val vertices=result.getValue("vertices").jsonArray.map { it.jsonArray.let { p -> Vec3(p[0].jsonPrimitive.double,p[1].jsonPrimitive.double,p[2].jsonPrimitive.double) } }
    val indices=result.getValue("indices").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
    return MeshData("Flächenextrusion",vertices,indices)
}
internal object CadNativeGeometry { private val shared by lazy { NativeKernel() };val kernel get()=AssemblyWork.current.get()?.kernel ?: shared }
internal fun PrimitiveFeature.evaluateMesh(values:Map<String,Quantity>):MeshData {
    fun length(expression:String,positive:Boolean=false):Double {
        val q=ParameterEvaluator.expression(expression,values)
        require(q.dimension==Dimension(1) && q.value.isFinite() && abs(q.value)<=1e9 && (!positive || q.value>=1e-6)) { "Körpermaße müssen gültige Längen sein; Größen müssen positiv sein." }
        return q.value
    }
    val shape=when(kind) {
        PrimitiveKind.BOX -> buildJsonObject { put("type","box");put("x",length(width,true));put("y",length(depth,true));put("z",length(height,true)) }
        PrimitiveKind.CYLINDER -> buildJsonObject { put("type","cylinder");put("radius",length(radius,true));put("height",length(height,true)) }
        PrimitiveKind.SPHERE -> buildJsonObject { put("type","sphere");put("radius",length(radius,true)) }
    }
    val origin=Vec3(length(x),length(y),length(z))
    return GeometryCache.native(name,shape).translated(origin)
}
internal fun resolveNativeShape(shape:JsonObject,values:Map<String,Quantity>):JsonObject {
    fun resolve(value:JsonElement,depth:Int=0):JsonElement {
        require(depth<=32) { "Die native Körperdefinition ist zu tief verschachtelt." }
        return when(value) {
            is JsonObject -> {
                if(value.size==1 && value["scalarExpression"] is JsonPrimitive) {
                    val quantity=ParameterEvaluator.expression(value.getValue("scalarExpression").jsonPrimitive.content,values)
                    require(quantity.dimension==Dimension() && quantity.value.isFinite()) { "Skalare Ausdrücke benötigen dimensionslose Werte." }
                    JsonPrimitive(quantity.value)
                } else if(value.size==1 && value["angleExpression"] is JsonPrimitive) {
                    val quantity=ParameterEvaluator.expression(value.getValue("angleExpression").jsonPrimitive.content,values)
                    require(quantity.dimension==Dimension(angle=1) && quantity.value.isFinite()) { "Der Rotationswinkel benötigt eine Winkelformel." }
                    JsonPrimitive(quantity.value)
                } else if(value.size==1 && value["parameter"] is JsonPrimitive) {
                    val name=value.getValue("parameter").jsonPrimitive.content
                    val quantity=values[name] ?: throw IllegalArgumentException("Der native Körper verweist auf den unbekannten Parameter '$name'.")
                    require(quantity.dimension==Dimension(1) && quantity.value.isFinite()) { "Der Parameter '$name' muss eine Länge sein." }
                    JsonPrimitive(quantity.value)
                } else if(value.size==1 && value["expression"] is JsonPrimitive) {
                    val expression=value.getValue("expression").jsonPrimitive.content
                    val quantity=ParameterEvaluator.expression(expression,values)
                    require(quantity.dimension==Dimension(1) && quantity.value.isFinite()) { "Die Formelausdrücke nativer Geometrie benötigen Längen." }
                    JsonPrimitive(quantity.value)
                } else JsonObject(value.mapValues { (_,child) -> resolve(child,depth+1) })
            }
            is JsonArray -> JsonArray(value.map { resolve(it,depth+1) })
            else -> value
        }
    }
    return resolve(shape).jsonObject
}
internal fun NativeShapeFeature.evaluateMesh(values:Map<String,Quantity>):MeshData {
    return GeometryCache.native(name,resolveNativeShape(shape,values),singleSolid)
}
internal fun nativeMesh(name:String,result:JsonObject):MeshData {
    val vertices=result.getValue("vertices").jsonArray.map { it.jsonArray.let { p -> Vec3(p[0].jsonPrimitive.double,p[1].jsonPrimitive.double,p[2].jsonPrimitive.double) } }
    val indices=result.getValue("indices").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
    return MeshData(name,vertices,indices)
}
fun DocumentState.bodyMeshes(): List<MeshData> = evaluatedModel().bodies.filter { it.id!="import" }.map { it.mesh }
fun DocumentState.renderMesh(): MeshData? {
    val parts=sceneBodies().filter { it.visible }.map { it.mesh }
    if(parts.isEmpty()) return null
    if(parts.size==1) return parts.single()
    val vertices=ArrayList<Vec3>()
    val indices=ArrayList<Int>()
    parts.forEach { part ->
        val offset=vertices.size;vertices.addAll(part.vertices)
        part.indices.forEach { indices.add(it+offset) }
    }
    return MeshData("Projekt",vertices,indices.toIntArray())
}
