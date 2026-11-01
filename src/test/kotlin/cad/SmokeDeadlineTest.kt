package cad

import org.junit.jupiter.api.Test
import kotlin.test.*

class SmokeDeadlineTest {
    @Test fun `fast completion needs no busy transition and delayed state can complete`() {
        var now=0L;val wait=SmokeDeadline { now }
        wait.await("submitted",30) { true }
        wait.nextPhase()
        assertFailsWith<SmokePending> { wait.await("completion",120) { false } }
        now=5_000_000_000
        wait.await("completion",120) { true }
    }
    @Test fun `timeout and backend failure identify their phase distinctly`() {
        var now=0L;val wait=SmokeDeadline { now }
        now=30_000_000_000
        assertEquals("Timeout in phase input",assertFailsWith<IllegalStateException> { wait.await("input",30) { false } }.message)
        assertEquals("Phase completion failed: Testfehler",assertFailsWith<IllegalStateException> { wait.await("completion",120,"Testfehler") { true } }.message)
        wait.nextPhase()
        assertFailsWith<SmokePending> { wait.await("submitted",30) { false } }
    }
}
