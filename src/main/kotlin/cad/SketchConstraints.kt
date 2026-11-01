package cad

import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SketchConstraintType { COINCIDENT, HORIZONTAL, VERTICAL, PARALLEL, PERPENDICULAR, EQUAL_LENGTH, CONCENTRIC, TANGENT_LINE_CIRCLE, TANGENT_CIRCLES, TANGENT_ARC, FIXED_LENGTH, DISTANCE, RADIUS, DIAMETER, ANGLE }

/** indices are point numbers for COINCIDENT, segment starts for line constraints, and empty for CONCENTRIC. */
data class SketchConstraint(
    val id: String = UUID.randomUUID().toString(),
    val type: SketchConstraintType,
    val elementIds: List<String>,
    val indices: List<Int> = emptyList(),
    val value: String? = null,
    val internal: Boolean = false
) {
    init {
        require(id.isNotBlank() && id.length<=128 && elementIds.size in 1..2 && elementIds.all { it.isNotBlank() && it.length<=128 })
        require(indices.all { it in 0..511 })
    }
    fun json()=buildJsonObject {
        put("id",id);put("type",type.name);put("elementIds",JsonArray(elementIds.map(::JsonPrimitive)))
        put("indices",JsonArray(indices.map(::JsonPrimitive)))
        value?.let { put("value",it) };put("internal",internal)
    }
    companion object {
        fun read(j: JsonObject)=SketchConstraint(j.requiredString("id"),SketchConstraintType.valueOf(j.requiredString("type")),
            j.getValue("elementIds").jsonArray.map { it.jsonPrimitive.content },j["indices"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),j["value"]?.jsonPrimitive?.content,j["internal"]?.jsonPrimitive?.boolean ?: false)
    }
}

data class SketchConstraintSolution(val sketch: Sketch,val status: String,val degreesOfFreedom: Int,val conflicts: List<String>)
private data class SolverConstraint(val type:String,val variables:List<Int>,val target:Double?=null,val internal:Boolean=false)

/** Adds one persistent geometric relationship and solves without mutating the source on conflict. */
fun Sketch.addConstraint(constraint: SketchConstraint,values: Map<String,Quantity> = emptyMap()): SketchConstraintSolution {
    require(constraints.size<512 && constraints.none { it.id==constraint.id }) { "Constraints benötigen eindeutige IDs (maximal 512)." }
    val candidate=copy(constraints=constraints+constraint)
    val result=candidate.solveConstraints(values)
    return if(result.status=="conflicting") result.copy(sketch=this) else result
}

/** Moves one point as a drag/edit candidate, then reapplies its relationships atomically. */
fun Sketch.moveConstrainedPoint(elementId:String,index:Int,u:Double,v:Double,values:Map<String,Quantity> = emptyMap()): SketchConstraintSolution {
    require(u.isFinite() && v.isFinite() && kotlin.math.abs(u)<=1e9 && kotlin.math.abs(v)<=1e9)
    val source=editableElements().firstOrNull { it.id==elementId } ?: throw IllegalArgumentException("Skizzenelement fehlt.")
    val moved=when {
        source.kind==SketchElementKind.LINE || source.kind==SketchElementKind.POLYLINE -> {
            require(index in source.vertices.indices);source.copy(vertices=source.vertices.mapIndexed { i,p -> if(i==index) SketchCoordinate(sketchLength(u),sketchLength(v)) else p })
        }
        source.kind==SketchElementKind.PROFILE && source.shape==SketchShape.CIRCLE -> {
            require(index==0);source.copy(u=sketchLength(u),v=sketchLength(v))
        }
        else -> throw IllegalArgumentException("Dieser Geometrietyp unterstützt noch keine punktbasierte Constraint-Bearbeitung.")
    }
    val candidate=withElements(editableElements().map { if(it.id==elementId) moved else it })
    val result=candidate.solveConstraints(values)
    return if(result.status=="conflicting") result.copy(sketch=this) else result
}

fun Sketch.solveConstraints(values: Map<String,Quantity> = emptyMap()): SketchConstraintSolution {
    if(constraints.isEmpty()) return SketchConstraintSolution(this,"underconstrained",0,emptyList())
    val elements=editableElements().associateBy { it.id }
    val variableIndex=linkedMapOf<Pair<String,Int>,Int>()
    val radiusIndex=linkedMapOf<String,Int>()
    val variables=mutableListOf<Double>()
    val circleCenters=mutableSetOf<String>()
    fun scalar(text:String):Double {
        val q=ParameterEvaluator.expression(text,values)
        require(q.dimension==Dimension(1) && q.value.isFinite() && kotlin.math.abs(q.value)<=1e9) { "Constraints benötigen gültige Längenkoordinaten." }
        return q.value
    }
    fun point(elementId:String,index:Int):List<Int> {
        val e=elements[elementId] ?: throw IllegalArgumentException("Constraint-Element '$elementId' fehlt.")
        require(e.kind==SketchElementKind.LINE || e.kind==SketchElementKind.POLYLINE || e.kind==SketchElementKind.ARC || e.kind==SketchElementKind.PROFILE && e.shape==SketchShape.CIRCLE) {
            "Constraints unterstützen Linien, Polylinien, Bögen und Kreismittelpunkte."
        }
        if(e.kind==SketchElementKind.PROFILE || e.kind==SketchElementKind.ARC) {
            require(index==0) { "Ein Kreismittelpunkt hat nur einen Punkt." };circleCenters.add(e.id)
        } else require(index in e.vertices.indices) { "Der referenzierte Skizzenpunkt fehlt." }
        val key=e.id to index
        val x=variableIndex[key]
        if(x!=null) return listOf(x,x+1)
        val coordinate=if(e.kind==SketchElementKind.PROFILE || e.kind==SketchElementKind.ARC) SketchCoordinate(e.u,e.v) else e.vertices[index]
        val offset=variables.size;variables.add(scalar(coordinate.u));variables.add(scalar(coordinate.v));variableIndex[key]=offset
        return listOf(offset,offset+1)
    }
    fun segment(elementId:String,start:Int):List<Int> {
        val e=elements[elementId] ?: throw IllegalArgumentException("Constraint-Element '$elementId' fehlt.")
        require(e.kind==SketchElementKind.LINE || e.kind==SketchElementKind.POLYLINE) { "Linien-Constraints benötigen eine Linie oder Polylinie." }
        val next=if(e.isClosed) (start+1)%e.vertices.size else start+1
        require(start in e.vertices.indices && next in e.vertices.indices) { "Das referenzierte Liniensegment fehlt." }
        return point(elementId,start)+point(elementId,next)
    }
    fun circle(elementId:String):Triple<List<Int>,Int,SketchElement> {
        val e=elements[elementId] ?: throw IllegalArgumentException("Constraint-Element '$elementId' fehlt.")
        require(e.kind==SketchElementKind.ARC || e.kind==SketchElementKind.PROFILE && e.shape==SketchShape.CIRCLE) { "Dieser Constraint benötigt einen Kreis oder Bogen." }
        val center=point(e.id,0)
        val radius=radiusIndex.getOrPut(e.id) { variables.size.also { variables.add(scalar(e.width));require(variables.last()>1e-9) { "Kreis- und Bogenradien müssen positiv sein." } } }
        return Triple(center,radius,e)
    }
    fun measure(c:SketchConstraint,dimension:Dimension=Dimension(1)):Double {
        val expression=c.value ?: throw IllegalArgumentException("Für dieses Maß fehlt der Wert.")
        val q=ParameterEvaluator.expression(expression,values)
        require(q.dimension==dimension && q.value.isFinite() && kotlin.math.abs(q.value)<=1e9) { if(dimension.angle==1) "Das Winkelmaß benötigt einen gültigen Winkel." else "Das Maß benötigt eine gültige Länge." }
        return q.value
    }
    val rows=mutableListOf<Pair<SketchConstraint,SolverConstraint>>()
    constraints.forEach { c ->
        val added=when(c.type) {
            SketchConstraintType.COINCIDENT -> { require(c.elementIds.size==2 && c.indices.size==2);val a=point(c.elementIds[0],c.indices[0]);val b=point(c.elementIds[1],c.indices[1]);SolverConstraint("coincident",a+b) }
            SketchConstraintType.HORIZONTAL -> { require(c.elementIds.size==1 && c.indices.size==1);val v=segment(c.elementIds[0],c.indices[0]);SolverConstraint("horizontal",listOf(v[1],v[3])) }
            SketchConstraintType.VERTICAL -> { require(c.elementIds.size==1 && c.indices.size==1);val v=segment(c.elementIds[0],c.indices[0]);SolverConstraint("vertical",listOf(v[0],v[2])) }
            SketchConstraintType.PARALLEL,SketchConstraintType.PERPENDICULAR,SketchConstraintType.EQUAL_LENGTH -> {
                require(c.elementIds.size==2 && c.indices.size==2)
                SolverConstraint(when(c.type) { SketchConstraintType.PARALLEL->"parallel";SketchConstraintType.PERPENDICULAR->"perpendicular";else->"equalLength" },segment(c.elementIds[0],c.indices[0])+segment(c.elementIds[1],c.indices[1]))
            }
            SketchConstraintType.CONCENTRIC -> {
                require(c.elementIds.size==2 && c.indices.isEmpty());SolverConstraint("concentric",point(c.elementIds[0],0)+point(c.elementIds[1],0))
            }
            SketchConstraintType.FIXED_LENGTH -> { require(c.elementIds.size==1 && c.indices.size==1);SolverConstraint("distance",segment(c.elementIds[0],c.indices[0]),measure(c)) }
            SketchConstraintType.DISTANCE -> { require(c.elementIds.size==2 && c.indices.size==2);SolverConstraint("distance",point(c.elementIds[0],c.indices[0])+point(c.elementIds[1],c.indices[1]),measure(c)) }
            SketchConstraintType.ANGLE -> { require(c.elementIds.size==1 && c.indices.size==1);SolverConstraint("angle",segment(c.elementIds[0],c.indices[0]),measure(c,Dimension(angle=1))) }
            SketchConstraintType.RADIUS,SketchConstraintType.DIAMETER -> {
                require(c.elementIds.size==1 && c.indices.isEmpty());val r=circle(c.elementIds.single()).second;val target=measure(c)/(if(c.type==SketchConstraintType.DIAMETER) 2 else 1)
                require(target>1e-9) { "Radius und Durchmesser müssen positiv sein." };SolverConstraint("fix",listOf(r),target)
            }
            SketchConstraintType.TANGENT_LINE_CIRCLE -> {
                require(c.elementIds.size==2 && c.indices.size==1);val line=segment(c.elementIds[0],c.indices[0]);val (center,radius)=circle(c.elementIds[1]);SolverConstraint("tangentLineCircle",line+center+radius)
            }
            SketchConstraintType.TANGENT_CIRCLES -> {
                require(c.elementIds.size==2 && c.indices.isEmpty());val a=circle(c.elementIds[0]);val b=circle(c.elementIds[1]);SolverConstraint("tangentCircles",a.first+listOf(a.second)+b.first+listOf(b.second),internal=c.internal)
            }
            SketchConstraintType.TANGENT_ARC -> {
                require(c.elementIds.size==2 && c.indices.size==3) { "Bogenanschluss benötigt Linie/Bogen sowie Segment- und Endpunktangaben." }
                val line=segment(c.elementIds[0],c.indices[0]);val lineEndpoint=c.indices[1];val arcEndpoint=c.indices[2]
                require(lineEndpoint in 0..1 && arcEndpoint in 0..1) { "Ungültiger Bogenendpunkt." }
                val (center,radius,arc)=circle(c.elementIds[1]);require(arc.kind==SketchElementKind.ARC) { "Der Anschluss benötigt einen Bogen." }
                val angle=if(arcEndpoint==0) arc.startAngle else "(${arc.startAngle}) + (${arc.sweepAngle})"
                val a=ParameterEvaluator.expression(angle,values);require(a.dimension==Dimension(angle=1)) { "Ungültiger Bogenwinkel." }
                val u=scalar(arc.u);val v=scalar(arc.v);val r=scalar(arc.width);val theta=a.value
                val fixedX=u+r*kotlin.math.cos(theta);val fixedY=v+r*kotlin.math.sin(theta)
                val endpoint=if(lineEndpoint==0) line.take(2) else line.drop(2).take(2)
                listOf(SolverConstraint("fix",listOf(endpoint[0]),fixedX),SolverConstraint("fix",listOf(endpoint[1]),fixedY),SolverConstraint("tangentLineCircle",line+center+radius))
            }
        }
        if(added is List<*>) added.forEach { rows.add(c to (it as SolverConstraint)) } else rows.add(c to (added as SolverConstraint))
    }
    require(variables.isNotEmpty()) { "Die Constraints enthalten keine Geometrie." }
    val request=buildJsonObject {
        put("variables",JsonArray(variables.map(::JsonPrimitive)))
        put("constraints",JsonArray(rows.map { (constraint,row) ->
            buildJsonObject { put("id",constraint.id);put("type",row.type);put("vars",JsonArray(row.variables.map(::JsonPrimitive)));row.target?.let { put("target",it) };if(row.internal) put("internal",true) }
        }))
    }
    require(variables.size<=256) { "Der Solver unterstützt höchstens 128 Punkte pro Berechnung." }
    val response=CadNativeGeometry.kernel.request("solve",request).get(30,TimeUnit.SECONDS)
    val status=response.getValue("status").jsonPrimitive.content
    val dof=response.getValue("degreesOfFreedom").jsonPrimitive.int
    val conflicts=response.getValue("conflicts").jsonArray.map { it.jsonPrimitive.content }
    if(status=="conflicting") return SketchConstraintSolution(this,status,dof,conflicts)
    val solved=response.getValue("variables").jsonArray.map { it.jsonPrimitive.double }
    val next=editableElements().map { e ->
        if(e.kind==SketchElementKind.LINE || e.kind==SketchElementKind.POLYLINE) e.copy(vertices=e.vertices.mapIndexed { point,p ->
            val i=variableIndex[e.id to point] ?: return@mapIndexed p
            SketchCoordinate(sketchLength(solved[i]),sketchLength(solved[i+1]))
        }) else if(e.id in circleCenters) {
            val i=variableIndex.getValue(e.id to 0)
            e.copy(u=sketchLength(solved[i]),v=sketchLength(solved[i+1]),width=radiusIndex[e.id]?.let { "${solved[it]} mm" } ?: e.width)
        } else if(e.id in radiusIndex) e.copy(width="${solved[radiusIndex.getValue(e.id)]} mm") else e
    }
    return SketchConstraintSolution(withElements(next),status,dof,conflicts)
}
