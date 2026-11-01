package cad

internal data class SceneBody(val id: String,val sketch: Sketch?,val mirrored: Boolean,val mesh: MeshData,val visible: Boolean,
    val regionId: String?=null)
internal fun bodyId(sketchId: String,mirrored: Boolean=false)=(if(mirrored) "mirror:" else "sketch:")+sketchId

/** Stable identifiers are independent of names and triangle offsets. */
internal fun DocumentState.sceneBodies(): List<SceneBody> = evaluatedModel().bodies
internal fun DocumentState.setBodyVisibility(id: String,visible: Boolean): DocumentState {
    require(sceneBodies().any { it.id==id }) { "Körper existiert nicht." }
    return copy(display=display.copy(hiddenBodyIds=if(visible) display.hiddenBodyIds-id else display.hiddenBodyIds+id))
}
internal fun DocumentState.framingBounds(): Bounds? {
    val values=ParameterEvaluator.evaluate(parameters)
    val points=sceneBodies().flatMap { listOf(it.mesh.bounds.low,it.mesh.bounds.high) }+
        evaluatedSketches().flatMap { it.profileCopies(values).flatten() }
    if(points.isEmpty()) return null
    return Bounds(Vec3(points.minOf { it.x },points.minOf { it.y },points.minOf { it.z }),Vec3(points.maxOf { it.x },points.maxOf { it.y },points.maxOf { it.z }))
}

internal fun DocumentState.isolateBody(id: String): DocumentState {
    val bodies=sceneBodies()
    require(bodies.any { it.id==id }) { "Körper existiert nicht." }
    return copy(display=display.copy(visible=true,hiddenBodyIds=bodies.map { it.id }.filterNot { it==id }.toSet()))
}
internal fun DocumentState.showAllBodies()=copy(display=display.copy(visible=true,hiddenBodyIds=emptySet()))

/** Removing an import retains sketches on its faces at their last evaluated world plane. */
internal fun DocumentState.removeImportedBody(): DocumentState {
    require(mesh!=null) { "Kein importiertes Objekt vorhanden." }
    val evaluated=evaluatedSketches().associateBy { it.id }
    return copy(mesh=null,source=null,bodyColors=bodyColors-"import",
        display=display.copy(hiddenBodyIds=display.hiddenBodyIds-"import"),
        sketches=sketches.map { sketch ->
            if(sketch.support!=null && sketch.support.sketchId==null)
                sketch.copy(frame=evaluated.getValue(sketch.id).frame,support=null)
            else sketch
        })
}
