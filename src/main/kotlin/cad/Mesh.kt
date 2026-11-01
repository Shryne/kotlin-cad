package cad

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Vec3(val x: Double, val y: Double, val z: Double)
data class Bounds(val low: Vec3, val high: Vec3) {
    val center get() = Vec3((low.x + high.x) / 2, (low.y + high.y) / 2, (low.z + high.z) / 2)
    val size get() = Vec3(high.x - low.x, high.y - low.y, high.z - low.z)
    val span get() = max(size.x, max(size.y, size.z)).coerceAtLeast(1e-9)
}
data class MeshData(val name: String, val vertices: List<Vec3>, val indices: IntArray) {
    init {
        require(vertices.isNotEmpty() && indices.isNotEmpty()) { "Die Datei enthält keine Flächen." }
        require(indices.size % 3 == 0 && indices.all { it in vertices.indices }) { "Ungültige Flächenindizes." }
        require(vertices.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) { "Ungültige Koordinaten." }
    }
    val bounds: Bounds by lazy {
        Bounds(Vec3(vertices.minOf { it.x }, vertices.minOf { it.y }, vertices.minOf { it.z }),
            Vec3(vertices.maxOf { it.x }, vertices.maxOf { it.y }, vertices.maxOf { it.z }))
    }
    val triangleCount get() = indices.size / 3
}

/** Import coordinates remain untouched. Units are assigned by the user, not inferred. */
object MeshImporter {
    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val MAX_TRIANGLES = 1_000_000
    fun read(path: Path): MeshData {
        require(Files.size(path) <= MAX_BYTES) { "Die Datei ist größer als 128 MB. Der Prototyp unterstützt kleinere Dateien." }
        val bytes = Files.readAllBytes(path)
        return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "stl" -> stl(path.fileName.toString(), bytes)
            "obj" -> obj(path.fileName.toString(), bytes.toString(Charsets.UTF_8))
            else -> error("Bitte eine STL- oder OBJ-Datei wählen.")
        }
    }
    fun stl(name: String, bytes: ByteArray): MeshData {
        // A binary STL may start with "solid". Validate the exact binary layout first.
        val count = if (bytes.size >= 84) ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL else -1L
        if (count >= 0 && 84L + count * 50L == bytes.size.toLong()) {
            require(count <= MAX_TRIANGLES) { "Mehr als 1 Million Dreiecke werden noch nicht unterstützt." }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(84)
            val vertices = ArrayList<Vec3>((count * 3).toInt())
            repeat(count.toInt()) {
                buffer.position(buffer.position() + 12)
                repeat(3) { vertices.add(Vec3(buffer.float.toDouble(), buffer.float.toDouble(), buffer.float.toDouble())) }
                buffer.short
            }
            return MeshData(name, vertices, IntArray(vertices.size) { it })
        }
        val vertices = ArrayList<Vec3>()
        var inFacet = false
        var facetVertices = 0
        bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { lineNumber, raw ->
            val parts = raw.trim().split(Regex("\\s+"))
            when (parts[0].lowercase()) {
                "facet" -> {
                    require(!inFacet) { "Verschachtelte STL-Fläche in Zeile ${lineNumber + 1}." }
                    inFacet = true; facetVertices = 0
                }
                "vertex" -> {
                    require(inFacet && parts.size == 4 && facetVertices < 3) { "Ungültiger STL-Punkt in Zeile ${lineNumber + 1}." }
                    require(vertices.size < MAX_TRIANGLES * 3) { "Zu viele Dreiecke." }
                    vertices.add(Vec3(parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble()))
                    facetVertices++
                }
                "endfacet" -> {
                    require(inFacet && facetVertices == 3) { "Eine STL-Fläche muss drei Punkte besitzen." }
                    inFacet = false
                }
            }
        }
        require(!inFacet) { "Die STL-Datei endet innerhalb einer Fläche." }
        return MeshData(name, vertices, IntArray(vertices.size) { it })
    }
    fun obj(name: String, text: String): MeshData {
        val vertices = ArrayList<Vec3>()
        val indices = ArrayList<Int>()
        text.lineSequence().forEachIndexed { line, raw ->
            val parts = raw.substringBefore('#').trim().split(Regex("\\s+"))
            try {
                when (parts[0]) {
                    "v" -> {
                        require(parts.size >= 4) { "Ein Punkt braucht drei Koordinaten." }
                        val w = if (parts.size == 5) parts[4].toDouble() else 1.0
                        require(w.isFinite() && w != 0.0) { "Ungültige homogene Koordinate." }
                        val vertex = Vec3(parts[1].toDouble() / w, parts[2].toDouble() / w, parts[3].toDouble() / w)
                        require(listOf(vertex.x, vertex.y, vertex.z).all { it.isFinite() }) { "Ungültige Koordinaten." }
                        require(vertices.size < MAX_TRIANGLES * 3) { "Zu viele Punkte." }
                        vertices.add(vertex)
                    }
                    "f" -> {
                        require(parts.size >= 4) { "Eine Fläche braucht mindestens drei Punkte." }
                        require(parts.size <= 4097) { "Eine einzelne Fläche hat zu viele Punkte." }
                        val face = parts.drop(1).map {
                            val index = it.substringBefore('/').toInt()
                            require(index != 0) { "OBJ-Indizes beginnen bei 1." }
                            val resolved = if (index > 0) index - 1 else vertices.size + index
                            require(resolved in vertices.indices) { "Punktindex außerhalb des Modells." }
                            resolved
                        }
                        require(indices.size / 3 + face.size - 2 <= MAX_TRIANGLES) { "Zu viele Dreiecke." }
                        indices.addAll(triangulate(face, vertices))
                    }
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("OBJ, Zeile ${line + 1}: ${e.message}", e)
            }
        }
        return MeshData(name, vertices, indices.toIntArray())
    }

    /** Ear clipping preserves concave polygons; a triangle fan would fill their notches. */
    private fun triangulate(face: List<Int>, vertices: List<Vec3>): List<Int> {
        val loop = face.toMutableList()
        if (loop.size > 3 && loop.first() == loop.last()) loop.removeAt(loop.lastIndex)
        require(loop.distinct().size == loop.size) { "Fläche enthält doppelte Punkte." }
        if (loop.size == 3) return loop
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in loop.indices) {
            val a = vertices[loop[i]]; val b = vertices[loop[(i + 1) % loop.size]]
            nx += (a.y - b.y) * (a.z + b.z)
            ny += (a.z - b.z) * (a.x + b.x)
            nz += (a.x - b.x) * (a.y + b.y)
        }
        val axis = if (abs(nx) >= max(abs(ny), abs(nz))) 0 else if (abs(ny) >= abs(nz)) 1 else 2
        fun point(i: Int): Pair<Double, Double> = vertices[i].let {
            when (axis) { 0 -> it.y to it.z; 1 -> it.x to it.z; else -> it.x to it.y }
        }
        fun cross(a: Int, b: Int, c: Int): Double {
            val p = point(a); val q = point(b); val r = point(c)
            return (q.first-p.first)*(r.second-p.second) - (q.second-p.second)*(r.first-p.first)
        }
        val area = loop.indices.sumOf { i ->
            val a = point(loop[i]); val b = point(loop[(i+1)%loop.size])
            a.first*b.second-b.first*a.second
        }
        require(area.isFinite() && area != 0.0) { "Entartete Polygonfläche." }
        val sign = if (area > 0) 1 else -1
        val extent = loop.map(::point).let { points ->
            max(points.maxOf { it.first } - points.minOf { it.first }, points.maxOf { it.second } - points.minOf { it.second })
        }
        val epsilon = extent * extent * 1e-12
        val result = ArrayList<Int>()
        while (loop.size > 3) {
            var clipped = false
            for (i in loop.indices) {
                val a = loop[(i+loop.size-1)%loop.size]; val b = loop[i]; val c = loop[(i+1)%loop.size]
                if (sign * cross(a,b,c) <= epsilon) continue
                val contains = loop.any { p ->
                    p != a && p != b && p != c &&
                        sign*cross(a,b,p) >= -epsilon && sign*cross(b,c,p) >= -epsilon && sign*cross(c,a,p) >= -epsilon
                }
                if (contains) continue
                result.addAll(listOf(a,b,c)); loop.removeAt(i); clipped = true; break
            }
            require(clipped) { "Polygon kann nicht trianguliert werden (z. B. Selbstüberschneidung)." }
        }
        result.addAll(loop)
        return result
    }
}
