package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NativeKernelTest {
    @Test fun `native worker evaluates validates solves and restarts without an interpreter`() {
        val binary=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
        assumeTrue(Files.isExecutable(binary),"Build the private native kernel with scripts/build-native.sh")
        NativeKernel(binary).use { kernel ->
            assertTrue(kernel.request("ping").get(5,TimeUnit.SECONDS).getValue("native").jsonPrimitive.boolean)
            val result=kernel.request("evaluate",buildJsonObject { put("shape",buildJsonObject {
                put("type","box");put("x",10);put("y",20);put("z",30)
            }) }).get(5,TimeUnit.SECONDS)
            assertEquals(6000.0,result.getValue("volume").jsonPrimitive.double,1e-6)
            assertTrue(result.getValue("indices").jsonArray.size>=36)
            fun polygon(vararg points: List<Int>)=JsonArray(points.map { JsonArray(it.map(::JsonPrimitive)) })
            val hole=kernel.request("evaluate",buildJsonObject { put("shape",buildJsonObject {
                put("type","extrude");put("outer",polygon(listOf(0,0,0),listOf(20,0,0),listOf(20,20,0),listOf(0,20,0)))
                put("holes",JsonArray(listOf(polygon(listOf(5,5,0),listOf(10,5,0),listOf(10,10,0),listOf(5,10,0)))))
                put("vector",JsonArray(listOf(0,0,4).map(::JsonPrimitive)))
            }) }).get(5,TimeUnit.SECONDS)
            assertEquals(1500.0,hole.getValue("volume").jsonPrimitive.double,1e-6)
            assertFails { kernel.request("evaluate",buildJsonObject { put("shape",buildJsonObject { put("type","box");put("x",-1);put("y",1);put("z",1) }) }).get(5,TimeUnit.SECONDS) }
            assertEquals(1,kernel.request("ping").get(5,TimeUnit.SECONDS).getValue("protocol").jsonPrimitive.int)
            val tests=kernel.request("self_test")
            kernel.cancel()
            runCatching { tests.get(5,TimeUnit.SECONDS) }
            assertTrue(kernel.request("ping").get(5,TimeUnit.SECONDS).getValue("native").jsonPrimitive.boolean)
            val gate=kernel.request("self_test").get(10,TimeUnit.SECONDS)
            assertEquals(5,gate.getValue("fixtures").jsonArray.size)
            assertTrue(gate.getValue("solver").jsonObject.getValue("conflictPreservesInput").jsonPrimitive.boolean)
        }
    }
}
