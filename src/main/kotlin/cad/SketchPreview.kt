package cad

/** Draft geometry is evaluated without committing a document or adding history entries. */
data class SketchPreviewContext(val document: DocumentState,val editedId: String,val affectedIds: Set<String>,val geometry: MeshData?) {
    fun bodies(draft: Sketch,extrusion:ExtrusionFeature?=null): List<MeshData> {
        return bodies(draft,listOfNotNull(extrusion))
    }
    fun bodies(draft: Sketch,extrusions:List<ExtrusionFeature>): List<MeshData> {
        require(draft.id==editedId)
        val candidate=if(extrusions.isNotEmpty()) document.replaceSketchExtrusions(draft,extrusions.map { it.copy(depth=draft.depth ?: it.depth) })
            else document.replaceSketch(draft,legacyExtrusion=draft.depth!=null)
        return candidate.sceneBodies().filter { it.sketch?.id in affectedIds }.map { it.mesh }
    }
}
fun Sketch.bodyCopies(values: Map<String,Quantity>): List<MeshData> {
    val body=extrudedMesh(values) ?: return emptyList()
    return listOf(body)+listOfNotNull(mirror?.let { body.mirrored(it,mirrorDistance(values)) })
}
fun DocumentState.prepareSketchPreview(editedId: String, rendered: MeshData? = renderMesh()): SketchPreviewContext {
    val affected=mutableSetOf(editedId)
    do {
        val oldSize=affected.size
        sketches.filter { it.support?.sketchId in affected }.forEach { affected.add(it.id) }
    } while(affected.size!=oldSize)
    val geometry=if(sketches.none { it.id==editedId }) rendered else {
        val parts=sceneBodies().filter { it.visible && it.sketch?.id !in affected }.map { it.mesh }
        when(parts.size) {
            0 -> null
            1 -> parts.single()
            else -> {
                val vertices=ArrayList<Vec3>();val indices=ArrayList<Int>()
                parts.forEach { part -> val offset=vertices.size;vertices.addAll(part.vertices);part.indices.forEach { indices.add(it+offset) } }
                MeshData("Umgebung",vertices,indices.toIntArray())
            }
        }
    }
    return SketchPreviewContext(this,editedId,affected,geometry)
}
