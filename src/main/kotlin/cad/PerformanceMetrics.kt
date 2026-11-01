package cad

import kotlin.math.ceil

internal data class PerformanceSnapshot(val fps: Double,val meanMs: Double,val p95Ms: Double,val windowSeconds: Double,
    val triangles: Int,val bodies: Int,val memoryMiB: Long)

/** FX pulse intervals, not GPU presentation times. Bounded two-second rolling window. */
internal class PulseMetrics {
    private val samples=ArrayDeque<Pair<Long,Double>>()
    private var previous=0L
    fun pulse(now: Long) {
        if(previous!=0L && now>previous) samples.addLast(now to (now-previous)/1_000_000.0)
        previous=now
        while(samples.isNotEmpty() && (now-samples.first().first>2_000_000_000L || samples.size>1200)) samples.removeFirst()
    }
    fun snapshot(triangles: Int,bodies: Int,memoryMiB: Long): PerformanceSnapshot {
        val intervals=samples.map { it.second }.sorted()
        val mean=intervals.average().takeIf { it.isFinite() } ?: 0.0
        return PerformanceSnapshot(if(mean>0) 1000/mean else 0.0,mean,
            if(intervals.isEmpty()) 0.0 else intervals[(ceil(intervals.size*.95).toInt()-1).coerceAtLeast(0)],
            intervals.sum()/1000,triangles,bodies,memoryMiB)
    }
}
