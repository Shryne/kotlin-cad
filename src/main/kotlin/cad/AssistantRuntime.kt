package cad

import kotlinx.serialization.json.*
import java.io.BufferedWriter
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

internal fun assistantRoot(): Path = System.getenv("KOTLIN_CAD_ASSISTANT_HOME")?.let(Path::of)
    ?: Path.of(System.getProperty("user.home"),"Library","Application Support","Kotlin CAD","Assistant")
internal fun privateDirectory(path: Path): Path {
    require(!Files.isSymbolicLink(path)) { "Der lokale Chatordner darf keine Verknüpfung sein." }
    Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"))
    return path
}
internal fun codexExecutable(): Path {
    val candidates=listOfNotNull(System.getenv("KOTLIN_CAD_CODEX")) + listOf(
        "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex",
        "/Applications/Codex.app/Contents/Resources/codex", "/opt/homebrew/bin/codex", "/usr/local/bin/codex") +
        System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }.map { "$it/codex" }
    return candidates.map(Path::of).firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?: throw IllegalStateException("Codex wurde nicht gefunden. Installiere die Codex-App oder die Codex CLI und verbinde erneut.")
}
internal fun assistantProcess(executable: Path,root: Path): ProcessBuilder {
    val home=privateDirectory(root.resolve("codex"));val workspace=privateDirectory(root.resolve("workspace"))
    val builder=ProcessBuilder(executable.toString(),"app-server","--listen","stdio://",
        "-c","cli_auth_credentials_store=\"keyring\"","-c","forced_login_method=\"chatgpt\"",
        "-c","model_provider=\"openai\"","-c","features.shell_tool=false","-c","features.apps=false",
        "-c","features.plugins=false","-c","features.remote_plugin=false","-c","features.multi_agent=false","-c","features.hooks=false",
        "-c","features.memories=false","-c","features.shell_snapshot=false","-c","web_search=\"disabled\"",
        "-c","default_permissions=\"cad_chat\"",
        "-c","permissions.cad_chat.filesystem={\":minimal\"=\"read\",\":root\"=\"deny\"}",
        "-c","permissions.cad_chat.network.enabled=false",
        "-c","skills.bundled.enabled=false","-c","skills.include_instructions=false")
    val original=builder.environment().toMap()
    builder.environment().clear()
    for(key in listOf("HOME","PATH","TMPDIR","USER","LOGNAME","LANG","LC_ALL","SHELL","SSL_CERT_FILE")) original[key]?.let { builder.environment()[key]=it }
    builder.environment()["CODEX_HOME"]=home.toString()
    builder.directory(workspace.toFile())
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    return builder
}
internal fun assistantError(error: Throwable): String {
    val cause=generateSequence(error) { it.cause }.last()
    val message=if(cause is TimeoutException) "Die Verbindung antwortet nicht. Bitte erneut verbinden." else cause.message ?: "Die Verbindung wurde unterbrochen."
    return message.replace(Regex("""(?i)["']?(access_token|refresh_token|id_token|authorization|api_key)["']?\s*[:=]\s*["']?[^\s,}]+"""),"[Zugangsdaten entfernt]")
        .replace(Regex("sk-[A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),"[Zugangsdaten entfernt]")
        .replace(Regex("(https?://[^\\s?]+)\\?[^\\s]+"),"$1").take(400)
}

/** Local JSON-RPC pipe. Authentication is owned by Codex and stored strictly in the OS keyring. */
internal class AssistantRuntime(
    executable: Path, root: Path,
    private val event: (String,JsonObject)->Unit,
    private val tool: (String,JsonObject)->CompletableFuture<JsonObject>,
    private val disconnected: ()->Unit
): AutoCloseable {
    private val process=assistantProcess(executable,privateDirectory(root)).start()
    private val writer: BufferedWriter=process.outputStream.bufferedWriter()
    private val sequence=AtomicInteger()
    private val pending=ConcurrentHashMap<Int,CompletableFuture<JsonObject>>()
    @Volatile private var closed=false
    init {
        Thread({
            try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if(line.length<=8_000_000) receive(Json.parseToJsonElement(line).jsonObject)
            } } } catch(_: Exception) { /* Protocol content and credentials must never enter logs. */ }
            finally {
                pending.values.forEach { it.completeExceptionally(IllegalStateException("Der lokale Codex-Dienst wurde beendet.")) };pending.clear()
                if(!closed) { closed=true;process.destroy();disconnected() }
            }
        },"cad-assistant-rpc").apply { isDaemon=true;start() }
    }
    @Synchronized private fun write(message: JsonObject) { check(!closed);writer.write(message.toString());writer.newLine();writer.flush() }
    fun request(method: String,params: JsonObject=buildJsonObject {}): CompletableFuture<JsonObject> {
        val id=sequence.incrementAndGet();val result=CompletableFuture<JsonObject>()
        pending[id]=result
        try { write(buildJsonObject { put("id",id);put("method",method);put("params",params) }) }
        catch(e: Exception) { pending.remove(id);result.completeExceptionally(e) }
        result.orTimeout(45,TimeUnit.SECONDS).whenComplete { _,_ -> pending.remove(id) }
        return result
    }
    fun notify(method: String) = write(buildJsonObject { put("method",method) })
    private fun answer(id: JsonElement,value: JsonObject) { runCatching { write(buildJsonObject { put("id",id);put("result",value) }) } }
    private fun receive(message: JsonObject) {
        val method=message["method"]?.jsonPrimitive?.content
        val id=message["id"]
        if(method==null && id!=null) {
            val result=pending.remove(id.jsonPrimitive.int) ?: return
            val error=message["error"]?.jsonObject
            if(error!=null) result.completeExceptionally(IllegalStateException(error["message"]?.jsonPrimitive?.content ?: "Codex-Anfrage fehlgeschlagen."))
            else result.complete(message["result"] as? JsonObject ?: buildJsonObject {})
            return
        }
        val params=message["params"] as? JsonObject ?: buildJsonObject {}
        if(method==null) return
        if(id==null) { event(method,params);return }
        when(method) {
            "item/tool/call" -> {
                val name=params["tool"]?.jsonPrimitive?.content.orEmpty()
                val args=params["arguments"] as? JsonObject ?: buildJsonObject {}
                val result=runCatching { tool(name,args) }.getOrElse { CompletableFuture.failedFuture(it) }
                result.whenComplete { value,error -> answer(id,buildJsonObject {
                    put("success",error==null)
                    put("contentItems",JsonArray(listOf(buildJsonObject {
                        if(error==null && value["mimeType"]?.jsonPrimitive?.content=="image/png") {
                            put("type","inputImage");put("imageUrl","data:image/png;base64,"+value.getValue("data").jsonPrimitive.content)
                        } else { put("type","inputText");put("text",if(error==null) value.toString() else assistantError(error)) }
                    })))
                }) }
            }
            "item/commandExecution/requestApproval","item/fileChange/requestApproval" -> answer(id,buildJsonObject { put("decision","decline") })
            "item/permissions/requestApproval" -> answer(id,buildJsonObject { put("permissions",buildJsonObject {});put("scope","turn") })
            else -> runCatching { write(buildJsonObject { put("id",id);put("error",buildJsonObject { put("code",-32601);put("message","Diese Funktion steht im CAD-Chat nicht zur Verfügung.") }) }) }
        }
    }
    override fun close() {
        closed=true
        runCatching { writer.close() }
        process.destroy()
        CompletableFuture.delayedExecutor(2,TimeUnit.SECONDS).execute { if(process.isAlive) process.destroyForcibly() }
        pending.values.forEach { it.cancel(false) };pending.clear()
    }
}
