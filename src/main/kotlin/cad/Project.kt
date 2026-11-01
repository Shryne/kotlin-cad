package cad

import kotlinx.serialization.json.*
import java.io.*
import java.nio.file.*
import java.util.zip.*

data class DisplaySettings(val wireframe: Boolean = false, val grid: Boolean = true, val visible: Boolean = true, val unit: String = "mm", val hiddenBodyIds: Set<String> = emptySet()) {
    init { require(hiddenBodyIds.size<=10000 && hiddenBodyIds.all { it.isNotBlank() && it.length<=256 }) { "Ungültige Körpersichtbarkeit." };require(unit in setOf("mm", "cm", "m", "in")) { "Ungültige Einheit." } }
}
data class DocumentState(
    val mesh: MeshData? = null,
    val source: String? = null,
    val display: DisplaySettings = DisplaySettings(),
    val parameters: List<Parameter> = emptyList(),
    val sketches: List<Sketch> = emptyList(),
    val features: List<CadFeature> = emptyList(),
    val bodyColors: Map<String,BodyColors> = emptyMap()
)
data class CameraPose(val yaw: Double = -35.0, val pitch: Double = 25.0, val panX: Double = 0.0, val panY: Double = 0.0, val zoom: Double = 1.0) {
    init {
        require(listOf(yaw, pitch, panX, panY, zoom).all { it.isFinite() } && pitch in -90.0..90.0 && zoom in 0.05..40.0) {
            "Ungültige Kameraeinstellungen."
        }
    }
}
data class ProjectArchive(val document: DocumentState, val camera: CameraPose = CameraPose())

/** Immutable document snapshots share the mesh; importing/opening starts a new history. */
class DocumentHistory(initial: DocumentState = DocumentState(), private val limit: Int = 100) {
    var current = initial
        private set
    private val past = ArrayDeque<DocumentState>()
    private val future = ArrayDeque<DocumentState>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun commit(next: DocumentState): Boolean {
        if (next == current) return false
        next.bodyMeshes()
        past.addLast(current)
        if (past.size > limit) past.removeFirst()
        current = next; future.clear()
        return true
    }
    fun undo(): DocumentState {
        if (canUndo) { future.addLast(current); current = past.removeLast() }
        return current
    }
    fun redo(): DocumentState {
        if (canRedo) { past.addLast(current); current = future.removeLast() }
        return current
    }
    fun reset(next: DocumentState) { current = next; past.clear(); future.clear() }
}

/** A self-contained ZIP: versioned JSON metadata and lossless double-precision mesh data. */
object ProjectIO {
    fun write(path: Path, archive: ProjectArchive, overwrite: Boolean = false) {
        require(path.fileName.toString().lowercase().endsWith(".kcad")) { "Projektdateien enden auf .kcad." }
        val canonical=archive.copy(document=archive.document.canonicalFeatures())
        canonical.document.bodyMeshes()
        val target = path.toAbsolutePath().normalize()
        require(Files.isDirectory(target.parent)) { "Der Zielordner existiert nicht." }
        if (!overwrite) require(!Files.exists(target)) { "Die Projektdatei existiert bereits." }
        val temporary = Files.createTempFile(target.parent, ".kcad-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                val description=metadata(canonical).toString().toByteArray(Charsets.UTF_8)
                require(description.size<=1_048_576) { "Projektbeschreibung zu groß. Anzahl der gespeicherten Flächenbereiche reduzieren oder Raumzonen verwenden." }
                zip.write(description); zip.closeEntry()
                canonical.document.mesh?.let { mesh ->
                    zip.putNextEntry(ZipEntry("mesh.bin"))
                    val data = DataOutputStream(BufferedOutputStream(zip, 64 * 1024))
                    data.writeInt(0x4b434d31); data.writeInt(mesh.vertices.size); data.writeInt(mesh.indices.size)
                    mesh.vertices.forEach { data.writeDouble(it.x); data.writeDouble(it.y); data.writeDouble(it.z) }
                    mesh.indices.forEach(data::writeInt)
                    data.flush()
                    zip.closeEntry()
                }
            }
            if (overwrite) {
                try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            } else Files.move(temporary, target)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun read(path: Path): ProjectArchive {
        require(Files.size(path) <= 160L * 1024 * 1024) { "Projektdatei ist zu groß." }
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 2 && entries.map { it.name }.distinct().size == entries.size &&
                entries.all { it.name in setOf("project.json", "mesh.bin") }) { "Ungültiger Projektinhalt." }
            val metadataEntry = zip.getEntry("project.json") ?: error("Projektbeschreibung fehlt.")
            require(metadataEntry.size in 1..1_048_576) { "Ungültige Projektbeschreibung." }
            val bytes = zip.getInputStream(metadataEntry).use { it.readNBytes(1_048_577) }
            require(bytes.size <= 1_048_576)
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(json["format"]?.jsonPrimitive?.content == "kotlin-cad" && json["version"]?.jsonPrimitive?.int in 1..12) {
                "Dieses Projektformat wird noch nicht unterstützt."
            }
            val display = json.getValue("display").jsonObject
            val settings = DisplaySettings(
                false, display.getValue("grid").jsonPrimitive.boolean,
                display.getValue("visible").jsonPrimitive.boolean, display.requiredString("unit"),
                display["hiddenBodyIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            )
            val parameters = json.getValue("parameters").jsonArray.map {
                val value = it.jsonObject; Parameter(ParameterEvaluator.name(value.requiredString("name")), ParameterEvaluator.name(value.requiredString("expression")))
            }
            ParameterEvaluator.evaluate(parameters)
            val mesh = json["mesh"]?.takeUnless { it == JsonNull }?.jsonObject?.let { descriptor ->
                val name = descriptor.requiredString("name")
                require(name.length <= 1024) { "Ungültiger Modellname." }
                val entry = zip.getEntry("mesh.bin") ?: error("Modellgeometrie fehlt.")
                require(entry.size in 12..90_000_000) { "Modellgeometrie ist zu groß." }
                zip.getInputStream(entry).buffered().use { stream ->
                    val data = DataInputStream(stream)
                    require(data.readInt() == 0x4b434d31) { "Ungültige Modellgeometrie." }
                    val pointCount = data.readInt(); val indexCount = data.readInt()
                    require(pointCount in 1..5_000_000 && indexCount in 3..3_000_000 && indexCount % 3 == 0 &&
                        entry.size == 12L + pointCount * 24L + indexCount * 4L) { "Ungültige Geometriegröße." }
                    val points = List(pointCount) { Vec3(data.readDouble(), data.readDouble(), data.readDouble()) }
                    val indices = IntArray(indexCount) { data.readInt() }
                    MeshData(name, points, indices)
                }
            }
            val camera = json.getValue("camera").jsonObject
            val pose = CameraPose(
                camera.getValue("yaw").jsonPrimitive.double, camera.getValue("pitch").jsonPrimitive.double,
                camera.getValue("panX").jsonPrimitive.double, camera.getValue("panY").jsonPrimitive.double,
                camera.getValue("zoom").jsonPrimitive.double
            )
            val source = json["source"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val sketches = json["sketches"]?.jsonArray?.map { Sketch.read(it.jsonObject) } ?: emptyList()
            val version=json.getValue("version").jsonPrimitive.int
            val features=if(version>=10) json.getValue("features").jsonArray.map { CadFeature.read(it.jsonObject) } else emptyList()
            if(version>=10) require(sketches.all { it.depth==null && it.elements.isNotEmpty() }) { "Ungültige Skizzenstruktur für Projektversion 10." }
            val document = DocumentState(mesh, source, settings, parameters, sketches,features, json["bodyColors"]?.jsonObject?.mapValues { BodyColors.read(it.value.jsonObject) } ?: emptyMap()).canonicalFeatures()
            document.bodyMeshes()
            return ProjectArchive(document, pose)
        }
    }
    private fun metadata(archive: ProjectArchive) = buildJsonObject {
        val document = archive.document
        put("format", "kotlin-cad"); put("version", 12)
        put("bodyColors",JsonObject(document.bodyColors.mapValues { it.value.json() }))
        put("sketches", JsonArray(document.sketches.map { it.json() }))
        put("features",JsonArray(document.features.map { it.json() }))
        put("source", document.source?.let(::JsonPrimitive) ?: JsonNull)
        put("mesh", document.mesh?.let { buildJsonObject { put("name", it.name) } } ?: JsonNull)
        put("parameters", JsonArray(document.parameters.map { buildJsonObject { put("name", it.name); put("expression", it.expression) } }))
        put("display", buildJsonObject {
            put("wireframe", false); put("grid", document.display.grid)
            put("visible", document.display.visible); put("unit", document.display.unit)
            put("hiddenBodyIds",JsonArray(document.display.hiddenBodyIds.sorted().map(::JsonPrimitive)))
        })
        put("camera", buildJsonObject {
            put("yaw", archive.camera.yaw); put("pitch", archive.camera.pitch); put("panX", archive.camera.panX)
            put("panY", archive.camera.panY); put("zoom", archive.camera.zoom)
        })
    }
}
