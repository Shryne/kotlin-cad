package cad

import kotlinx.serialization.json.*
import kotlin.math.*

internal operator fun Vec3.plus(b: Vec3)=Vec3(x+b.x,y+b.y,z+b.z)
internal operator fun Vec3.minus(b: Vec3)=Vec3(x-b.x,y-b.y,z-b.z)
internal operator fun Vec3.times(s: Double)=Vec3(x*s,y*s,z*s)
internal fun Vec3.dot(b: Vec3)=x*b.x+y*b.y+z*b.z
internal fun Vec3.cross(b: Vec3)=Vec3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
internal fun Vec3.norm()=sqrt(dot(this))
internal fun Vec3.normalized(): Vec3 { val n=norm();require(n>1e-12 && n.isFinite()) { "Die Fläche ist entartet." };return this*(1/n) }

data class SketchFrame(val origin: Vec3,val uAxis: Vec3,val vAxis: Vec3) {
    init {
        require(listOf(origin,uAxis,vAxis).all { listOf(it.x,it.y,it.z).all(Double::isFinite) })
        require(abs(uAxis.norm()-1)<1e-6 && abs(vAxis.norm()-1)<1e-6 && abs(uAxis.dot(vAxis))<1e-6) { "Ungültige Skizzenebene." }
        require(listOf(origin.x,origin.y,origin.z).all { abs(it)<=1e10 })
    }
    val normal get()=uAxis.cross(vAxis)
    fun point(u: Double,v: Double)=origin+uAxis*u+vAxis*v
    fun project(p: Vec3): SketchPoint { val q=p-origin;return SketchPoint(q.dot(uAxis),q.dot(vAxis)) }
    fun json()=buildJsonObject {
        fun vector(v: Vec3)=JsonArray(listOf(v.x,v.y,v.z).map(::JsonPrimitive))
        put("origin",vector(origin));put("uAxis",vector(uAxis));put("vAxis",vector(vAxis))
    }
    companion object {
        fun read(j: JsonObject): SketchFrame {
            fun vector(key: String): Vec3 { val a=j.getValue(key).jsonArray;require(a.size==3);return Vec3(a[0].jsonPrimitive.double,a[1].jsonPrimitive.double,a[2].jsonPrimitive.double) }
            return SketchFrame(vector("origin"),vector("uAxis"),vector("vAxis"))
        }
    }
}
data class FaceReference(val sketchId: String? = null,val mirrored: Boolean = false,val triangle: Int,val shape: SketchShape? = null,val featureId: String? = null,val surfaceId: String? = null) {
    init { require(triangle>=0 && triangle<1_000_000);require(sketchId==null || sketchId.length in 1..128);require(featureId==null || featureId.length in 1..256);require(surfaceId==null || surfaceId.length in 1..256) }
    fun json()=buildJsonObject {
        put("sketchId",sketchId?.let(::JsonPrimitive) ?: JsonNull);put("mirrored",mirrored);put("triangle",triangle)
        put("shape",shape?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("featureId",featureId?.let(::JsonPrimitive) ?: JsonNull);put("surfaceId",surfaceId?.let(::JsonPrimitive) ?: JsonNull)
    }
    companion object { fun read(j: JsonObject)=FaceReference(j["sketchId"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content,
        j["mirrored"]?.jsonPrimitive?.boolean ?: false,j.getValue("triangle").jsonPrimitive.int,
        j["shape"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content?.let(SketchShape::valueOf),
        j["featureId"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content,
        j["surfaceId"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content) }
}
data class SelectedFace(val reference: FaceReference,val frame: SketchFrame,val patch: List<Vec3>,val label: String)
fun MeshData.faceFrame(triangle: Int): SketchFrame {
    require(triangle in 0 until triangleCount) { "Die gewählte Fläche existiert nicht mehr." }
    val a=vertices[indices[triangle*3]];val b=vertices[indices[triangle*3+1]];val c=vertices[indices[triangle*3+2]]
    val normal=(b-a).cross(c-a).normalized()
    val axis=if (abs(normal.x)<.9) Vec3(1.0,0.0,0.0) else Vec3(0.0,1.0,0.0)
    val u=(axis-normal*axis.dot(normal)).normalized()
    return SketchFrame(a,u,normal.cross(u).normalized())
}
fun MeshData.facePatch(triangle: Int): List<Vec3> {
    val frame=faceFrame(triangle)
    val tolerance=max(1e-7,bounds.span*1e-7)
    val result=ArrayList<Vec3>()
    for(offset in indices.indices step 3) {
        val a=vertices[indices[offset]];val b=vertices[indices[offset+1]];val c=vertices[indices[offset+2]]
        val n=(b-a).cross(c-a)
        if(n.norm()>1e-12 && n.normalized().dot(frame.normal)>1-1e-7 &&
            listOf(a,b,c).all { abs((it-frame.origin).dot(frame.normal))<=tolerance }) {
            result.add(a);result.add(b);result.add(c)
        }
    }
    return result
}
/** Resolve face dependencies before building profiles. Missing/cyclic supports fail transactionally. */
fun DocumentState.evaluatedSketches(): List<Sketch> = evaluatedModel().sketches
fun DocumentState.selectFace(sceneTriangle: Int): SelectedFace {
    require(sceneTriangle>=0)
    var triangle=sceneTriangle
    sceneBodies().filter { it.visible }.forEach { body ->
        val part=body.mesh
        if(triangle<part.triangleCount) {
            require(body.sketch?.shape!=SketchShape.CIRCLE || triangle<2*(96-2)) { "Die Zylindermantelfläche ist gekrümmt. Wähle eine ebene Stirnfläche." }
            val reference=FaceReference(body.sketch?.id,body.mirrored,triangle,body.sketch?.shape,
                if(features.isNotEmpty()) body.id else null,
                if(features.isNotEmpty()) body.surfaceId(triangle) else null)
            return SelectedFace(reference,part.faceFrame(triangle),part.facePatch(triangle),body.sketch?.let { "${it.name} · Fläche" } ?: "Importierte Fläche")
        }
        triangle-=part.triangleCount
    }
    error("Die gewählte Fläche existiert nicht mehr.")
}
/** Translate a stable face reference back to the visible scene's current triangle numbering. */
internal fun DocumentState.sceneTriangle(reference: FaceReference): Int? {
    var offset=0
    for(body in sceneBodies().filter { it.visible }) {
        val matches=when {
            reference.featureId!=null -> body.id==reference.featureId
            reference.sketchId!=null -> body.sketch?.id==reference.sketchId && body.mirrored==reference.mirrored
            else -> body.id=="import"
        }
        if(matches) return offset+body.surfaceTriangle(reference)
        offset+=body.mesh.triangleCount
    }
    return null
}
fun Sketch.localProject(p: Vec3)=frame?.project(p) ?: plane.project(p)
fun Sketch.editorProfiles(values: Map<String, Quantity>): List<List<Vec3>> {
    val basis=frame ?: SketchFrame(Vec3(0.0,0.0,0.0),when(plane) { SketchPlane.YZ -> Vec3(0.0,1.0,0.0);else -> Vec3(1.0,0.0,0.0) },
        when(plane) { SketchPlane.XY -> Vec3(0.0,1.0,0.0);else -> Vec3(0.0,0.0,1.0) })
    return profileCopies(values).filter { points -> points.all { abs((it-basis.origin).dot(basis.normal))<=1e-6 } }
}
/** Keep visible feature edges instead of displaying triangulation diagonals in the sketch backdrop. */
fun MeshData.contextEdges(): List<Pair<Vec3,Vec3>> {
    if(triangleCount>5000) return emptyList()
    val unique=vertices.distinct();val ids=unique.withIndex().associate { it.value to it.index }
    val edges=linkedMapOf<Pair<Int,Int>,MutableList<Vec3>>()
    for(offset in indices.indices step 3) {
        val points=(0..2).map { vertices[indices[offset+it]] }
        val cross=(points[1]-points[0]).cross(points[2]-points[0]);if(cross.norm()<1e-12) continue
        val normal=cross.normalized()
        for(i in 0..2) {
            val a=ids.getValue(points[i]);val b=ids.getValue(points[(i+1)%3]);val key=min(a,b) to max(a,b)
            edges.getOrPut(key) { mutableListOf() }.add(normal)
        }
    }
    return edges.filter { (_,normals) -> normals.size==1 || normals.any { abs(it.dot(normals.first()))<.99 } }
        .keys.map { unique[it.first] to unique[it.second] }
}

/** Reference points for construction aids on the chosen face, excluding unrelated bodies. */
fun DocumentState.supportPatch(reference: FaceReference): List<Vec3> {
    val id=reference.featureId ?: reference.sketchId?.let { bodyId(it,reference.mirrored) } ?: "import"
    val body=sceneBodies().firstOrNull { it.id==id } ?: return emptyList()
    return body.mesh.facePatch(body.surfaceTriangle(reference))
}
