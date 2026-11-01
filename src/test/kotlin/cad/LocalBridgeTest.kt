package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class LocalBridgeTest {
    @Test fun localCommandsRoundTripUnicodeAndLargeResponses() = withDirectory { directory ->
        LocalBridge(directory) { request ->
            CompletableFuture.completedFuture(buildJsonObject {
                put("action", request.requiredString("action")); put("data", "Größe ✓".repeat(10000))
            })
        }.use { bridge ->
            val result = BridgeClient(directory).call(buildJsonObject { put("action", "test") })
            assertEquals("test", result.requiredString("action"))
            assertEquals("Größe ✓".repeat(10000), result.requiredString("data"))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(bridge.socketPath))
        }
        assertFalse(Files.exists(directory.resolve("cad-${ProcessHandle.current().pid()}.sock")))
    }
    @Test fun failedCommandDoesNotBreakNextRequest() = withDirectory { directory ->
        LocalBridge(directory) { request ->
            if (request.requiredString("action") == "fail")
                CompletableFuture.failedFuture(IllegalArgumentException("Kein Modell"))
            else CompletableFuture.completedFuture(buildJsonObject { put("status", "ok") })
        }.use {
            val client = BridgeClient(directory)
            val error = assertFailsWith<IllegalStateException> {
                client.call(buildJsonObject { put("action", "fail") })
            }
            assertContains(error.message!!, "Kein Modell")
            assertEquals("ok", client.call(buildJsonObject { put("action", "get") }).requiredString("status"))
        }
    }
    @Test fun missingViewerGivesUsefulError() = withDirectory { directory ->
        assertContains(assertFailsWith<IllegalStateException> {
            BridgeClient(directory).call(buildJsonObject { put("action", "get") })
        }.message!!, "Viewer")
    }
    @Test fun localFramesAreBoundedAndMustEndWithNewline() {
        assertFailsWith<IllegalArgumentException> { readFrame(ByteArrayInputStream("abcdef\n".toByteArray()), 3) }
        assertFailsWith<IllegalStateException> { readFrame(ByteArrayInputStream("abc".toByteArray()), 10) }
        assertEquals("✓", readFrame(ByteArrayInputStream("✓\n".toByteArray()), 10))
    }
    @Test fun inputsRejectWrongTypesAndOutOfRangeNumbers() {
        val input = Json.parseToJsonElement("""{"visible":"true","zoom":100,"path":12,"pitch":"10"}""").jsonObject
        assertFailsWith<IllegalArgumentException> { input.optionalBoolean("visible") }
        assertFailsWith<IllegalArgumentException> { input.number("zoom", 1.0, 0.1, 10.0) }
        assertFailsWith<IllegalArgumentException> { input.requiredString("path") }
        assertFailsWith<IllegalArgumentException> { input.number("pitch", 0.0, -180.0, 180.0) }
    }
    private fun withDirectory(test: (Path) -> Unit) {
        // Short UNIX socket paths work within macOS's 104-byte address limit.
        val directory = Files.createTempDirectory(Path.of("/private/tmp"), "cad-test-")
        try { test(directory) } finally { Files.list(directory).use { it.forEach(Files::deleteIfExists) }; Files.delete(directory) }
    }
}
