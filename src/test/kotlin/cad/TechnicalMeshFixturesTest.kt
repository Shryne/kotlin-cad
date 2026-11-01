package cad

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.*
import kotlin.test.*

class TechnicalMeshFixturesTest {
    @Test fun `monitor mount fixture has known plate hole slot bounds and volume`() {
        val executable=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(executable));val kernel=NativeKernel(executable)
        try {
            val mesh=TechnicalMeshFixtures.monitorMount(kernel)
            assertEquals(Vec3(0.0,0.0,0.0),mesh.bounds.low);assertEquals(Vec3(120.0,80.0,6.0),mesh.bounds.high)
            val volume=mesh.indices.toList().chunked(3).sumOf { face -> mesh.vertices[face[0]].dot(mesh.vertices[face[1]].cross(mesh.vertices[face[2]]))/6.0 }
            val expected=120.0*80*6-6*(200+189*Math.PI)
            assertEquals(expected,volume,expected*.005)
        } finally { kernel.close() }
    }
    @Test fun `squat rack fixture has known posts span crossbar connector plates and repeated holes`() {
        val executable=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(executable));val kernel=NativeKernel(executable)
        try {
            val mesh=TechnicalMeshFixtures.squatRack(kernel)
            assertEquals(Vec3(-45.0,-20.0,0.0),mesh.bounds.low)
            assertEquals(Vec3(1045.0,80.0,2200.0),mesh.bounds.high)
            assertTrue(mesh.triangleCount>1000)
        } finally { kernel.close() }
    }
}
