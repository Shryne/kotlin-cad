package cad

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.*

enum class MeshFormat(val extension: String) { STL("stl"), OBJ("obj") }
data class MeshExportResult(val path: Path,val format: MeshFormat,val triangles: Int,val parts: Int,val bytes: Long)

/** Writes committed geometry in project coordinates. Camera, selection, grid and draft overlays are excluded. */
object MeshExporter {
    fun write(path: Path,document: DocumentState,overwrite: Boolean=false): MeshExportResult {
        return writeParts(path,listOfNotNull(document.mesh)+document.bodyMeshes(),overwrite)
    }
    fun writeParts(path:Path,parts:List<MeshData>,overwrite:Boolean=false):MeshExportResult {
        val target=path.toAbsolutePath().normalize()
        val format=MeshFormat.entries.firstOrNull { it.extension==target.fileName.toString().substringAfterLast('.',"").lowercase() }
            ?: throw IllegalArgumentException("Exportdateien enden auf .stl oder .obj.")
        require(parts.isNotEmpty()) { "Es gibt noch keinen Körper oder importiertes Modell zum Exportieren." }
        val count=parts.sumOf { it.triangleCount.toLong() }
        require(count in 1..1_000_000) { "Der Export unterstützt bis zu 1 Million Dreiecke." }
        require(Files.isDirectory(target.parent)) { "Der Zielordner existiert nicht." }
        if(!overwrite) require(!Files.exists(target)) { "Die Exportdatei existiert bereits." }
        val temporary=Files.createTempFile(target.parent,".cad-export-",".tmp")
        try {
            when(format) {
                MeshFormat.STL -> Files.newOutputStream(temporary).buffered().use { stl(it,parts,count.toInt()) }
                MeshFormat.OBJ -> obj(temporary,parts)
            }
            val bytes=Files.size(temporary)
            require(bytes<=128L*1024*1024) { "Die Exportdatei überschreitet die unterstützten 128 MB." }
            if(overwrite) {
                try { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
                catch(_: AtomicMoveNotSupportedException) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING) }
            } else Files.move(temporary,target)
            return MeshExportResult(target,format,count.toInt(),parts.size,bytes)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun stl(output: OutputStream,parts: List<MeshData>,count: Int) {
        val header=ByteArray(80)
        "Kotlin CAD binary STL".toByteArray(Charsets.US_ASCII).copyInto(header)
        output.write(header)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array())
        val record=ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        parts.forEach { part ->
            require(part.vertices.all { it.x.toFloat().isFinite() && it.y.toFloat().isFinite() && it.z.toFloat().isFinite() }) {
                "Die Koordinaten sind für STL zu groß. Verwende OBJ."
            }
            for(index in part.indices.indices step 3) {
                if(Thread.currentThread().isInterrupted) throw InterruptedException("Export abgebrochen.")
                val a=part.vertices[part.indices[index]];val b=part.vertices[part.indices[index+1]];val c=part.vertices[part.indices[index+2]]
                val cross=(b-a).cross(c-a);val length=cross.norm()
                val normal=if(length>0 && length.isFinite()) cross*(1/length) else Vec3(0.0,0.0,0.0)
                record.clear()
                listOf(normal,a,b,c).forEach { record.putFloat(it.x.toFloat());record.putFloat(it.y.toFloat());record.putFloat(it.z.toFloat()) }
                record.putShort(0);output.write(record.array())
            }
        }
    }
    private fun obj(path: Path,parts: List<MeshData>) {
        Files.newBufferedWriter(path,Charsets.UTF_8).use { writer ->
            writer.appendLine("# Kotlin CAD OBJ export")
            writer.appendLine("# Coordinates match the project; OBJ does not define a standard length unit.")
            var offset=1
            parts.forEachIndexed { index,part ->
                writer.appendLine("o part_${index+1}")
                writer.appendLine("# ${part.name.replace('\n',' ').replace('\r',' ').take(128)}")
                part.vertices.forEach { writer.appendLine("v ${it.x} ${it.y} ${it.z}") }
                for(i in part.indices.indices step 3) {
                    if(Thread.currentThread().isInterrupted) throw InterruptedException("Export abgebrochen.")
                    writer.appendLine("f ${part.indices[i]+offset} ${part.indices[i+1]+offset} ${part.indices[i+2]+offset}")
                }
                offset+=part.vertices.size
            }
        }
    }
}
