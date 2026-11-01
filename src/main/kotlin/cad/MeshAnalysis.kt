package cad

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.*
import kotlin.math.*

/** Bounded, deterministic local diagnostics for an imported triangle mesh. */
internal object MeshAnalysisJobs {
    private fun vector(p:Vec3)=buildJsonObject { put("x",p.x);put("y",p.y);put("z",p.z) }
    private data class Job(val id:String,@Volatile var revision:String,val future:CompletableFuture<JsonObject>,@Volatile var cancelled:Boolean=false)
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue(4),{Thread(it,"cad-mesh-analysis").apply { isDaemon=true }},ThreadPoolExecutor.AbortPolicy())
    private val jobs=ConcurrentHashMap<String,Job>()
    fun start(mesh:MeshData,unit:String):String {
        require(mesh.triangleCount<=300_000) { "Die lokale Analyse ist auf 300 000 Dreiecke begrenzt, damit die Oberfläche bedienbar bleibt." }
        jobs.values.filter { it.future.isDone }.forEach { if(jobs.size>=8) jobs.remove(it.id,it) }
        require(jobs.size<8) { "Zu viele Netzanalyse-Aufträge." }
        val id="analysis-${java.util.UUID.randomUUID()}"
        val placeholder=CompletableFuture<JsonObject>()
        val job=Job(id,"pending",placeholder);jobs[id]=job
        try { worker.execute {
            try {
                val stopped={ job.cancelled || Thread.currentThread().isInterrupted }
                val revision=fingerprint(mesh,stopped);job.revision=revision
                placeholder.complete(analyze(mesh,unit,revision,stopped))
            }
            catch(e:Throwable) { placeholder.completeExceptionally(e) }
        } } catch(e:RejectedExecutionException) { jobs.remove(id);throw IllegalStateException("Der lokale Analyseplatz ist ausgelastet.") }
        return id
    }
    fun read(id:String,offset:Int,limit:Int):JsonObject {
        require(offset>=0 && limit in 1..100) { "Analyse-Seiten benötigen offset ≥ 0 und limit 1–100." }
        val job=jobs[id] ?: throw IllegalArgumentException("Analyseauftrag existiert nicht oder ist abgelaufen.")
        if(!job.future.isDone) return buildJsonObject { put("jobId",id);put("revision",job.revision);put("status","running") }
        val result=try { job.future.join() } catch(e:CompletionException) { throw IllegalStateException(e.cause?.message ?: "Die Netzanalyse ist fehlgeschlagen.") }
        val components=result.getValue("components").jsonArray;val faces=result.getValue("planarPatches").jsonArray;val contours=result.getValue("contours").jsonArray;val cylinders=result.getValue("cylindricalPatches").jsonArray;val patterns=result.getValue("repeatedCylindricalPatterns").jsonArray;val candidates=result.getValue("featureCandidates").jsonArray;val unsupported=result.getValue("unknowns").jsonArray
        return buildJsonObject {
            put("jobId",id);put("revision",job.revision);put("status","complete");put("unit",result.getValue("unit"));put("bounds",result.getValue("bounds"));put("quality",result.getValue("quality"))
            put("components",JsonArray(components.drop(offset).take(limit)));put("componentCount",result.getValue("componentCount"));put("componentsTruncated",result["componentsTruncated"] ?: JsonPrimitive(false));put("componentOffset",offset)
            put("planarPatches",JsonArray(faces.drop(offset).take(limit)));put("planarPatchCount",result.getValue("planarPatchCount"));put("planarPatchOffset",offset)
            put("contours",JsonArray(contours.drop(offset).take(limit)));put("contourCount",result.getValue("contourCount"));put("contourOffset",offset)
            put("featureCandidates",JsonArray(candidates.drop(offset).take(limit)));put("featureCandidateCount",result.getValue("featureCandidateCount"));put("featureCandidatesTruncated",result.getValue("featureCandidatesTruncated"));put("featureCandidateOffset",offset)
            put("cylindricalPatches",JsonArray(cylinders.drop(offset).take(limit)));put("cylindricalPatchCount",result.getValue("cylindricalPatchCount"));put("cylindricalPatchOffset",offset)
            put("repeatedCylindricalPatterns",JsonArray(patterns.drop(offset).take(limit)));put("repeatedCylindricalPatternCount",patterns.size);put("repeatedCylindricalPatternOffset",offset)
            put("unknowns",JsonArray(unsupported.drop(offset).take(limit)));put("unknownCount",unsupported.size);put("unknownOffset",offset)
            put("pagination",buildJsonObject { put("offset",offset);put("limit",limit);put("nextOffset",(offset+limit).takeIf { it<maxOf(components.size,faces.size,contours.size,cylinders.size,patterns.size,candidates.size,unsupported.size) }?.let(::JsonPrimitive) ?: JsonNull) })
            put("capability","Mesh topology, approximate planar patches, closed contour boundary candidates, cylindrical surface fits and groups of repeated parallel cylindrical patches are measured. A contour is marked inner only when a containing boundary loop was detected on the same patch; unclassified loops may be openings or outside patch edges. Boundary loops describe mesh geometry, not certain holes, part boundaries inside fused meshes or manufacturing intent.")
        }
    }
    internal fun completedResult(id:String):JsonObject {
        val job=jobs[id] ?: throw IllegalArgumentException("Analyseauftrag existiert nicht oder ist abgelaufen.")
        require(job.future.isDone) { "Die Netzanalyse läuft noch." }
        return try { job.future.join() } catch(e:CompletionException) { throw IllegalStateException(e.cause?.message ?: "Die Netzanalyse ist fehlgeschlagen.") }
    }
    internal fun revisionOf(mesh:MeshData)=fingerprint(mesh) { false }
    fun cancel(id:String):JsonObject {
        val job=jobs[id] ?: throw IllegalArgumentException("Analyseauftrag existiert nicht oder ist abgelaufen.")
        job.cancelled=true;job.future.cancel(true);jobs.remove(id,job)
        return buildJsonObject { put("jobId",id);put("cancelled",true);put("modelChanged",false) }
    }
    private fun fingerprint(mesh:MeshData,stopped:()->Boolean):String {
        val digest=MessageDigest.getInstance("SHA-256")
        val bytes=ByteArray(8)
        fun integer(value:Long) { for(i in 0..7) bytes[i]=(value ushr (i*8)).toByte();digest.update(bytes) }
        integer(mesh.vertices.size.toLong());integer(mesh.indices.size.toLong())
        mesh.vertices.forEachIndexed { i,p -> if(i%4096==0 && stopped()) throw CancellationException("Die Netzanalyse wurde abgebrochen.");integer(java.lang.Double.doubleToLongBits(p.x));integer(java.lang.Double.doubleToLongBits(p.y));integer(java.lang.Double.doubleToLongBits(p.z)) }
        mesh.indices.forEachIndexed { i,v -> if(i%16384==0 && stopped()) throw CancellationException("Die Netzanalyse wurde abgebrochen.");integer(v.toLong()) }
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }
    private data class T(val normal:Vec3,val area:Double,val plane:Double)
    private fun analyze(mesh:MeshData,unit:String,revision:String,stopped:()->Boolean):JsonObject {
        val n=mesh.triangleCount;val weld=HashMap<String,Int>(mesh.vertices.size.coerceAtMost(1_000_000));val weldPoints=ArrayList<Vec3>();var nextVertex=0
        fun welded(p:Vec3):Int { val key="${round(p.x*1e6).toLong()}:${round(p.y*1e6).toLong()}:${round(p.z*1e6).toLong()}";return weld.getOrPut(key) { nextVertex++;weldPoints.add(p);nextVertex-1 } }
        val triangles=ArrayList<T>(n);val edgeCounts=HashMap<Long,IntArray>(n*2);val adjacency=LongArray(n*3);var adjacencyCount=0;val parent=IntArray(n) { it };val rank=ByteArray(n)
        fun root(v0:Int):Int { var v=v0;while(parent[v]!=v) { parent[v]=parent[parent[v]];v=parent[v] };return v }
        fun union(a:Int,b:Int) { var x=root(a);var y=root(b);if(x==y)return;if(rank[x]<rank[y]) { val t=x;x=y;y=t };parent[y]=x;if(rank[x]==rank[y])rank[x]++ }
        fun edge(a:Int,b:Int):Long { val lo=min(a,b);val hi=max(a,b);return (lo.toLong() shl 32) xor (hi.toLong() and 0xffffffffL) }
        var degenerate=0;var areaTotal=0.0
        for(t in 0 until n) {
            if(t%2048==0) { if(stopped()) throw CancellationException("Die Netzanalyse wurde abgebrochen.") }
            val ia=mesh.indices[t*3];val ib=mesh.indices[t*3+1];val ic=mesh.indices[t*3+2];val a=mesh.vertices[ia];val b=mesh.vertices[ib];val c=mesh.vertices[ic]
            val cross=(b-a).cross(c-a);val twice=sqrt(cross.dot(cross));val area=twice/2
            if(area<=1e-12) degenerate++ else areaTotal+=area
            val normal=if(twice<=1e-12) Vec3(0.0,0.0,0.0) else cross*(1/twice)
            val plane=normal.dot(a)
            triangles.add(T(normal,area,plane))
            for((u,v) in listOf(ia to ib,ib to ic,ic to ia)) { val va=welded(mesh.vertices[u]);val vb=welded(mesh.vertices[v]);val key=edge(va,vb);val incidence=edgeCounts[key];if(incidence==null) edgeCounts[key]=intArrayOf(t,1,-1) else { union(t,incidence[0]);if(incidence[1]==1) { incidence[2]=t;adjacency[adjacencyCount++]=(incidence[0].toLong() shl 32) or (t.toLong() and 0xffffffffL) };incidence[1]++ } }
        }
        val groups=triangles.indices.groupBy(::root).values.sortedBy { it.minOrNull() ?: Int.MAX_VALUE }
        val components=groups.take(256).mapIndexed { i,indices ->
            val points=indices.asSequence().flatMap { t -> val k=t*3;sequenceOf(mesh.vertices[mesh.indices[k]],mesh.vertices[mesh.indices[k+1]],mesh.vertices[mesh.indices[k+2]]) }
            val bounds=Bounds(Vec3(points.minOf { it.x },points.minOf { it.y },points.minOf { it.z }),Vec3(points.maxOf { it.x },points.maxOf { it.y },points.maxOf { it.z }))
            buildJsonObject {
                put("id","component-${i+1}-${indices.first()}")
                put("triangles",indices.size)
                put("dimensions",vector(bounds.size))
                put("bounds",buildJsonObject { put("min",vector(bounds.low));put("max",vector(bounds.high)) })
                put("surfaceArea",indices.sumOf { triangles[it].area })
                put("fitError","not fitted")
            }
        }
        data class PlaneKey(val nx:Int,val ny:Int,val nz:Int,val d:Int)
        val planeGroups=triangles.indices.filter { triangles[it].area>1e-12 }.groupBy { i -> val t=triangles[i];var nx=t.normal.x;var ny=t.normal.y;var nz=t.normal.z;var d=t.plane
            if(nx< -1e-8 || abs(nx)<1e-8 && (ny< -1e-8 || abs(ny)<1e-8 && nz<0)) { nx=-nx;ny=-ny;nz=-nz;d=-d }
            PlaneKey(round(nx*500).toInt(),round(ny*500).toInt(),round(nz*500).toInt(),round(d*10).toInt())
        }.entries.sortedByDescending { entry -> entry.value.sumOf { triangles[it].area } }.take(64)
        val planes=planeGroups.mapIndexed { i,e ->
            val sample=triangles[e.value.first()]
            val error=sqrt(e.value.sumOf { tri -> val t=triangles[tri];(0..2).sumOf { j -> val p=mesh.vertices[mesh.indices[tri*3+j]];val d=abs(sample.normal.dot(p)-sample.plane);d*d } }/(e.value.size*3))
            buildJsonObject { put("id","plane-${i+1}");put("normal",buildJsonObject { put("x",sample.normal.x);put("y",sample.normal.y);put("z",sample.normal.z) });put("offset",sample.plane);put("triangles",e.value.size);put("area",e.value.sumOf { triangles[it].area });put("fitErrorMm",error);put("method","RMS vertex distance to representative fitted plane; patch candidates use quantized triangle normals") }
        }
        val contours=MeshSurfaceFitting.contours(mesh,planeGroups.map { it.value },edgeCounts,weldPoints,stopped)
        val cylinders=MeshSurfaceFitting.cylinders(mesh,triangles.map { it.normal },adjacency,adjacencyCount,stopped)
        val patterns=MeshSurfaceFitting.repeatedPatterns(cylinders)
        val allCandidates=MeshSurfaceFitting.featureCandidates(revision,planes,contours,cylinders,patterns)
        val candidates=allCandidates.take(2048)
        val low=mesh.bounds.low;val high=mesh.bounds.high
        val openEdges=edgeCounts.values.count { it[1]==1 }
        return buildJsonObject {
            put("revision",revision);put("unit",unit);put("bounds",buildJsonObject { put("min",vector(low));put("max",vector(high));put("dimensions",vector(mesh.bounds.size)) })
            put("components",JsonArray(components));put("componentCount",groups.size);put("componentsTruncated",groups.size>components.size);put("planarPatches",JsonArray(planes));put("planarPatchCount",planes.size);put("contours",JsonArray(contours));put("contourCount",contours.size);put("cylindricalPatches",JsonArray(cylinders));put("cylindricalPatchCount",cylinders.size);put("repeatedCylindricalPatterns",JsonArray(patterns));put("featureCandidates",JsonArray(candidates));put("featureCandidateCount",allCandidates.size);put("featureCandidatesTruncated",allCandidates.size>candidates.size)
            val nonManifold=edgeCounts.values.count { it[1]>2 }
            put("quality",buildJsonObject { put("triangles",n);put("vertices",mesh.vertices.size);put("surfaceArea",areaTotal);put("degenerateTriangles",degenerate);put("openEdges",openEdges);put("nonManifoldEdges",nonManifold);put("closedManifold",openEdges==0 && nonManifold==0);put("connectedComponents",groups.size) })
            put("unknowns",JsonArray(listOf("Hole and slot classification","Repeated hole/profile patterns","Part boundaries inside fused meshes","Parametric feature intent" ).map(::JsonPrimitive)))
        }
    }
}
