package cad

import org.junit.jupiter.api.Test
import kotlin.test.*

class PerformanceMetricsTest {
    @Test fun `pulse window reports real intervals and expires old samples`() {
        val metrics=PulseMetrics()
        assertEquals(0.0,metrics.snapshot(0,0,0).fps)
        for(i in 1..100) metrics.pulse(i*10_000_000L)
        val fast=metrics.snapshot(12,1,20)
        assertEquals(100.0,fast.fps,1e-6);assertEquals(10.0,fast.p95Ms)
        for(i in 1..150) metrics.pulse(1_000_000_000L+i*20_000_000L)
        val slow=metrics.snapshot(24,2,30)
        assertEquals(50.0,slow.fps,1e-6);assertEquals(20.0,slow.p95Ms)
        assertTrue(slow.windowSeconds<=2.1);assertEquals(24,slow.triangles)
    }
}
