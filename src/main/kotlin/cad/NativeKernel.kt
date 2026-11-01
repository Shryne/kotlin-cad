package cad

import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** A private native worker. No shell, interpreter, system OCCT or inherited credentials. */
internal class NativeKernel(private val executable: Path = nativeKernelExecutable()): AutoCloseable {
    private val sequence=AtomicInteger()
    private val pending=ConcurrentHashMap<Int,CompletableFuture<JsonObject>>()
    private var process: Process?=null
    private var writer: java.io.BufferedWriter?=null
    private var closed=false
    @Synchronized private fun start() {
        check(!closed) { "Der Geometriekern wurde geschlossen." }
        if(process?.isAlive==true) return
        check(Files.isRegularFile(executable) && Files.isExecutable(executable)) { "Der mitgelieferte Geometriekern fehlt." }
        val builder=ProcessBuilder(executable.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.DISCARD)
        val environment=builder.environment();val temporary=environment["TMPDIR"];environment.clear()
        environment["PATH"]="/usr/bin:/bin";environment["LANG"]="en_US.UTF-8";temporary?.let { environment["TMPDIR"]=it }
        val child=builder.start();process=child;writer=child.outputStream.bufferedWriter()
        Thread({
            try {
                child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    require(line.length<=32_000_000) { "Geometrieantwort ist zu groß." }
                    val response=Json.parseToJsonElement(line).jsonObject
                    response["id"]?.jsonPrimitive?.intOrNull?.let { id -> pending.remove(id)?.let { future ->
                        val error=response["error"] as? JsonObject
                        if(error!=null) future.completeExceptionally(IllegalArgumentException(error["message"]?.jsonPrimitive?.content ?: "Geometrieoperation fehlgeschlagen."))
                        else future.complete(response.getValue("result").jsonObject)
                    } }
                } }
            } catch(_: Exception) { }
            finally { synchronized(this) {
                if(process===child) { process=null;writer=null;child.destroyForcibly();failPending("Der Geometriekern wurde beendet. Die nächste Anfrage startet ihn neu.") }
            } }
        },"cad-native-reader").apply { isDaemon=true;start() }
    }
    @Synchronized fun request(operation: String,arguments: JsonObject=buildJsonObject {},timeoutSeconds: Long=30): CompletableFuture<JsonObject> {
        val future=CompletableFuture<JsonObject>()
        try {
            require(operation in setOf("ping","evaluate_solid","solid_info","interference","evaluate","inspect_edges","solve","self_test")) { "Unbekannte Geometrieoperation." }
            require(timeoutSeconds in 1..120) { "Ungültiges Zeitlimit." }
            require(pending.size<32) { "Zu viele gleichzeitige Geometrieanfragen." }
            start()
            val id=sequence.incrementAndGet();val message=JsonObject(arguments+mapOf("id" to JsonPrimitive(id),"operation" to JsonPrimitive(operation))).toString()
            require(message.length<=2_000_000) { "Geometrieanfrage ist zu groß." }
            pending[id]=future
            writer!!.apply { write(message);newLine();flush() }
            val child=process
            CompletableFuture.delayedExecutor(timeoutSeconds,TimeUnit.SECONDS).execute {
                synchronized(this) { if(pending.remove(id,future)) {
                    future.completeExceptionally(TimeoutException("Die Geometrieberechnung hat das Zeitlimit überschritten."))
                    if(process===child) cancel()
                } }
            }
        } catch(e: Exception) { pending.entries.removeIf { it.value===future };future.completeExceptionally(e);if(e is java.io.IOException) cancel() }
        return future
    }
    private fun failPending(message: String) { pending.values.forEach { it.completeExceptionally(CancellationException(message)) };pending.clear() }
    @Synchronized fun cancel() {
        val child=process;process=null;writer=null
        runCatching { child?.outputStream?.close() };child?.destroyForcibly()
        failPending("Die Geometrieberechnung wurde abgebrochen.")
    }
    @Synchronized override fun close() { closed=true;cancel() }
}
internal fun nativeKernelExecutable(): Path {
    val packaged=System.getProperty("jpackage.app-path")?.let { Path.of(it).parent.parent.resolve("app/native/cad-kernel") }
    val classpath=System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toAbsolutePath().parent.resolve("native/cad-kernel") }
    return listOfNotNull(System.getProperty("kotlin.cad.kernel")?.let(Path::of),System.getenv("KOTLIN_CAD_KERNEL")?.let(Path::of),packaged).plus(classpath)
        .plus(Path.of("build/native-runtime/cad-kernel").toAbsolutePath()).firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?: throw IllegalStateException("Der mitgelieferte Geometriekern wurde nicht gefunden.")
}
