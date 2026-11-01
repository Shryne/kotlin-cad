package cad

import kotlinx.serialization.json.*
import java.io.*
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.*

/** Private local IPC. MCP itself is provided by the separate stdio adapter. */
class LocalBridge(
    directory: Path = bridgeDirectory(),
    private val execute: (JsonObject) -> CompletableFuture<JsonObject>
) : AutoCloseable {
    val socketPath: Path = directory.resolve("cad-${ProcessHandle.current().pid()}.sock")
    private val listener: ServerSocketChannel
    private val clients = ConcurrentHashMap.newKeySet<SocketChannel>()
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { Thread(it, "cad-bridge-command").apply { isDaemon = true } })
    @Volatile private var closed = false

    init {
        require(!Files.isSymbolicLink(directory)) { "Bridge-Verzeichnis darf keine Verknüpfung sein." }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            listener.bind(UnixDomainSocketAddress.of(socketPath))
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"))
        } catch (e: Exception) { listener.close(); workers.shutdownNow(); throw e }
        Thread({
            while (!closed) {
                try {
                    val client = listener.accept()
                    clients.add(client)
                    try { workers.submit { handle(client) } }
                    catch (_: RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (e: Exception) {
                    if (!closed) System.err.println("CAD bridge: ${e.message}")
                }
            }
        }, "cad-bridge-listener").apply { isDaemon = true; start() }
    }

    private fun handle(channel: SocketChannel) {
        // Closing an idle connection prevents local clients from occupying workers indefinitely.
        val timeout = scheduler.schedule({ runCatching { channel.close() } }, 65, TimeUnit.SECONDS)
        channel.use {
            try {
                val input = Channels.newInputStream(channel)
                val request = Json.parseToJsonElement(readFrame(input, 16 * 1024)).jsonObject
                val result = execute(request).get(60, TimeUnit.SECONDS)
                writeFrame(Channels.newOutputStream(channel), buildJsonObject {
                    put("ok", true); put("result", result)
                })
            } catch (e: Exception) {
                runCatching {
                    writeFrame(Channels.newOutputStream(channel), buildJsonObject {
                        put("ok", false)
                        put("error", (e.cause ?: e).message ?: "Lokaler Befehl fehlgeschlagen.")
                    })
                }
            } finally {
                timeout.cancel(false)
                clients.remove(channel)
            }
        }
    }
    override fun close() {
        closed = true
        listener.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        Files.deleteIfExists(socketPath)
    }
    companion object {
        private val scheduler = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "cad-bridge-timeout").apply { isDaemon = true }
        }
    }
}

fun bridgeDirectory(): Path = System.getenv("KOTLIN_CAD_BRIDGE_DIR")?.let(Path::of)
    ?: Path.of(System.getProperty("user.home"), "Library", "Application Support", "Kotlin CAD", "bridge")

/** One connection per command; no persistent connection can outlive the owning viewer. */
class BridgeClient(private val directory: Path = bridgeDirectory(), private val socket: Path? = null) {
    fun call(request: JsonObject): JsonObject {
        val target = socket ?: discover()
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            val timeout = bridgeTimeout.schedule({ runCatching { channel.close() } }, 65, TimeUnit.SECONDS)
            try {
                channel.connect(UnixDomainSocketAddress.of(target))
                writeFrame(Channels.newOutputStream(channel), request)
                val response = Json.parseToJsonElement(readFrame(Channels.newInputStream(channel), 20 * 1024 * 1024)).jsonObject
                check(response["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                    response["error"]?.jsonPrimitive?.content ?: "Ungültige Antwort des Viewers."
                }
                return response.getValue("result").jsonObject
            } finally { timeout.cancel(false) }
        }
    }
    private fun discover(): Path {
        check(Files.isDirectory(directory)) { "Kotlin CAD läuft nicht. Bitte die App starten." }
        val live = Files.list(directory).use { entries ->
            entries.filter { it.fileName.toString().matches(Regex("cad-[0-9]+\\.sock")) }
                .filter { file ->
                    val pid = file.fileName.toString().removePrefix("cad-").removeSuffix(".sock").toLong()
                    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                }.toList()
        }
        check(live.isNotEmpty()) { "Kein laufender Viewer mit MCP-Zugang gefunden. Bitte die aktuelle App starten." }
        check(live.size == 1) {
            "Mehrere CAD-Fenster gefunden. Mit --socket gezielt eine Instanz auswählen: ${live.joinToString()}"
        }
        return live.single()
    }
    companion object {
        private val bridgeTimeout = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "cad-client-timeout").apply { isDaemon = true }
        }
    }
}

internal fun readFrame(input: InputStream, limit: Int): String {
    val source = input.buffered()
    val bytes = ByteArrayOutputStream()
    while (true) {
        val next = source.read()
        check(next != -1) { "Die lokale Verbindung wurde vorzeitig geschlossen." }
        if (next == 10) return bytes.toString(Charsets.UTF_8)
        require(bytes.size() < limit) { "Lokale Nachricht überschreitet die Größenbegrenzung." }
        bytes.write(next)
    }
}
private fun writeFrame(output: OutputStream, value: JsonObject) {
    output.write(value.toString().toByteArray(Charsets.UTF_8))
    output.write(10)
    output.flush()
}
