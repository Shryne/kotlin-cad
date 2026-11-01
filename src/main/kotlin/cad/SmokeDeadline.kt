package cad

/** Nonblocking phase deadline for native smoke harnesses. Clock injection keeps timeout tests deterministic. */
internal class SmokeDeadline(private val clock:()->Long=System::nanoTime) {
    private var started=clock()
    fun nextPhase() { started=clock() }
    fun await(label:String,timeoutSeconds:Long,error:String?=null,condition:()->Boolean) {
        check(error==null) { "Phase $label failed: $error" }
        if(condition()) return
        check(clock()-started<timeoutSeconds*1_000_000_000) { "Timeout in phase $label" }
        throw SmokePending()
    }
}
internal class SmokePending:RuntimeException(null,null,false,false)
