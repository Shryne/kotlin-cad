package cad

import kotlinx.serialization.json.*
import kotlin.math.*

/** Bounded geometric fits over smooth triangle patches; these are surface evidence, not feature intent. */
internal object MeshSurfaceFitting {
    private data class Eigen(val value:Double,val vector:DoubleArray)
    private data class Contour2d(val points:List<Pair<Double,Double>>,val area:Double,val perimeter:Double,val center:Pair<Double,Double>?,val radius:Double?,val circleError:Double?)
    fun cylinders(mesh:MeshData,normals:List<Vec3>,adjacency:LongArray,adjacencyCount:Int,stopped:()->Boolean):List<JsonObject> {
        val parent=IntArray(normals.size) { it };val rank=ByteArray(normals.size)
        fun root(start:Int):Int { var i=start;while(parent[i]!=i) { parent[i]=parent[parent[i]];i=parent[i] };return i }
        fun union(a:Int,b:Int) { var x=root(a);var y=root(b);if(x==y)return;if(rank[x]<rank[y]) { val t=x;x=y;y=t };parent[y]=x;if(rank[x]==rank[y])rank[x]++ }
        for(i in 0 until adjacencyCount) {
            if(i%4096==0 && stopped()) throw java.util.concurrent.CancellationException("Der Zylinderfit wurde abgebrochen.")
            val a=(adjacency[i] ushr 32).toInt();val b=adjacency[i].toInt()
            if(normals[a].dot(normals[b])>=cos(Math.toRadians(38.0))) union(a,b)
        }
        val patches=normals.indices.groupBy(::root).values.filter { it.size>=12 }.take(2000)
        val found=mutableListOf<Pair<Double,JsonObject>>()
        for(ids in patches) {
            if(stopped()) throw java.util.concurrent.CancellationException("Der Zylinderfit wurde abgebrochen.")
            val fit=fitPatch(mesh,ids,normals) ?: continue
            found+=fit.second to fit.first
        }
        return found.sortedBy { it.first }.mapIndexed { i,(_,json) -> JsonObject(json+ ("id" to JsonPrimitive("cylinder-${i+1}"))) }
    }
    fun repeatedPatterns(fits:List<JsonObject>):List<JsonObject> {
        data class Fit(val json:JsonObject,val axis:Vec3,val center:Vec3,val radius:Double,val span:Double)
        fun point(json:JsonObject,key:String):Vec3 { val p=json.getValue(key).jsonObject;return Vec3(p.getValue("x").jsonPrimitive.double,p.getValue("y").jsonPrimitive.double,p.getValue("z").jsonPrimitive.double) }
        val remaining=fits.map { Fit(it,point(it,"axis"),point(it,"center"),it.getValue("radius").jsonPrimitive.double,it.getValue("axialSpan").jsonPrimitive.double) }.toMutableList()
        val result=mutableListOf<JsonObject>()
        while(remaining.isNotEmpty()) {
            val seed=remaining.removeAt(0)
            val matches=remaining.filter { abs(seed.axis.dot(it.axis))>.985 && abs(seed.radius-it.radius)<=max(seed.radius,it.radius)*.025 && abs(seed.span-it.span)<=max(seed.span,it.span)*.08 }
            if(matches.isNotEmpty()) remaining.removeAll(matches.toSet())
            val group=listOf(seed)+matches
            if(group.size<3) continue
            val distances=group.indices.flatMap { i -> (i+1 until group.size).map { j -> val delta=group[j].center-group[i].center;val radial=delta-seed.axis*delta.dot(seed.axis);radial.norm() } }.filter { it>1e-6 }
            val meanRadius=group.map { it.radius }.average()
            result+=buildJsonObject {
                put("id","cylinder-pattern-${result.size+1}");put("type","repeated parallel cylindrical patches");put("count",group.size)
                put("axis",buildJsonObject { put("x",seed.axis.x);put("y",seed.axis.y);put("z",seed.axis.z) })
                put("meanRadius",meanRadius);put("meanDiameter",meanRadius*2);put("meanAxialSpan",group.map { it.span }.average())
                put("centerSpacing",if(distances.isEmpty()) JsonNull else buildJsonObject { put("minimum",distances.minOrNull()!!);put("maximum",distances.maxOrNull()!!);put("mean",distances.average()) })
                put("members",JsonArray(group.map { it.json.getValue("id") }));put("centers",JsonArray(group.map { it.center }.map { p -> buildJsonObject { put("x",p.x);put("y",p.y);put("z",p.z) } }))
                put("meanFitError",group.map { it.json.getValue("fitError").jsonPrimitive.double }.average())
                put("interpretation","Geometric repetition candidate only; hole, bolt, pin or manufacturing intent is unknown.")
            }
        }
        return result
    }
    /** Stable, revision-scoped feature evidence assembled from measured surface fits. */
    fun featureCandidates(revision:String,planes:List<JsonObject>,contours:List<JsonObject>,cylinders:List<JsonObject>,patterns:List<JsonObject>):List<JsonObject> = buildList {
        planes.forEach { plane ->
            add(buildJsonObject {
                put("id","$revision:planar:${plane.getValue("id").jsonPrimitive.content}")
                put("kind","planar-patch");put("sourceIds",JsonArray(listOf(plane.getValue("id")).map { it }))
                put("area",plane.getValue("area"));put("fitError",plane.getValue("fitErrorMm"));put("normal",plane.getValue("normal"));put("offset",plane.getValue("offset"))
                put("interpretation","Measured planar surface candidate. It may be a plate, profile wall, cap or another planar face.")
            })
        }
        contours.forEach { contour ->
            val kind=contour.getValue("kind").jsonPrimitive.content
            add(buildJsonObject {
                put("id","$revision:${contour.getValue("id").jsonPrimitive.content}")
                put("kind",if(kind=="circular-contour") "circular-boundary" else "closed-boundary")
                put("sourceIds",JsonArray(listOf(contour.getValue("planeId"),contour.getValue("id"))))
                put("role",contour.getValue("role"));put("nestingDepth",contour.getValue("nestingDepth"));put("area",contour.getValue("area"));put("perimeter",contour.getValue("perimeter"));put("fitError",contour.getValue("fitError"))
                put("radius",contour.getValue("radius"));put("center",contour.getValue("center"))
                put("interpretation","Planar closed boundary only. Opening, outside edge and manufacturing intent are not determined by this evidence.")
            })
        }
        cylinders.forEach { cylinder ->
            add(buildJsonObject {
                put("id","$revision:${cylinder.getValue("id").jsonPrimitive.content}")
                put("kind","cylindrical-surface");put("sourceIds",JsonArray(listOf(cylinder.getValue("id"))))
                put("axis",cylinder.getValue("axis"));put("center",cylinder.getValue("center"));put("radius",cylinder.getValue("radius"));put("axialSpan",cylinder.getValue("axialSpan"));put("fitError",cylinder.getValue("fitError"))
                put("interpretation","Measured cylindrical surface only; hole, boss, pin and shaft are indistinguishable here.")
            })
        }
        patterns.forEach { pattern ->
            add(buildJsonObject {
                put("id","$revision:${pattern.getValue("id").jsonPrimitive.content}")
                put("kind","repeated-cylindrical-surfaces");put("sourceIds",pattern.getValue("members"));put("count",pattern.getValue("count"));put("axis",pattern.getValue("axis"));put("meanRadius",pattern.getValue("meanRadius"));put("meanDiameter",pattern.getValue("meanDiameter"));put("centerSpacing",pattern.getValue("centerSpacing"));put("fitError",pattern.getValue("meanFitError"))
                put("interpretation","Geometric repetition candidate. It is not yet a classified hole pattern or editable parametric feature.")
            })
        }
    }
    fun contours(mesh:MeshData,patches:List<List<Int>>,edgeCounts:Map<Long,IntArray>,weldPoints:List<Vec3>,stopped:()->Boolean):List<JsonObject> {
        val owner=IntArray(mesh.triangleCount) { -1 }
        patches.forEachIndexed { patch,ids -> ids.forEach { owner[it]=patch } }
        val boundaries=Array(patches.size) { mutableListOf<Long>() }
        edgeCounts.entries.forEachIndexed { i,(key,edge) ->
            if(i%8192==0 && stopped()) throw java.util.concurrent.CancellationException("Die Konturauswertung wurde abgebrochen.")
            val first=owner[edge[0]];val second=if(edge[1]>=2) owner[edge[2]] else -1
            if(first>=0 && first!=second) boundaries[first]+=key
            if(second>=0 && second!=first) boundaries[second]+=key
        }
        val result=mutableListOf<JsonObject>()
        boundaries.forEachIndexed { patchIndex,keys ->
            if(stopped()) throw java.util.concurrent.CancellationException("Die Konturauswertung wurde abgebrochen.")
            val neighbor=HashMap<Int,MutableList<Int>>()
            val remaining=HashSet<Long>(keys.size*2)
            keys.forEach { key ->
                val a=(key ushr 32).toInt();val b=key.toInt();remaining+=key
                neighbor.getOrPut(a,::mutableListOf).add(b);neighbor.getOrPut(b,::mutableListOf).add(a)
            }
            val loops=mutableListOf<List<Int>>()
            while(remaining.isNotEmpty()) {
                val edge=remaining.first();val start=(edge ushr 32).toInt();var current=start;var previous=-1;val path=mutableListOf(start);var closed=false;var steps=0
                while(steps++<=keys.size) {
                    val next=neighbor[current].orEmpty().firstOrNull { candidate ->
                        val packed=(min(current,candidate).toLong() shl 32) or (max(current,candidate).toLong() and 0xffffffffL)
                        packed in remaining
                    } ?: break
                    val packed=(min(current,next).toLong() shl 32) or (max(current,next).toLong() and 0xffffffffL)
                    remaining.remove(packed);previous=current;current=next
                    if(current==start) { closed=true;break }
                    path+=current
                    if(previous==current) break
                }
                if(closed && path.size>=8) loops+=path
            }
            if(loops.isEmpty()) return@forEachIndexed
            val sample=mesh.indices.let { indices ->
                val triangle=patches[patchIndex].first();val p0=mesh.vertices[indices[triangle*3]];val p1=mesh.vertices[indices[triangle*3+1]];val p2=mesh.vertices[indices[triangle*3+2]]
                Triple((p1-p0).cross(p2-p0).normalized(),p0,p0)
            }
            val normal=sample.first
            val reference=if(abs(normal.x)<.8) Vec3(1.0,0.0,0.0) else Vec3(0.0,1.0,0.0)
            val u=normal.cross(reference).normalized();val v=normal.cross(u).normalized()
            val fitted=loops.map { loop -> fitContour(loop.map { id -> val p=weldPoints[id];val offset=p-sample.second;p to (offset.dot(u) to offset.dot(v)) }.map { it.second }) }
            val depths=fitted.mapIndexed { index,contour ->
                val point=contour.points.first()
                fitted.indices.count { parent -> parent!=index && abs(fitted[parent].area)>abs(contour.area) && contains(point,fitted[parent].points) }
            }
            fitted.forEachIndexed { loopIndex,contour ->
                val area=abs(contour.area);if(area<1e-8) return@forEachIndexed
                val depth=depths[loopIndex];val radius=contour.radius
                val sourceLoop=loops[loopIndex].map(weldPoints::get)
                val stride=max(1,ceil(sourceLoop.size/256.0).toInt())
                val sampled=sourceLoop.filterIndexed { index,_ -> index%stride==0 }.take(256)
                result+=buildJsonObject {
                    put("id","contour-${patchIndex+1}-${loopIndex+1}");put("planeId","plane-${patchIndex+1}")
                    put("kind",if(radius!=null && (contour.circleError ?: Double.POSITIVE_INFINITY)/radius<.035) "circular-contour" else "closed-contour")
                    // A loop at depth zero can be either the outside edge of a patch or an
                    // isolated opening. Only report "inner" when another loop actually
                    // encloses it; otherwise leave the interpretation open.
                    put("role",if(depth>0) "inner-boundary-candidate" else "unclassified-boundary-candidate");put("nestingDepth",depth)
                    put("area",area);put("perimeter",contour.perimeter);put("pointCount",contour.points.size)
                    put("points",JsonArray(sampled.map { p -> buildJsonObject { put("x",p.x);put("y",p.y);put("z",p.z) } }))
                    if(contour.circleError==null) put("fitError",JsonNull) else put("fitError",contour.circleError)
                    if(radius==null) { put("radius",JsonNull);put("diameter",JsonNull);put("center",JsonNull) }
                    else {
                        put("radius",radius);put("diameter",radius*2)
                        val fitCenter=contour.center!!;val center=sample.second+u*fitCenter.first+v*fitCenter.second
                        put("center",buildJsonObject { put("x",center.x);put("y",center.y);put("z",center.z) })
                    }
                    put("method","Closed mesh-edge loop on an approximate planar patch; nesting and circle fit describe boundary geometry only")
                }
            }
        }
        return result
    }
    private fun fitContour(points:List<Pair<Double,Double>>):Contour2d {
        val area=points.indices.sumOf { i -> val a=points[i];val b=points[(i+1)%points.size];a.first*b.second-b.first*a.second }/2
        val perimeter=points.indices.sumOf { i -> val a=points[i];val b=points[(i+1)%points.size];hypot(b.first-a.first,b.second-a.second) }
        val meanX=points.map { it.first }.average();val meanY=points.map { it.second }.average()
        val matrix=Array(3) { DoubleArray(4) }
        points.forEach { (rawX,rawY) ->
            val x=rawX-meanX;val y=rawY-meanY;val row=doubleArrayOf(x,y,1.0);val rhs=-(x*x+y*y)
            for(r in 0..2) { for(c in 0..2) matrix[r][c]+=row[r]*row[c];matrix[r][3]+=row[r]*rhs }
        }
        val fit=solve(matrix)
        val cx=fit?.get(0)?.let { -it/2 };val cy=fit?.get(1)?.let { -it/2 }
        val r2=if(fit!=null && cx!=null && cy!=null) cx*cx+cy*cy-fit[2] else Double.NaN
        val radius=if(r2.isFinite() && r2>1e-12) sqrt(r2) else null
        val error=if(radius!=null && cx!=null && cy!=null) sqrt(points.sumOf { (x,y) -> val e=hypot(x-meanX-cx,y-meanY-cy)-radius;e*e }/points.size) else null
        return Contour2d(points,area,perimeter,if(cx!=null && cy!=null) (meanX+cx) to (meanY+cy) else null,radius,error)
    }
    private fun contains(p:Pair<Double,Double>,poly:List<Pair<Double,Double>>):Boolean {
        var inside=false
        for(i in poly.indices) { val a=poly[i];val b=poly[(i+1)%poly.size]
            if((a.second>p.second)!=(b.second>p.second) && p.first<(b.first-a.first)*(p.second-a.second)/(b.second-a.second)+a.first) inside=!inside
        }
        return inside
    }
    private fun fitPatch(mesh:MeshData,ids:List<Int>,normals:List<Vec3>):Pair<JsonObject,Double>? {
        val mean=Vec3(ids.sumOf { normals[it].x },ids.sumOf { normals[it].y },ids.sumOf { normals[it].z })*(1.0/ids.size)
        val cov=Array(3) { DoubleArray(3) }
        ids.forEach { i -> val n=normals[i]-mean;val a=doubleArrayOf(n.x,n.y,n.z);for(r in 0..2) for(c in 0..2) cov[r][c]+=a[r]*a[c]/ids.size }
        val eig=jacobi(cov).sortedBy { it.value }
        if(eig[1].value<.035*eig[2].value || eig[0].value>.08*eig[1].value) return null
        var axis=Vec3(eig[0].vector[0],eig[0].vector[1],eig[0].vector[2]).normalized()
        if(axis.dot(Vec3(1.0,1.0,1.0))<0) axis=axis*(-1.0)
        val reference=if(abs(axis.x)<.8) Vec3(1.0,0.0,0.0) else Vec3(0.0,1.0,0.0)
        val u=axis.cross(reference).normalized();val v=axis.cross(u).normalized()
        val indices=ids.asSequence().flatMap { t -> sequenceOf(mesh.indices[t*3],mesh.indices[t*3+1],mesh.indices[t*3+2]) }.distinct().take(6000).toList()
        if(indices.size<12) return null
        val points=indices.map { mesh.vertices[it] }
        val projectedRaw=points.map { it.dot(u) to it.dot(v) }
        val originU=projectedRaw.map { it.first }.average();val originV=projectedRaw.map { it.second }.average()
        val projected=projectedRaw.map { (x,y) -> (x-originU) to (y-originV) }
        val sums=Array(3) { DoubleArray(4) }
        for((x,y) in projected) {
            val row=doubleArrayOf(x,y,1.0);val rhs=-(x*x+y*y)
            for(r in 0..2) { for(c in 0..2) sums[r][c]+=row[r]*row[c];sums[r][3]+=row[r]*rhs }
        }
        val solution=solve(sums) ?: return null
        val cxLocal=-solution[0]/2;val cyLocal=-solution[1]/2
        val r2=cxLocal*cxLocal+cyLocal*cyLocal-solution[2]
        if(!r2.isFinite() || r2<=1e-12) return null
        val radius=sqrt(r2)
        val errors=projected.map { hypot(it.first-cxLocal,it.second-cyLocal)-radius }
        val rms=sqrt(errors.sumOf { it*it }/errors.size)
        if(!rms.isFinite() || rms/radius>.035 || radius<mesh.bounds.span*1e-5) return null
        val axial=points.map { it.dot(axis) }
        val center=u*(cxLocal+originU)+v*(cyLocal+originV)+axis*((axial.minOrNull()!!+axial.maxOrNull()!!)/2)
        val spread=ids.map { val n=normals[it];acos(abs(n.dot(axis)).coerceIn(0.0,1.0)) }
        if(spread.maxOrNull()!!<.12) return null
        val json=buildJsonObject {
            put("axis",buildJsonObject { put("x",axis.x);put("y",axis.y);put("z",axis.z) })
            put("center",buildJsonObject { put("x",center.x);put("y",center.y);put("z",center.z) })
            put("radius",radius);put("diameter",radius*2);put("axialSpan",axial.maxOrNull()!!-axial.minOrNull()!!)
            put("triangles",ids.size);put("fitError",rms);put("relativeFitError",rms/radius)
            put("method","Smooth triangle-patch normals and least-squares circle fit; approximate surface evidence")
        }
        return json to rms
    }
    private fun solve(matrix:Array<DoubleArray>):DoubleArray? {
        val a=Array(3) { matrix[it].copyOf() }
        for(c in 0..2) {
            val p=(c..2).maxByOrNull { abs(a[it][c]) } ?: return null
            if(abs(a[p][c])<1e-12) return null
            val tmp=a[c];a[c]=a[p];a[p]=tmp
            val scale=a[c][c];for(j in c..3) a[c][j]/=scale
            for(r in 0..2) if(r!=c) { val f=a[r][c];for(j in c..3) a[r][j]-=f*a[c][j] }
        }
        return DoubleArray(3) { a[it][3] }.takeIf { it.all(Double::isFinite) }
    }
    private fun jacobi(source:Array<DoubleArray>):List<Eigen> {
        val a=Array(3) { source[it].copyOf() };val v=Array(3) { DoubleArray(3) { j -> if(it==j) 1.0 else 0.0 } }
        repeat(32) {
            var p=0;var q=1
            for(i in 0..2) for(j in i+1..2) if(abs(a[i][j])>abs(a[p][q])) { p=i;q=j }
            if(abs(a[p][q])<1e-12) return@repeat
            val theta=.5*atan2(2*a[p][q],a[q][q]-a[p][p]);val c=cos(theta);val s=sin(theta)
            val app=c*c*a[p][p]-2*s*c*a[p][q]+s*s*a[q][q];val aqq=s*s*a[p][p]+2*s*c*a[p][q]+c*c*a[q][q]
            for(k in 0..2) if(k!=p && k!=q) { val akp=a[k][p];val akq=a[k][q];a[k][p]=c*akp-s*akq;a[p][k]=a[k][p];a[k][q]=s*akp+c*akq;a[q][k]=a[k][q] }
            a[p][p]=app;a[q][q]=aqq;a[p][q]=0.0;a[q][p]=0.0
            for(k in 0..2) { val vkp=v[k][p];val vkq=v[k][q];v[k][p]=c*vkp-s*vkq;v[k][q]=s*vkp+c*vkq }
        }
        return (0..2).map { i -> Eigen(a[i][i],DoubleArray(3) { v[it][i] }) }
    }
}
