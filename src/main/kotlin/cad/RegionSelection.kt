package cad

import kotlin.math.abs

/** Geometry used for picking, independent of the renderer and document history. */
internal data class PickableRegion(val reference:RegionReference,val frame:SketchFrame,val outer:List<SketchPoint>,val holes:List<List<SketchPoint>>) {
    fun distance(origin:Vec3,direction:Vec3):Double? {
        val denominator=direction.dot(frame.normal)
        if(abs(denominator)<1e-10) return null
        val t=(frame.origin-origin).dot(frame.normal)/denominator
        if(t<0) return null
        val p=frame.project(origin+direction*t)
        return t.takeIf { inside(p,outer) && holes.none { inside(p,it) } }
    }
    private fun inside(p:SketchPoint,polygon:List<SketchPoint>):Boolean {
        var inside=false
        for(i in polygon.indices) {
            val a=polygon[i];val b=polygon[(i+1)%polygon.size]
            if((a.v>p.v)!=(b.v>p.v) && p.u<(b.u-a.u)*(p.v-a.v)/(b.v-a.v)+a.u) inside=!inside
        }
        return inside
    }
}

internal fun DocumentState.pickableRegions():List<PickableRegion> {
    val values=ParameterEvaluator.evaluate(parameters)
    return evaluatedSketches().filter { it.visible }.flatMap { sketch ->
        // Invalid or open contours remain visible wires, never selectable regions.
        runCatching {
            val frame=sketch.drawingFrame()
            val contours=sketch.elementGeometries(values).associate { it.id to it.points.map(frame::project) }
            sketch.regions(values).map { region -> PickableRegion(RegionReference(sketch.id,region.id),frame,
                contours.getValue(region.outerElementIds.single()),region.holeElementIds.map(contours::getValue)) }
        }.getOrDefault(emptyList())
    }.sortedWith(compareBy({it.reference.sketchId},{it.reference.regionId}))
}

internal fun pickRegion(regions:List<PickableRegion>,origin:Vec3,direction:Vec3,occluder:MeshData?):RegionReference? {
    val ray=direction.normalized()
    val hits=regions.mapNotNull { region -> region.distance(origin,ray)?.let { region to it } }
    val nearest=hits.minOfOrNull { it.second } ?: return null
    val hit=hits.filter { abs(it.second-nearest)<1e-6 }.minWith(compareBy({it.first.reference.sketchId},{it.first.reference.regionId}))
    if(occluder!=null) for(i in occluder.indices.indices step 3) {
        val a=occluder.vertices[occluder.indices[i]];val b=occluder.vertices[occluder.indices[i+1]];val c=occluder.vertices[occluder.indices[i+2]]
        val e1=b-a;val e2=c-a;val h=ray.cross(e2);val determinant=e1.dot(h)
        if(abs(determinant)<1e-10) continue
        val s=origin-a;val u=s.dot(h)/determinant
        if(u<0 || u>1) continue
        val q=s.cross(e1);val v=ray.dot(q)/determinant
        if(v<0 || u+v>1) continue
        val t=e2.dot(q)/determinant
        if(t>=0 && t<nearest-1e-6) return null
    }
    return hit.first.reference
}

internal data class RegionExtrusionDraft(val sources:List<RegionReference>,val ids:List<String>,val depth:String="10 mm") {
    fun applyTo(document:DocumentState):DocumentState {
        require(sources.isNotEmpty() && sources.distinct().size==sources.size && ids.size==sources.size && ids.distinct().size==ids.size)
        val valid=document.pickableRegions().map { it.reference }.toSet()
        require(sources.all { it in valid }) { "Eine ausgewählte Skizzenregion ist nicht mehr verfügbar. Bitte neu auswählen." }
        require(document.effectiveFeatures().none { it.id in ids }) { "Die Extrusionskennung ist bereits vergeben." }
        val next=document.copy(features=document.effectiveFeatures()+sources.zip(ids).map { (source,id) -> ExtrusionFeature(id,source,depth) }).canonicalFeatures()
        next.bodyMeshes() // Validate every source and depth before publishing any part.
        return next
    }
}
