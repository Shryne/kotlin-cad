package cad

import kotlinx.serialization.json.*
import java.util.PriorityQueue
import kotlin.math.*

internal data class SurfaceTriangle(val a:Vec3,val b:Vec3,val c:Vec3,val id:Int) {
    val center get()=(a+b+c)*(1.0/3)
    val area get()=(b-a).cross(c-a).norm()/2
}
/** Exact closest point on a triangle, including degenerate triangles. */
internal fun closestOnTriangle(p:Vec3,t:SurfaceTriangle):Vec3 {
    val a=t.a;val b=t.b;val c=t.c;val ab=b-a;val ac=c-a
    fun segment(x:Vec3,y:Vec3):Vec3 { val d=y-x;return if(d.dot(d)<1e-24)x else x+d*((p-x).dot(d)/d.dot(d)).coerceIn(0.0,1.0) }
    if(ab.cross(ac).norm()<1e-14)return listOf(segment(a,b),segment(b,c),segment(c,a)).minBy { (it-p).dot(it-p) }
    val ap=p-a;val d1=ab.dot(ap);val d2=ac.dot(ap);if(d1<=0 && d2<=0)return a
    val bp=p-b;val d3=ab.dot(bp);val d4=ac.dot(bp);if(d3>=0 && d4<=d3)return b
    val vc=d1*d4-d3*d2;if(vc<=0 && d1>=0 && d3<=0)return a+ab*(d1/(d1-d3))
    val cp=p-c;val d5=ab.dot(cp);val d6=ac.dot(cp);if(d6>=0 && d5<=d6)return c
    val vb=d5*d2-d1*d6;if(vb<=0 && d2>=0 && d6<=0)return a+ac*(d2/(d2-d6))
    val va=d3*d6-d5*d4;if(va<=0 && d4-d3>=0 && d5-d6>=0)return b+(c-b)*((d4-d3)/((d4-d3)+(d5-d6)))
    val denominator=1/(va+vb+vc);return a+ab*(vb*denominator)+ac*(vc*denominator)
}
internal class SurfaceIndex(mesh:MeshData) {
    data class Hit(val point:Vec3,val distance:Double,val triangle:SurfaceTriangle)
    private class Node(val bounds:Bounds,val triangles:List<SurfaceTriangle>,val left:Node?=null,val right:Node?=null)
    val triangles=(0 until mesh.triangleCount).map { t -> SurfaceTriangle(mesh.vertices[mesh.indices[t*3]],mesh.vertices[mesh.indices[t*3+1]],mesh.vertices[mesh.indices[t*3+2]],t) }
    private fun build(ts:List<SurfaceTriangle>):Node {
        MeasurementJobs.checkDeadline()
        val ps=ts.flatMap { listOf(it.a,it.b,it.c) };val bounds=Bounds(Vec3(ps.minOf { it.x },ps.minOf { it.y },ps.minOf { it.z }),Vec3(ps.maxOf { it.x },ps.maxOf { it.y },ps.maxOf { it.z }))
        if(ts.size<=8)return Node(bounds,ts)
        val size=bounds.size;val axis=if(size.x>=size.y && size.x>=size.z)0 else if(size.y>=size.z)1 else 2
        val sorted=ts.sortedBy { val p=it.center;when(axis){0->p.x;1->p.y;else->p.z} };val mid=sorted.size/2
        return Node(bounds,emptyList(),build(sorted.subList(0,mid)),build(sorted.subList(mid,sorted.size)))
    }
    private val root=build(triangles)
    private fun distance2(p:Vec3,b:Bounds):Double { fun d(v:Double,l:Double,h:Double):Double { return max(l-v,max(0.0,v-h)) };val x=d(p.x,b.low.x,b.high.x);val y=d(p.y,b.low.y,b.high.y);val z=d(p.z,b.low.z,b.high.z);return x*x+y*y+z*z }
    fun nearest(p:Vec3):Hit {
        var best=Double.POSITIVE_INFINITY;var point=p;var triangle=triangles.first()
        fun visit(node:Node) {
            if(distance2(p,node.bounds)>best)return
            node.triangles.forEach { t -> val q=closestOnTriangle(p,t);val d=(p-q).dot(p-q);if(d<best){best=d;point=q;triangle=t} }
            if(node.left!=null && node.right!=null) {
                val left=node.left;val right=node.right
                if(distance2(p,left.bounds)<distance2(p,right.bounds)){visit(left);visit(right)} else {visit(right);visit(left)}
            }
        }
        visit(root);return Hit(point,sqrt(best),triangle)
    }
}
internal object MeshSurfaceComparison {
    data class MapPoint(val triangle:Int,val point:Vec3,val distance:Double,val upper:Double,val maximumSample:Double,val vertices:List<Vec3>) {
        fun json()=buildJsonObject { put("triangle",triangle);put("point",point.measureJson());put("distanceMm",distance);put("upperBoundMm",upper);put("maximumSampleMm",maximumSample);put("vertices",JsonArray(vertices.map { it.measureJson() })) }
    }
    data class Direction(val lower:Double,val upper:Double,val rms:Double,val cells:Int,val map:List<MapPoint>) {
        fun json()=buildJsonObject { put("maximumLowerBoundMm",lower);put("maximumUpperBoundMm",upper);put("centroidAreaWeightedRmsMm",rms);put("evaluatedCells",cells);put("mapCount",map.size);put("deviationMap",JsonArray(map.map { it.json() })) }
    }
    data class Result(val forward:Direction,val reverse:Direction,val tolerance:Double) {
        fun json()=buildJsonObject {
            put("method","Adaptive bidirectional point-to-triangle distance with BVH; continuous triangle-surface maximum bounded by convex-target and Lipschitz bounds, subject to floating-point roundoff. RMS is centroid quadrature, not certified.")
            put("maximumLowerBoundMm",max(forward.lower,reverse.lower));put("maximumUpperBoundMm",max(forward.upper,reverse.upper));put("boundGapMm",max(forward.upper,reverse.upper)-max(forward.lower,reverse.lower))
            put("requestedToleranceMm",tolerance);put("converged",max(forward.upper,reverse.upper)-max(forward.lower,reverse.lower)<=tolerance)
            put("sourceToCopy",forward.json());put("copyToSource",reverse.json());put("uncertainty","Bounds describe the imported and reconstructed triangle surfaces, not an unknown original smooth CAD surface. Budget exhaustion is reported by converged=false; never claim a perfect copy from this result.")
        }
    }
    fun compare(source:MeshData,copy:MeshData,tolerance:Double,budget:Int):Result {
        require(tolerance.isFinite() && tolerance in .0001..10.0);require(budget in 100..200_000)
        require(max(source.triangleCount,copy.triangleCount)<=budget) { "Das Zellbudget muss mindestens die größte Dreieckszahl abdecken." }
        val a=SurfaceIndex(source);val b=SurfaceIndex(copy)
        return Result(directed(a,b,tolerance,budget),directed(b,a,tolerance,budget),tolerance)
    }
    private fun directed(source:SurfaceIndex,target:SurfaceIndex,tolerance:Double,budget:Int):Direction {
        data class Cell(val triangle:SurfaceTriangle,val lower:Double,val upper:Double,val distance:Double)
        var lower=0.0;var count=0
        fun cell(t:SurfaceTriangle):Cell {
            if(count++%128==0)MeasurementJobs.checkDeadline()
            val center=target.nearest(t.center);val vertices=listOf(t.a,t.b,t.c)
            val lo=max(center.distance,vertices.maxOf { target.nearest(it).distance })
            val convex=vertices.maxOf { (it-closestOnTriangle(it,center.triangle)).norm() }
            val lipschitz=center.distance+vertices.maxOf { (it-t.center).norm() }
            val upper=max(lo,min(convex,lipschitz))+1e-9
            lower=max(lower,lo);return Cell(t,lo,upper,center.distance)
        }
        val maxima=HashMap<Int,Double>()
        val queue=PriorityQueue<Cell>(compareByDescending { it.upper });val map=mutableListOf<MapPoint>();var area=0.0;var sum=0.0
        source.triangles.forEach { t -> val c=cell(t);queue.add(c);area+=t.area;sum+=t.area*c.distance*c.distance;map.add(MapPoint(t.id,t.center,c.distance,c.upper,c.lower,listOf(t.a,t.b,t.c)));maxima[t.id]=c.lower }
        while(queue.peek().upper-lower>tolerance && count+4<=budget) {
            val t=queue.remove().triangle;val ab=(t.a+t.b)*.5;val bc=(t.b+t.c)*.5;val ca=(t.c+t.a)*.5
            listOf(SurfaceTriangle(t.a,ab,ca,t.id),SurfaceTriangle(ab,t.b,bc,t.id),SurfaceTriangle(ca,bc,t.c,t.id),SurfaceTriangle(ab,bc,ca,t.id)).forEach { val c=cell(it);queue.add(c);maxima[it.id]=max(maxima[it.id] ?: 0.0,c.lower) }
        }
        val upperById=queue.groupBy { it.triangle.id }.mapValues { (_,cells)->cells.maxOf { it.upper } }
        return Direction(lower,queue.peek().upper,if(area>0)sqrt(sum/area) else 0.0,count,map.map { it.copy(upper=upperById.getValue(it.triangle),maximumSample=maxima.getValue(it.triangle)) })
    }
}
