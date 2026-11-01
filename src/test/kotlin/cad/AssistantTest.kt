package cad

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.swing.SwingUtilities
import kotlin.test.*

class AssistantTest {
    @TempDir lateinit var root: Path
    private fun fake(): Path {
        val path=root.resolve("fake-codex")
        Files.copy(Path.of("src/test/resources/fake-codex.py"),path)
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));return path
    }
    private fun edt(action: ()->Unit) { SwingUtilities.invokeAndWait(action) }
    private fun await(condition: ()->Boolean) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<until) { val result=AtomicBoolean();edt { result.set(condition()) };if(result.get()) return;Thread.sleep(25) }
        error("Assistant condition timed out")
    }
    @Test fun `child uses isolated keyring only and drops inherited secrets`() {
        val builder=assistantProcess(fake(),root.resolve("local"))
        assertEquals(root.resolve("local/codex").toString(),builder.environment()["CODEX_HOME"])
        assertFalse(builder.environment().containsKey("OPENAI_API_KEY"))
        assertFalse(builder.environment().containsKey("GITHUB_TOKEN"))
        assertTrue(builder.command().contains("cli_auth_credentials_store=\"keyring\""))
        assertTrue(builder.command().contains("forced_login_method=\"chatgpt\""))
        assertTrue(builder.command().contains("features.shell_tool=false"))
        assertTrue(builder.command().contains("default_permissions=\"cad_chat\""))
        assertTrue(builder.command().contains("permissions.cad_chat.filesystem={\":minimal\"=\"read\",\":root\"=\"deny\"}"))
        assertTrue(builder.command().contains("permissions.cad_chat.network.enabled=false"))
        assertEquals(root.resolve("local/workspace").toFile(),builder.directory())
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(root.resolve("local/codex")))
    }
    @Test fun `login streaming tools history reconnect logout and cancellation follow the protocol`() {
        val binary=fake();val opened=AtomicBoolean();val calls=CopyOnWriteArrayList<String>()
        var controller: AssistantController?=null
        edt { controller=AssistantController({ epoch,name,_ ->
            assertEquals(7,epoch);calls+=name
            CompletableFuture.completedFuture(buildJsonObject { put("selection",buildJsonObject {});put("parameters",JsonArray(emptyList())) })
        },root.resolve("local"),{ binary },{ opened.set(it.host=="auth.openai.com") }) }
        val chat=controller!!
        try {
            edt { chat.connect() };await { !chat.connecting };assertFalse(chat.connected)
            edt { chat.signIn() };await { chat.connected && chat.models.isNotEmpty() };assertTrue(opened.get())
            edt { chat.send("Setze die Breite.","project",7,buildJsonObject {}) }
            await { !chat.busy && chat.messages.lastOrNull()?.text=="Breite auf 42 mm gesetzt." }
            assertEquals(listOf("cad_get_model","cad_set_parameter"),calls.toList())
            assertFalse(Files.exists(root.resolve("local/codex/auth.json")))
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve("local/conversation.json")))
            edt { chat.rebindProject("renamed");chat.send("verboten","renamed",7,buildJsonObject {}) };await { !chat.busy && chat.messages.lastOrNull()?.text=="Nicht ausgeführt." }
            assertEquals(2,calls.size)
            assertTrue(chat.messages.any { it.text=="Breite auf 42 mm gesetzt." })
            edt { chat.send("warte","renamed",7,buildJsonObject {}) };await { chat.busy && chat.activity.isNotBlank() }
            Thread.sleep(100);edt { chat.stop() };await { !chat.busy && chat.messages.lastOrNull()?.text=="Angehalten." }
            edt { chat.close() }
            Files.writeString(root.resolve("local/codex/test-missing-rollout"),"simulate lost backend history")
            var restored: AssistantController?=null
            edt { restored=AssistantController({ _,_,_ -> CompletableFuture.completedFuture(buildJsonObject { put("selection",buildJsonObject {}) }) },root.resolve("local"),{ binary },{}) }
            val resumed=restored!!
            try {
                assertTrue(resumed.messages.any { it.text=="Breite auf 42 mm gesetzt." })
                edt { resumed.connect() };await { resumed.connected }
                edt { resumed.send("Noch einmal","renamed",7,buildJsonObject {}) };await { !resumed.busy && resumed.messages.lastOrNull()?.text=="Breite auf 42 mm gesetzt." };assertNull(resumed.error)
                edt { resumed.logout() };await { !resumed.connecting && !resumed.connected };assertTrue(resumed.messages.isEmpty())
            } finally { edt { resumed.close() } }
        } finally { if(chat.connected) edt { chat.close() } }
    }
    @Test fun `project switch stops an old conversation before a new scoped turn`() {
        val binary=fake();val local=root.resolve("local")
        Files.createDirectories(local.resolve("codex"));Files.writeString(local.resolve("codex/test-signed-in"),"protocol fixture")
        val epochs=CopyOnWriteArrayList<Long>();var controller: AssistantController?=null
        edt { controller=AssistantController({ epoch,_,_ -> epochs+=epoch;CompletableFuture.completedFuture(buildJsonObject { put("selection",buildJsonObject {}) }) },local,{ binary },{}) }
        val chat=controller!!
        try {
            edt { chat.connect() };await { chat.connected }
            edt { chat.send("warte","A",1,buildJsonObject {}) };await { chat.busy }
            edt { chat.projectChanged("B") };assertFalse(chat.busy);assertTrue(chat.messages.isEmpty())
            edt { chat.send("Breite","B",2,buildJsonObject {}) }
            await { !chat.busy && chat.messages.lastOrNull()?.text=="Breite auf 42 mm gesetzt." }
            assertEquals(listOf(2L,2L),epochs.toList())
            assertTrue(chat.messages.none { it.text=="warte" })
        } finally { edt { chat.close() } }
    }
    @Test fun `immediate delayed and failed turns retain exactly one submitted message`() {
        val binary=fake()
        for(mode in listOf("immediate","delayed","error")) {
            val local=root.resolve(mode);Files.createDirectories(local.resolve("codex"))
            Files.writeString(local.resolve("codex/test-signed-in"),"fixture")
            Files.writeString(local.resolve("codex/test-response-mode"),mode)
            var controller:AssistantController?=null
            edt { controller=AssistantController({ _,_,_ -> error("No CAD tool expected") },local,{ binary },{}) }
            val chat=controller!!
            try {
                edt { chat.connect() };await { chat.connected }
                edt { chat.send("Testauftrag",mode,1,buildJsonObject {}) }
                await { !chat.busy && (chat.error!=null || chat.messages.lastOrNull()?.text=="Testantwort.") }
                assertEquals(1,chat.messages.count { it.role=="user" && it.text=="Testauftrag" })
                if(mode=="error") assertNotNull(chat.error) else assertNull(chat.error)
            } finally { edt { chat.close() } }
        }
    }

    @Test fun `public model context removes file paths and credentials errors are redacted`() {
        val value=buildJsonObject {
            put("instance",123);put("project",buildJsonObject { put("path","/private/file.kcad");put("dirty",false) })
            put("model",buildJsonObject { put("path","/private/source.stl");put("name","Part") })
            put("sceneBodies",JsonArray(listOf(buildJsonObject { put("bounds",buildJsonObject { put("min",buildJsonObject { put("x",-5) });put("max",buildJsonObject { put("x",105) }) }) })))
        }
        val result=assistantModelInfo(value).toString()
        assertFalse(result.contains("/private"));assertFalse(result.contains("instance"));assertTrue(result.contains("Part"))
        assertTrue(result.contains("105"))
        val analysis=assistantModelInfo(buildJsonObject { put("jobId","analysis-1");put("status","complete");put("revision","abc");put("quality",buildJsonObject { put("triangles",12) });put("privatePath","/private/input.obj") })
        assertEquals("complete",analysis.getValue("status").jsonPrimitive.content)
        assertFalse(analysis.toString().contains("privatePath"));assertTrue(assistantTools.any { it.name=="cad_get_mesh_analysis" })
        val proposal=assistantModelInfo(buildJsonObject { put("proposalId","proposal-1");put("sourceRevision","rev-1");put("opening",buildJsonObject { put("proposedDiameter",22) });put("privatePath","/private/source.obj") })
        assertEquals("proposal-1",proposal.getValue("proposalId").jsonPrimitive.content);assertTrue(proposal.toString().contains("proposedDiameter"));assertFalse(proposal.toString().contains("privatePath"))
        val rack=assistantModelInfo(buildJsonObject { put("modelKind","squat-rack");put("assembly",buildJsonObject { put("partCount",5) });put("features",JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("nativeShape"))))));put("surfaceDeviation",buildJsonObject { put("unit","mm") });put("privatePath","/private/rack.obj") })
        assertEquals("squat-rack",rack.getValue("modelKind").jsonPrimitive.content);assertEquals(5,rack.getValue("assembly").jsonObject.getValue("partCount").jsonPrimitive.int)
        assertTrue(rack.toString().contains("nativeShape") && rack.toString().contains("surfaceDeviation"));assertFalse(rack.toString().contains("privatePath"))
        assertTrue(assistantModelInfo(buildJsonObject { put("legacySketchDepthProjection",true) }).getValue("legacySketchDepthProjection").jsonPrimitive.boolean)
        val secret="sk-"+"a".repeat(48)
        assertFalse(assistantError(Exception("access_token=$secret https://example.invalid/?code=private")).contains(secret))
        assertFalse(assistantError(Exception("https://example.invalid/?code=private")).contains("code=private"))
    }
}
