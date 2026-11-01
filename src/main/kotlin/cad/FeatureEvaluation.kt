package cad

import kotlin.math.abs

internal data class EvaluatedModel(val sketches: List<Sketch>,val bodies: List<SceneBody>)
internal data class SketchRegion(val id: String,val outerElementIds: List<String>,val holeElementIds: List<String> = emptyList())

internal fun CadFeature.position(values:Map<String,Quantity>):Vec3 {
    val expressions=when(this) {
        is PrimitiveFeature -> listOf(x,y,z)
        is ExtrusionFeature -> listOf(x,y,z)
        is FaceExtrusionFeature -> listOf(x,y,z)
        is MirrorFeature -> listOf(x,y,z)
        is LoftFeature -> listOf(x,y,z)
        is NativeShapeFeature -> listOf(x,y,z)
        is InstanceFeature -> listOf(x,y,z)
    }
    val coordinates=expressions.map { expression ->
        val quantity=ParameterEvaluator.expression(expression,values)
        require(quantity.dimension==Dimension(1) && quantity.value.isFinite() && kotlin.math.abs(quantity.value)<=1e9) { "Körperpositionen benötigen Längen bis 1 000 000 000 mm." }
        quantity.value
    }
    return Vec3(coordinates[0],coordinates[1],coordinates[2])
}
internal fun MeshData.translated(offset:Vec3):MeshData = if(offset==Vec3(0.0,0.0,0.0)) this else copy(vertices=vertices.map { Vec3(it.x+offset.x,it.y+offset.y,it.z+offset.z) })

/** Classifies closed sketch wires in their shared plane. IDs always belong to the outer element. */
internal fun Sketch.regions(values: Map<String,Quantity> = emptyMap()): List<SketchRegion> {
    val profiles=profileElements()
    if(profiles.isEmpty()) return emptyList()
    val frame=drawingFrame()
    data class Contour(val element: SketchElement,val points: List<Pair<Double,Double>>,val area: Double)
    val contours=profiles.map { element ->
        val geometry=element.geometry(this,values)
        val points=geometry.points.map { p ->
            val d=p-frame.origin; d.dot(frame.uAxis) to d.dot(frame.vAxis)
        }.let { if(it.size>1 && distance2(it.first(),it.last())<1e-16) it.dropLast(1) else it }
        require(geometry.closed) { "Kontur '${element.id}' ist offen; offene Linien und Bögen sind keine Extrusionsregion." }
        require(points.size>=3) { "Kontur '${element.id}' ist zu kurz." }
        Contour(element,points,signedArea(points))
    }
    contours.forEach { validateContour(it.points,it.element.id) }
    for(i in contours.indices) for(j in i+1 until contours.size) {
        require(!contoursIntersect(contours[i].points,contours[j].points)) {
            "Die Konturen '${contours[i].element.id}' und '${contours[j].element.id}' überlappen oder berühren sich."
        }
    }
    fun contains(parent: Contour,child: Contour)=pointInPolygon(child.points.first(),parent.points)
    return contours.mapNotNull { outer ->
        // Every closed wire is independently selectable; its direct children become holes.
        val holes=contours.filter { it!==outer && contains(outer,it) &&
            contours.none { middle -> middle!==outer && middle!==it && contains(outer,middle) && contains(middle,it) } }
        SketchRegion(outer.element.id,listOf(outer.element.id),holes.map { it.element.id })
    }
}

private fun distance2(a: Pair<Double,Double>,b: Pair<Double,Double>)=(a.first-b.first)*(a.first-b.first)+(a.second-b.second)*(a.second-b.second)
private fun signedArea(p: List<Pair<Double,Double>>)=p.indices.sumOf { i -> val a=p[i];val b=p[(i+1)%p.size];a.first*b.second-b.first*a.second }/2.0
private fun cross(a: Pair<Double,Double>,b: Pair<Double,Double>,c: Pair<Double,Double>)=(b.first-a.first)*(c.second-a.second)-(b.second-a.second)*(c.first-a.first)
private fun onSegment(a: Pair<Double,Double>,b: Pair<Double,Double>,p: Pair<Double,Double>): Boolean =
    kotlin.math.abs(cross(a,b,p))<1e-8 && p.first>=minOf(a.first,b.first)-1e-8 && p.first<=maxOf(a.first,b.first)+1e-8 && p.second>=minOf(a.second,b.second)-1e-8 && p.second<=maxOf(a.second,b.second)+1e-8
private fun segmentsIntersect(a: Pair<Double,Double>,b: Pair<Double,Double>,c: Pair<Double,Double>,d: Pair<Double,Double>): Boolean {
    val ab1=cross(a,b,c);val ab2=cross(a,b,d);val cd1=cross(c,d,a);val cd2=cross(c,d,b)
    return (ab1*ab2 < -1e-16 && cd1*cd2 < -1e-16) || onSegment(a,b,c) || onSegment(a,b,d) || onSegment(c,d,a) || onSegment(c,d,b)
}
private fun validateContour(p: List<Pair<Double,Double>>,id: String) {
    for(i in p.indices) for(j in i+1 until p.size) {
        if(j==i+1 || i==0 && j==p.lastIndex) continue
        require(!segmentsIntersect(p[i],p[(i+1)%p.size],p[j],p[(j+1)%p.size])) { "Kontur '$id' schneidet sich selbst." }
    }
    require(kotlin.math.abs(signedArea(p))>1e-10) { "Kontur '$id' hat keine Fläche." }
}
private fun contoursIntersect(a: List<Pair<Double,Double>>,b: List<Pair<Double,Double>>)=a.indices.any { i -> b.indices.any { j -> segmentsIntersect(a[i],a[(i+1)%a.size],b[j],b[(j+1)%b.size]) } }
private fun pointInPolygon(p: Pair<Double,Double>,poly: List<Pair<Double,Double>>): Boolean {
    var inside=false
    for(i in poly.indices) { val a=poly[i];val b=poly[(i+1)%poly.size];if(onSegment(a,b,p)) return false
        if((a.second>p.second)!=(b.second>p.second) && p.first<(b.first-a.first)*(p.second-a.second)/(b.second-a.second)+a.first) inside=!inside
    }
    return inside
}

internal fun SceneBody.surfaceId(triangle: Int): String {
    val s=sketch ?: return "mesh-triangle:$triangle"
    val n=if(s.shape==SketchShape.CIRCLE) 96 else if(s.shape==SketchShape.TRIANGLE) 3 else 4
    val capTriangles=2*(n-2)
    return "${regionId ?: "profile"}/"+if(triangle<capTriangles) (if(triangle%2==0) "cap:start" else "cap:end") else "side:${(triangle-capTriangles)/2}"
}
internal fun SceneBody.surfaceTriangle(reference: FaceReference): Int {
    val surface=reference.surfaceId ?: return reference.triangle
    if(sketch==null) {
        require(surface=="mesh-triangle:${reference.triangle}") { "Importierte Fläche stimmt nicht mehr überein." };return reference.triangle
    }
    val prefix="${regionId ?: "profile"}/"
    require(surface.startsWith(prefix)) { "Die Quellregion der Fläche fehlt." }
    val n=if(sketch.shape==SketchShape.CIRCLE) 96 else if(sketch.shape==SketchShape.TRIANGLE) 3 else 4
    return when(val role=surface.removePrefix(prefix)) {
        "cap:start" -> 0
        "cap:end" -> 1
        else -> { require(role.startsWith("side:"));val edge=role.substringAfter(':').toIntOrNull() ?: error("Ungültige Fläche.")
            require(edge in 0 until n) { "Die referenzierte Kante fehlt." };2*(n-2)+2*edge }
    }
}

/** Small bounded cache avoids rebuilding the whole dependency graph for each UI property read. */
private object EvaluationCache {
    private data class Entry(val document: DocumentState,val result: EvaluatedModel,val vertices: Int)
    private val entries=ArrayDeque<Entry>()
    fun get(state: DocumentState): EvaluatedModel {
        AssemblyWork.check()
        synchronized(entries) {
            entries.firstOrNull { it.document===state }?.let { return it.result }
            entries.firstOrNull { val old=it.document;old.mesh===state.mesh && old.parameters==state.parameters && old.features==state.features && old.sketches==state.sketches }?.let { cached ->
                val result=cached.result.copy(bodies=cached.result.bodies.map { it.copy(visible=it.id !in state.display.hiddenBodyIds) })
                entries.addFirst(Entry(state,result,cached.vertices));trim();return result
            }
        }
        val result=evaluate(state)
        synchronized(entries) { entries.addFirst(Entry(state,result,result.bodies.sumOf { it.mesh.vertices.size }));trim() }
        return result
    }
    private fun trim() { while(entries.size>8 || (entries.size>1 && entries.sumOf { it.vertices }>250000)) entries.removeLast() }

}
internal fun DocumentState.evaluatedModel()=EvaluationCache.get(this)
private fun evaluate(document: DocumentState): EvaluatedModel {
    document.featureOrder()
    val values=ParameterEvaluator.evaluate(document.parameters)
    val features=document.effectiveFeatures().associateBy { it.id }
    val definitions=document.sketches.associateBy { it.id }
    class Resolver {
        val profiles=mutableMapOf<String,Sketch>();val bodies=mutableMapOf<String,SceneBody>();val visiting=mutableSetOf<String>()
        fun enter(key: String) { AssemblyWork.check();require(visiting.add(key) && visiting.size<=128) { "Zyklische oder zu tiefe Modellabhängigkeit." } }
        fun sketch(id: String): Sketch {
            profiles[id]?.let { return it };enter("profile:$id")
            val original=definitions[id] ?: error("Die Trägerskizze fehlt.")
            val attached=original.support?.let { reference ->
                val source=body(reference.featureId ?: reference.sketchId?.let { bodyId(it,reference.mirrored) } ?: "import")
                require(reference.shape==null || source.sketch?.shape==reference.shape) { "Die Form der Trägerfläche wurde verändert." }
                val triangle=source.surfaceTriangle(reference)
                require(source.sketch?.shape!=SketchShape.CIRCLE || triangle<188) { "Gekrümmte Mantelflächen sind keine Skizzenebenen." }
                original.copy(frame=source.mesh.faceFrame(triangle))
            } ?: original
            val result=SketchEvaluationCache.get(attached,values) {
            val solvedSketch=if(attached.constraints.isEmpty()) attached else {
                val solved=attached.solveConstraints(values)
                require(solved.status!="conflicting") { "Skizze '${attached.name}' enthält widersprüchliche Constraints: ${solved.conflicts.joinToString()}" }
                solved.sketch
            }
            solvedSketch.elementGeometries(values);solvedSketch
            }
            profiles[id]=result;visiting.remove("profile:$id");return result
        }
        fun body(id: String): SceneBody {
            bodies[id]?.let { return it };enter("body:$id")
            val result=if(id=="import") SceneBody(id,null,false,document.mesh ?: error("Die importierte Trägerfläche fehlt."),id !in document.display.hiddenBodyIds)
            else when(val feature=features[id] ?: error("Feature existiert nicht: $id")) {
                is PrimitiveFeature -> SceneBody(id,null,false,feature.evaluateMesh(values),id !in document.display.hiddenBodyIds)
                is LoftFeature -> SceneBody(id,null,false,feature.evaluateMesh(values),id !in document.display.hiddenBodyIds)
                is NativeShapeFeature -> SceneBody(id,null,false,feature.evaluateMesh(values),id !in document.display.hiddenBodyIds)
                is InstanceFeature -> {
                    val source=body(feature.sourceFeatureId)
                    val origin=features.getValue(feature.sourceFeatureId).position(values);val angles=feature.angles(values)
                    val mesh=GeometryCache.get(listOf("instance",source.mesh.vertices,source.mesh.indices,origin,angles)) {
                        source.mesh.copy(name="Instance",vertices=source.mesh.vertices.map { rotatePoint(it-origin,angles) })
                    }
                    SceneBody(id,null,false,mesh.copy(name=feature.name),id !in document.display.hiddenBodyIds)
                }
                is ExtrusionFeature -> {
                    val s=sketch(feature.region.sketchId)
                    val region=s.regions(values).firstOrNull { it.id==feature.region.regionId } ?: error("Die Quellregion fehlt.")
                    val element=s.profileElements().firstOrNull { it.id==region.id } ?: error("Die Quellkontur fehlt.")
                    val profile=element.on(s).copy(depth=feature.depth)
                    val mesh=GeometryCache.get(listOf("extrusion",profile.copy(name=""),region,geometryValues(s.json().toString()+feature.depth,values))) { (if(region.holeElementIds.isEmpty() && element.kind==SketchElementKind.PROFILE) profile.extrudedMesh(values)
                        else s.copy(depth=feature.depth).extrudedMesh(values,region)) ?: error("Extrusionstiefe fehlt.") }
                    SceneBody(id,profile,false,mesh.copy(name=profile.name),id !in document.display.hiddenBodyIds,element.id)
                }
                is FaceExtrusionFeature -> {
                    val sourceId=feature.sourceFace.featureId ?: error("Die Körperflächenreferenz fehlt.")
                    require(sourceId!="import") { "Importierte Netzflächen lassen sich nicht zuverlässig rekonstruieren und können nicht extrudiert werden." }
                    val source=body(sourceId);val triangle=source.surfaceTriangle(feature.sourceFace)
                    require(source.sketch!=null) { "Diese Fläche stammt aus einer rekonstruierten Netztopologie und ist keine verlässlich referenzierbare Körperfläche." }
                    val sourceSketch=definitions[source.sketch.id]
                    val sourceRegion=sourceSketch?.regions(values)?.firstOrNull { it.id==source.regionId }
                    require(sourceRegion?.holeElementIds.isNullOrEmpty()) { "Die Flächenreferenz dieser Mehrkontur-Geometrie ist nicht stabil genug zum Extrudieren." }
                    require(source.sketch?.shape!=SketchShape.CIRCLE || triangle<188) { "Die gekrümmte Mantelfläche ist ungeeignet. Wähle eine ebene Fläche." }
                    val mesh=GeometryCache.get(listOf("face",source.mesh,triangle,ParameterEvaluator.expression(feature.depth,values))) { source.mesh.extrudedFace(triangle,feature.depth,values) }
                    SceneBody(id,null,false,mesh,id !in document.display.hiddenBodyIds)
                }
                is MirrorFeature -> {
                    val source=body(feature.sourceFeatureId);val offset=ParameterEvaluator.expression(feature.offset,values)
                    require(offset.dimension==Dimension(1) && abs(offset.value)<=1e9) { "Ungültiger Spiegelversatz." }
                    val mesh=GeometryCache.get(listOf("mirror",source.mesh.vertices,source.mesh.indices,feature.plane,offset.value)) { source.mesh.mirrored(feature.plane,offset.value) }
                    SceneBody(id,source.sketch,true,mesh.copy(name=source.mesh.name+" gespiegelt"),id !in document.display.hiddenBodyIds,source.regionId)
                }
            }.let { body -> if(body.id=="import" || features[body.id] is PrimitiveFeature) body else body.copy(mesh=body.mesh.translated(checkNotNull(features[body.id]).position(values))) }
            AssemblyWork.step("Körper: $id");bodies[id]=result;visiting.remove("body:$id");return result
        }
    }
    val resolver=Resolver();val profiles=document.sketches.map { resolver.sketch(it.id) }
    val bodies=listOfNotNull(document.mesh?.let { resolver.body("import") })+document.effectiveFeatures().map { resolver.body(it.id) }
    return EvaluatedModel(profiles,bodies)
}
