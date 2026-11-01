package cad

import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong

/** Bounded content cache. Names/IDs/appearance never affect shape keys. */
internal object GeometryCache {
    private val meshes=LinkedHashMap<Any,MeshData>(16,.75f,true)
    val hits=AtomicLong();val builds=AtomicLong()
    fun get(key:Any,create:()->MeshData):MeshData {
        AssemblyWork.check()
        synchronized(meshes) { meshes[key]?.let { hits.incrementAndGet();return it } }
        val value=create();AssemblyWork.check();builds.incrementAndGet()
        synchronized(meshes) {
            meshes[key]=value
            while(meshes.size>512 || meshes.values.sumOf { it.vertices.size*24L+it.indices.size*4L }>64L*1024*1024) meshes.remove(meshes.keys.first())
        }
        return value
    }
    fun native(name:String,shape:JsonObject,single:Boolean=false):MeshData {
        val mesh=get(listOf("native",shape,single)) {
            nativeMesh("Geometry",CadNativeGeometry.kernel.request(if(single) "evaluate_solid" else "evaluate",buildJsonObject { put("shape",shape);put("singleSolid",single) }).get(60,java.util.concurrent.TimeUnit.SECONDS))
        }
        return if(mesh.name==name) mesh else mesh.copy(name=name)
    }
    internal fun clear() { synchronized(meshes) { meshes.clear();hits.set(0);builds.set(0) } }
}
/** Only actual parameter names occurring in an expression-bearing definition affect its key. */
internal fun geometryValues(definition:String,values:Map<String,Quantity>):Map<String,Quantity> {
    val names=Regex("[\\p{L}_][\\p{L}\\p{N}_]*").findAll(definition).map { it.value }.toSet()
    return values.filterKeys { it in names }
}

internal object SketchEvaluationCache {
    private val entries=LinkedHashMap<Any,Sketch>(16,.75f,true)
    fun get(sketch:Sketch,values:Map<String,Quantity>,create:()->Sketch):Sketch {
        AssemblyWork.check()
        val key=sketch to geometryValues(sketch.json().toString(),values)
        synchronized(entries) { entries[key]?.let { return it } }
        val result=create();AssemblyWork.check()
        synchronized(entries) { entries[key]=result;while(entries.size>256) entries.remove(entries.keys.first()) }
        return result
    }
}
