package cad

import kotlinx.serialization.json.*
import java.util.concurrent.*
import kotlin.math.*

/** Read-only bounded jobs; a stale report is explicitly tied to its source fingerprint. */
internal object MeasurementJobs {
    private data class Job(val revision:String,val snapshot:DocumentState?,val future:CompletableFuture<JsonObject>,@Volatile var cancelled:Boolean=false)
    private val jobs=ConcurrentHashMap<String,Job>()
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue(2),{Thread(it,"cad-measurement").apply { isDaemon=true }},ThreadPoolExecutor.AbortPolicy())
    private val deadline=ThreadLocal<Long>()
    private val cancelled=ThreadLocal<()->Boolean>()
    fun checkDeadline() { if(Thread.currentThread().isInterrupted || cancelled.get()?.invoke()==true)throw CancellationException("Messung abgebrochen.");if(System.nanoTime()>(deadline.get() ?: Long.MAX_VALUE))throw TimeoutException("Messung überschreitet 60 Sekunden; kleinere Aufgabe wählen.") }
    fun start(mesh:MeshData,snapshot:DocumentState?=null,work:()->JsonObject):JsonObject {
        require(mesh.triangleCount<=300_000)
        jobs.entries.filter { it.value.future.isDone }.forEach { if(jobs.size>=6)jobs.remove(it.key) }
        require(jobs.size<6) { "Zu viele Messaufträge." }
        val id="measurement-${java.util.UUID.randomUUID()}";val future=CompletableFuture<JsonObject>();val job=Job(MeshAnalysisJobs.revisionOf(mesh),snapshot,future);jobs[id]=job
        try { executor.execute { deadline.set(System.nanoTime()+TimeUnit.SECONDS.toNanos(60));cancelled.set { job.cancelled }
            try { checkDeadline();future.complete(work()) }catch(e:Exception){future.completeExceptionally(e)}finally{deadline.remove();cancelled.remove()} } }
        catch(e:RejectedExecutionException){jobs.remove(id);throw IllegalStateException("Messung ausgelastet.")}
        return buildJsonObject { put("jobId",id);put("sourceRevision",job.revision);put("status","running");put("modelChanged",false) }
    }
    fun read(id:String,document:DocumentState,offset:Int,limit:Int):JsonObject {
        require(offset>=0 && limit in 1..1000)
        val job=jobs[id] ?: error("Messauftrag nicht gefunden.")
        val stale=job.snapshot?.let { it!=document } ?: (document.mesh==null || MeshAnalysisJobs.revisionOf(document.mesh)!=job.revision)
        fun base(status:String)=buildJsonObject { put("jobId",id);put("sourceRevision",job.revision);put("stale",stale);put("status",status);put("unit","mm") }
        if(job.cancelled)return base("cancelled")
        if(!job.future.isDone)return base("running")
        val result=try{job.future.join()}catch(e:Exception){return JsonObject(base(if(job.cancelled)"cancelled" else "failed")+mapOf("error" to JsonPrimitive(e.cause?.message ?: e.message ?: "Messung fehlgeschlagen.")))}
        fun page(value:JsonElement):JsonElement=when(value){
            is JsonObject -> JsonObject(value.mapValues { (key,v) -> if(key=="deviationMap")JsonArray(v.jsonArray.drop(offset).take(limit)) else page(v) })
            else -> value
        }
        return JsonObject(base("complete")+page(result).jsonObject+mapOf("offset" to JsonPrimitive(offset),"limit" to JsonPrimitive(limit)))
    }
    fun requireCurrent(id:String,document:DocumentState) { val job=jobs[id] ?: error("Messauftrag nicht gefunden.");require(job.snapshot==null || job.snapshot==document) { "Messung gehört zu einem früheren Projektstand. Neu vergleichen." } }
    fun result(id:String):JsonObject { val job=jobs[id] ?: error("Messauftrag nicht gefunden.");require(job.future.isDone && !job.cancelled) { "Messung läuft noch oder wurde abgebrochen." };return job.future.join() }
    fun cancel(id:String):JsonObject { val job=jobs[id] ?: error("Messauftrag nicht gefunden.");job.cancelled=true;job.future.cancel(false);return buildJsonObject {put("jobId",id);put("cancelled",true)} }
}

internal object MeshMeasurements {
    fun sections(mesh:MeshData,origin:Vec3,normal:Vec3,offsets:List<Double>,tolerance:Double):JsonObject {
        require(offsets.size in 1..32 && offsets.all { it.isFinite() && abs(it)<=1e9 });val n=normal.normalized()
        return buildJsonObject { put("measurementKind","sections");put("fitToleranceMm",tolerance);put("sections",JsonArray(offsets.map { offset ->
            val section=MeshSections.cut(mesh,origin+n*offset,n)
            val report=MeshSections.report(section,tolerance)
            val transitions=section.paths.flatMap { path -> transitions(ContourFitting.fit(path,n,tolerance),path.closed,n) }
            JsonObject(report+mapOf("offset" to JsonPrimitive(offset),"edgeDetails" to JsonArray(transitions)))
        })) }
    }
    private fun transitions(fits:List<JsonObject>,closed:Boolean,normal:Vec3):List<JsonObject> {
        if(fits.size<3)return emptyList()
        val indices=if(closed)fits.indices.toList() else (1 until fits.lastIndex).toList()
        return indices.mapNotNull { i ->
            val previous=fits[(i+fits.size-1)%fits.size];val middle=fits[i];val next=fits[(i+1)%fits.size]
            fun kind(f:JsonObject)=f.getValue("kind").jsonPrimitive.content
            if(kind(previous)!="line" || kind(next)!="line")return@mapNotNull null
            val a=previous.getValue("end").measureVector();val b=next.getValue("start").measureVector()
            val d=(a-previous.getValue("start").measureVector()).normalized();val e=(next.getValue("end").measureVector()-b).normalized()
            val denominator=d.cross(e).dot(normal)
            if(abs(denominator)<1e-8)return@mapNotNull null
            val intersection=a+d*((b-a).cross(e).dot(normal)/denominator)
            when(kind(middle)) {
                "line" -> buildJsonObject {put("kind","chamfer-candidate");put("start",a.measureJson());put("end",b.measureJson());put("widthMm",(b-a).norm());put("setbackBeforeMm",(intersection-a).norm());put("setbackAfterMm",(intersection-b).norm());put("supportAngleDegrees",acos(d.dot(e).coerceIn(-1.0,1.0))*180/PI);put("uncertainty","Straight transition between fitted supports; may be a designed facet rather than a chamfer.")}
                "arc" -> {val center=middle.getValue("center").measureVector();val t1=normal.cross(a-center).normalized();val t2=normal.cross(b-center).normalized()
                    buildJsonObject {put("kind","round-candidate");put("radiusMm",middle.getValue("radius"));put("start",a.measureJson());put("end",b.measureJson());put("tangentMismatchBeforeDegrees",acos(abs(t1.dot(d)).coerceIn(0.0,1.0))*180/PI);put("tangentMismatchAfterDegrees",acos(abs(t2.dot(e)).coerceIn(0.0,1.0))*180/PI);put("maxResidualMm",middle.getValue("maxResidualMm"));put("uncertainty","Section radius, not proof of a constant 3D fillet radius; compare neighboring sections.")} }
                else -> null
            }
        }
    }
    fun bore(mesh:MeshData,origin:Vec3,axis:Vec3,start:Double,end:Double,steps:Int,tolerance:Double):JsonObject {
        require(start.isFinite() && end.isFinite() && end>start && abs(start)<=1e9 && abs(end)<=1e9 && steps in 3..64)
        val n=axis.normalized();val u=n.cross(if(abs(n.x)<.8)Vec3(1.0,0.0,0.0) else Vec3(0.0,1.0,0.0)).normalized();val v=n.cross(u)
        fun contains(p:Vec3,path:SectionPath):Boolean {var inside=false;val px=p.dot(u);val py=p.dot(v);for(i in path.points.indices){val a=path.points[i];val b=path.points[(i+1)%path.points.size];val ay=a.dot(v);val by=b.dot(v);if((ay>py)!=(by>py) && px<(b.dot(u)-a.dot(u))*(py-ay)/(by-ay)+a.dot(u))inside=!inside};return inside}
        val profiles=(0 until steps).map { i ->
            MeasurementJobs.checkDeadline();val depth=start+(end-start)*i/(steps-1);val center=origin+n*depth;val section=MeshSections.cut(mesh,center,n)
            val circles=if(section.ambiguous || section.coplanarTriangles>0)emptyList() else section.paths.filter { it.closed }.mapNotNull { path ->
                val fit=ContourFitting.fit(path,n,tolerance).singleOrNull()?.takeIf { it["kind"]?.jsonPrimitive?.content=="circle" } ?: return@mapNotNull null
                val c=fit.getValue("center").measureVector();if((c-center).norm()>tolerance)return@mapNotNull null
                val nesting=section.paths.count { it!==path && it.closed && contains(c,it) }
                if(nesting%2!=1)return@mapNotNull null // Require an enclosing material boundary: exterior cylinders are not bores.
                fit
            }
            val circle=circles.minByOrNull { it.getValue("radius").jsonPrimitive.double }
            buildJsonObject {put("depthMm",depth);put("measured",circle!=null);put("ambiguous",section.ambiguous || circles.size>1);if(circle!=null){put("radiusMm",circle.getValue("radius"));put("maxResidualMm",circle.getValue("maxResidualMm"))} }
        }
        val segments=mutableListOf<JsonObject>();var i=0
        while(i<profiles.lastIndex) {
            fun radius(k:Int)=profiles[k]["radiusMm"]?.jsonPrimitive?.double
            if(radius(i)==null || radius(i+1)==null){i++;continue}
            val from=i;val dz=(end-start)/(steps-1);val slope=(radius(i+1)!!-radius(i)!!)/dz;i++
            while(i<profiles.lastIndex && radius(i+1)!=null && abs(radius(i+1)!!-(radius(from)!!+slope*(i+1-from)*dz))<=tolerance)i++
            val first=profiles[from].getValue("depthMm").jsonPrimitive.double;val last=profiles[i].getValue("depthMm").jsonPrimitive.double
            segments.add(buildJsonObject {put("kind",if(abs(slope)*(last-first)<=tolerance)"cylindrical-bore" else "conical-transition-candidate");put("startDepthMm",first);put("endDepthMm",last);put("depthMm",last-first);put("startRadiusMm",radius(from)!!);put("endRadiusMm",radius(i)!!);put("includedAngleDegrees",2*atan(abs(slope))*180/PI);put("sampleCount",i-from+1);put("transitionDepthUncertaintyMm",dz);put("supportedByMultipleSections",i-from>=2)})
        }
        return buildJsonObject {put("measurementKind","bore-profile");put("origin",origin.measureJson());put("axis",n.measureJson());put("profiles",JsonArray(profiles));put("segments",JsonArray(segments));put("uncertainty","Only closed coaxial circular inner contours are measured. Missing sections remain unknown. Counterbores/countersinks are candidates from changes of radius; transition depths are bracketed by the sampling interval. Refine around transitions; a two-section cone is not confirmed.")}
    }
}
