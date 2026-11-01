package cad

import kotlinx.serialization.json.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class AssemblyWork(val total:Int,val progress:(String,Int)->Unit={_,_->}) : AutoCloseable {
    val kernel=NativeKernel();val cancelled=AtomicBoolean(false);private val completed=AtomicInteger()
    fun cancel() { cancelled.set(true);kernel.cancel() }
    fun checkCancelled() { if(cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Vorgang abgebrochen; Projekt unverändert.") }
    fun <T> run(block:()->T):T { current.set(this);try { checkCancelled();return block() } finally { current.remove() } }
    override fun close() { kernel.close() }
    companion object {
        val current=ThreadLocal<AssemblyWork?>()
        fun check() { current.get()?.checkCancelled() }
        fun step(message:String) { current.get()?.let { it.checkCancelled();it.progress(message,it.completed.incrementAndGet().coerceAtMost(it.total)) } }
    }
}
internal data class BatchCandidate(val document:DocumentState,val changedIds:List<String>)
/** Stage definitions first. Only the final graph must be valid; never commit intermediate states. */
internal fun DocumentState.assemblyBatch(operations:JsonArray):BatchCandidate {
    require(operations.size in 1..256) { "Eine Transaktion benötigt 1 bis 256 Operationen." }
    var next=copy(features=effectiveFeatures(),sketches=sketches.map { it.copy(depth=null) })
    val areas=mutableListOf<JsonObject>();val changed=linkedSetOf<String>()
    operations.forEachIndexed { index,value ->
        AssemblyWork.check()
        try {
            val op=value.jsonObject
            when(op.requiredString("op")) {
                "set_parameter" -> {
                    val name=ParameterEvaluator.name(op.requiredString("name"));val parameter=Parameter(name,ParameterEvaluator.name(op.requiredString("expression")))
                    next=next.copy(parameters=next.parameters.filterNot { it.name==name }+parameter)
                }
                "delete_parameter" -> { val name=op.requiredString("name");require(next.parameters.any { it.name==name });next=next.copy(parameters=next.parameters.filterNot { it.name==name }) }
                "put_feature" -> {
                    val feature=CadFeature.read(op.getValue("feature").jsonObject)
                    val exists=next.features.any { it.id==feature.id }
                    if(exists) require(next.features.first { it.id==feature.id }::class==feature::class) { "Featuretyp einer bestehenden ID darf nicht geändert werden." }
                    next=next.copy(features=if(exists) next.features.map { if(it.id==feature.id) feature else it } else next.features+feature);changed.add(feature.id)
                }
                "delete_feature" -> {
                    val id=op.requiredString("id");require(next.features.any { it.id==id }) { "Feature '$id' fehlt." }
                    next=next.copy(features=next.features.filterNot { it.id==id },bodyColors=next.bodyColors-id,display=next.display.copy(hiddenBodyIds=next.display.hiddenBodyIds-id));changed.add(id)
                }
                "set_position" -> {
                    val id=op.requiredString("id");val feature=next.features.firstOrNull { it.id==id } ?: error("Feature fehlt.");val j=feature.json()
                    next=next.copy(features=next.features.map { if(it.id==id) feature.withPosition(op["x"]?.jsonPrimitive?.content ?: j.requiredString("x"),op["y"]?.jsonPrimitive?.content ?: j.requiredString("y"),op["z"]?.jsonPrimitive?.content ?: j.requiredString("z")) else it });changed.add(id)
                }
                "set_body_color" -> {
                    val id=op.requiredString("bodyId");require(next.features.any { it.id==id } || id=="import" && next.mesh!=null) { "Körper fehlt." }
                    val color=op["color"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content?.let(::checkedColor)
                    next=next.copy(bodyColors=next.bodyColors+(id to (next.bodyColors[id] ?: BodyColors()).copy(color=color)));changed.add(id)
                }
                "set_area_color" -> { areas.add(op);changed.add(op.requiredString("bodyId")) }
                "remove_area_color" -> { areas.add(op);changed.add(op.requiredString("bodyId")) }
                else -> error("Unbekannte Batch-Operation.")
            }
        } catch(e:Exception) { throw IllegalArgumentException("Operation ${index+1}: ${e.message}",e) }
        AssemblyWork.step("Operation ${index+1}/${operations.size}")
    }
    ParameterEvaluator.evaluate(next.parameters)
    next=next.canonicalFeatures();next.evaluatedModel()
    areas.forEach { op -> next=if(op.requiredString("op")=="remove_area_color") next.withoutColorArea(op.requiredString("bodyId"),op.requiredString("areaId")) else next.withColorArea(op.requiredString("bodyId"),next.colorAreaFrom(op)) }
    AssemblyWork.check()
    return BatchCandidate(next,changed.toList())
}
internal class AssemblyTask(val id:String,val kind:String,val work:AssemblyWork,val snapshot:DocumentState) {
    @Volatile var status="running"
    @Volatile var message="Vorbereitung"
    @Volatile var completed=0
    @Volatile var result:JsonObject?=null
    fun json()=buildJsonObject {
        put("jobId",id);put("kind",kind);put("status",status);put("message",message);put("completed",completed);put("total",work.total)
        put("cancellable",status=="running");put("modelChanged",status=="complete" && kind=="batch")
        result?.let { put("result",it) }
    }
}
