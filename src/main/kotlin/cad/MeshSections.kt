package cad

import kotlinx.serialization.json.*
import kotlin.math.*

internal fun Vec3.measureJson()=JsonArray(listOf(x,y,z).map(::JsonPrimitive))
internal fun JsonElement.measureVector():Vec3 { val a=jsonArray;require(a.size==3);return Vec3(a[0].jsonPrimitive.double,a[1].jsonPrimitive.double,a[2].jsonPrimitive.double).also { require(listOf(it.x,it.y,it.z).all { n -> n.isFinite() && abs(n)<=1e9 }) } }
internal data class SectionPath(val points:List<Vec3>,val closed:Boolean)
internal data class MeshSection(val origin:Vec3,val normal:Vec3,val paths:List<SectionPath>,val ambiguous:Boolean,val coplanarTriangles:Int)

/** Intersects actual mesh triangles, rather than selecting a recessed surface patch. */
internal object MeshSections {
    fun cut(mesh:MeshData,origin:Vec3,normal:Vec3):MeshSection {
        require(mesh.triangleCount<=300_000) { "Querschnitte sind auf 300 000 Dreiecke begrenzt." }
        val n=normal.normalized();val eps=max(1e-7,mesh.bounds.span*1e-9)
        val points=mutableListOf<Vec3>();val buckets=HashMap<Triple<Long,Long,Long>,MutableList<Int>>()
        fun key(p:Vec3)=Triple(floor(p.x/eps).toLong(),floor(p.y/eps).toLong(),floor(p.z/eps).toLong())
        fun vertex(p:Vec3):Int {
            val k=key(p)
            for(x in -1..1) for(y in -1..1) for(z in -1..1) buckets[Triple(k.first+x,k.second+y,k.third+z)]?.firstOrNull { (points[it]-p).norm()<=eps }?.let { return it }
            val id=points.size;points.add(p);buckets.getOrPut(k) { mutableListOf() }.add(id);return id
        }
        val edges=linkedSetOf<Pair<Int,Int>>();val coplanar=HashMap<Pair<Int,Int>,Int>();var coplanarCount=0
        fun edge(a:Vec3,b:Vec3,flat:Boolean=false) { val i=vertex(a);val j=vertex(b);if(i==j)return;val e=min(i,j) to max(i,j);if(flat)coplanar[e]=(coplanar[e] ?: 0)+1 else edges.add(e) }
        for(t in 0 until mesh.triangleCount) {
            if(t%2048==0) MeasurementJobs.checkDeadline()
            val p=(0..2).map { mesh.vertices[mesh.indices[t*3+it]] };val d=p.map { (it-origin).dot(n) }
            if(d.all { abs(it)<=eps }) { coplanarCount++;for(i in 0..2)edge(p[i],p[(i+1)%3],true);continue }
            val hits=mutableListOf<Vec3>()
            for(i in 0..2) { val j=(i+1)%3;if(abs(d[i])<=eps)hits.add(p[i]);if(d[i]*d[j]<0 && abs(d[i])>eps && abs(d[j])>eps) hits.add(p[i]+(p[j]-p[i])*(d[i]/(d[i]-d[j]))) }
            val unique=hits.distinctBy(::vertex)
            if(unique.size==2)edge(unique[0],unique[1])
        }
        coplanar.filterValues { it==1 }.keys.forEach(edges::add)
        require(edges.size<=30_000) { "Zu viele Schnittkanten; wähle einen kleineren Bereich oder ein einfacheres Netz." }
        val adjacency=HashMap<Int,MutableList<Int>>();edges.forEach { (a,b) -> adjacency.getOrPut(a){mutableListOf()}.add(b);adjacency.getOrPut(b){mutableListOf()}.add(a) }
        val ambiguous=adjacency.values.any { it.size>2 } || coplanar.values.any { it>2 }
        val unused=edges.toMutableSet();val paths=mutableListOf<SectionPath>()
        while(unused.isNotEmpty()) {
            val seed=unused.first();val start=listOf(seed.first,seed.second).firstOrNull { adjacency[it]!!.size==1 } ?: seed.first
            val ids=mutableListOf(start);var current=start
            while(true) {
                val next=adjacency[current]!!.firstOrNull { (min(current,it) to max(current,it)) in unused } ?: break
                unused.remove(min(current,next) to max(current,next));current=next
                if(current==start)break
                ids.add(current)
                if(adjacency[current]!!.size!=2)break
            }
            paths.add(SectionPath(ids.map { points[it] },current==start && ids.size>=3))
        }
        return MeshSection(origin,n,paths,ambiguous,coplanarCount)
    }
    fun report(section:MeshSection,tolerance:Double)=buildJsonObject {
        put("origin",section.origin.measureJson());put("normal",section.normal.measureJson());put("ambiguous",section.ambiguous);put("coplanarTriangles",section.coplanarTriangles)
        put("contours",JsonArray(section.paths.map { path -> buildJsonObject {
            put("closed",path.closed);put("points",JsonArray(path.points.map { it.measureJson() }));put("fits",JsonArray(ContourFitting.fit(path,section.normal,tolerance)))
        } }))
        put("uncertainty","Querschnitt des triangulierten Netzes. Koplanare/mehrdeutige Schnitte und offene Pfade nicht als sichere Öffnungen interpretieren. Fits sind Näherungen mit gemessenem Residuum, keine ursprünglichen CAD-Kurven.")
    }
}

internal object ContourFitting {
    private data class Circle(val center:Vec3,val radius:Double,val error:Double,val sweep:Double)
    private fun circle(p:List<Vec3>,normal:Vec3):Circle? {
        if(p.size<4)return null
        val origin=p.first();val u=(p[p.size/3]-origin).let { if(it.norm()<1e-9)return null else it.normalized() };val v=normal.cross(u).normalized()
        val xy=p.map { (it-origin).dot(u) to (it-origin).dot(v) };val mx=xy.map { it.first }.average();val my=xy.map { it.second }.average()
        var xx=0.0;var yy=0.0;var xySum=0.0;var bx=0.0;var by=0.0
        xy.forEach { (a,b) -> val x=a-mx;val y=b-my;val q=x*x+y*y;xx+=x*x;yy+=y*y;xySum+=x*y;bx+=x*q/2;by+=y*q/2 }
        val det=xx*yy-xySum*xySum;if(abs(det)<1e-15)return null
        val cx=(bx*yy-by*xySum)/det+mx;val cy=(by*xx-bx*xySum)/det+my;val center=origin+u*cx+v*cy
        val radius=p.map { (it-center).norm() }.average();if(radius<1e-7 || !radius.isFinite())return null
        // Include chord midpoints: a coarse polygon must not masquerade as a precise circle.
        val samples=p+p.zipWithNext().map { (a,b)->(a+b)*.5 };val error=samples.maxOf { abs((it-center).norm()-radius) }
        val angles=xy.map { atan2(it.second-cy,it.first-cx) };val delta=angles.zipWithNext().map { (a,b)->atan2(sin(b-a),cos(b-a)) }
        if(delta.any { it>1e-6 } && delta.any { it< -1e-6 })return null
        val sweep=delta.sum();if(abs(sweep)>2*PI+1e-5)return null
        return Circle(center,radius,error,sweep)
    }
    private fun line(p:List<Vec3>,tolerance:Double):JsonObject? {
        val a=p.first();val b=p.last();val d=b-a;val dd=d.dot(d);if(dd<1e-16)return null
        val error=p.maxOf { q -> val t=(q-a).dot(d)/dd;if(t< -1e-8 || t>1+1e-8)return null;(q-(a+d*t)).norm() }
        if(error>tolerance)return null
        return buildJsonObject { put("kind","line");put("start",a.measureJson());put("end",b.measureJson());put("maxResidualMm",error) }
    }
    private fun arc(p:List<Vec3>,normal:Vec3,tolerance:Double,closed:Boolean=false):JsonObject? {
        val c=circle(p,normal) ?: return null
        if(c.error>tolerance || abs(c.sweep)<.025)return null
        if(closed && abs(abs(c.sweep)-2*PI)>.02)return null
        // Keep measured shared endpoints so neighboring fitted spans join exactly.
        // The fitted center/radius and residual remain measurement estimates.
        return buildJsonObject { put("kind",if(closed)"circle" else "arc");put("center",c.center.measureJson());put("radius",c.radius);put("normal",normal.measureJson());put("sweepRadians",c.sweep)
            put("start",p.first().measureJson());put("mid",p[p.size/2].measureJson());put("end",p.last().measureJson());put("maxResidualMm",c.error)
            put("interpretation","Fitted circular boundary; start/mid/end use measured points for connected construction. Center/radius/residual describe the least-squares fit; compare the reconstructed surface. Possible fillet/bore/outline, not a proven feature classification.") }
    }
    private fun bezier(p:List<Vec3>,tolerance:Double):JsonObject? {
        if(p.size<6)return null
        val lengths=mutableListOf(0.0);p.zipWithNext().forEach { (a,b)->lengths.add(lengths.last()+(b-a).norm()) };val total=lengths.last();if(total<1e-9)return null
        val ts=lengths.map { it/total };var aa=0.0;var ab=0.0;var bb=0.0;var ra=Vec3(0.0,0.0,0.0);var rb=ra
        for(i in p.indices) { val t=ts[i];val s=1-t;val a=3*s*s*t;val b=3*s*t*t;val r=p[i]-p.first()*(s*s*s)-p.last()*(t*t*t);aa+=a*a;ab+=a*b;bb+=b*b;ra+=r*a;rb+=r*b }
        val det=aa*bb-ab*ab;if(abs(det)<1e-12)return null
        val c1=(ra*bb-rb*ab)*(1/det);val c2=(rb*aa-ra*ab)*(1/det)
        fun at(t:Double):Vec3 { val s=1-t;return p.first()*(s*s*s)+c1*(3*s*s*t)+c2*(3*s*t*t)+p.last()*(t*t*t) }
        var error=0.0
        for(i in 0 until p.lastIndex) for(j in 0..4) { val f=j/4.0;val q=p[i]*(1-f)+p[i+1]*f;error=max(error,(q-at(ts[i]*(1-f)+ts[i+1]*f)).norm()) }
        if(error>tolerance)return null
        return buildJsonObject { put("kind","bezier");put("controlPoints",JsonArray(listOf(p.first(),c1,c2,p.last()).map { it.measureJson() }));put("maxResidualMm",error);put("residualMethod","Chord-length parameter samples at vertices and quarter segments; not a certified curve bound") }
    }
    fun fit(path:SectionPath,normal:Vec3,tolerance:Double):List<JsonObject> {
        require(tolerance.isFinite() && tolerance in .00001..5.0)
        if(path.points.size<2)return emptyList()
        require(path.points.size<=4096) { "Kontur enthält mehr als 4096 Punkte; Fits nicht abgeschnitten." }
        val p=path.points+if(path.closed)listOf(path.points.first()) else emptyList()
        if(path.closed)arc(p,normal,tolerance,true)?.let { return listOf(it) }
        val result=mutableListOf<JsonObject>();var start=0
        while(start<p.lastIndex) {
            MeasurementJobs.checkDeadline()
            var chosen:JsonObject?=null;var end=start+1
            // Prefer simple lines/arcs before fitting freeform spans. Bounded window prevents cubic work.
            for(stop in min(p.lastIndex,start+128) downTo start+1) {
                val run=p.subList(start,stop+1)
                val fit=line(run,tolerance) ?: if(run.size>=4)arc(run,normal,tolerance) else null
                if(fit!=null) { chosen=fit;end=stop;break }
            }
            if(end-start<4) for(stop in min(p.lastIndex,start+64) downTo start+5) {
                bezier(p.subList(start,stop+1),tolerance)?.let { chosen=it;end=stop };if(end-start>=5)break
            }
            result.add(chosen ?: buildJsonObject { put("kind","line");put("start",p[start].measureJson());put("end",p[end].measureJson());put("maxResidualMm",0) });start=end
        }
        return result
    }
}
