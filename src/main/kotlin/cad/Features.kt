package cad

import kotlinx.serialization.json.*

enum class SketchElementKind { PROFILE, LINE, POLYLINE, ARC }
data class SketchCoordinate(val u: String, val v: String) {
    fun json()=buildJsonObject { put("u",u);put("v",v) }
    companion object { fun read(j: JsonObject)=SketchCoordinate(j.requiredString("u"),j.requiredString("v")) }
}
data class SketchElementGeometry(val id: String,val kind: SketchElementKind,val points: List<Vec3>,val closed: Boolean)

/** Element IDs are stable within their owning sketch; only closed profile elements define regions. */
data class SketchElement(val id: String="profile",val shape: SketchShape=SketchShape.RECTANGLE,
    val u: String="0 mm",val v: String="0 mm",val width: String="60 mm",val height: String="40 mm",
    val angle: String="0 deg",val centered: Boolean=false,val kind: SketchElementKind=SketchElementKind.PROFILE,
    val vertices: List<SketchCoordinate> = emptyList(),val startAngle: String="0 deg",val sweepAngle: String="90 deg",val isClosed: Boolean=false) {
    init { require(id.isNotBlank() && id.length<=128) { "Ungültige Element-ID." } }
    fun on(sketch: Sketch)=sketch.copy(shape=shape,u=u,v=v,width=width,height=height,angle=angle,centered=centered,elements=emptyList())
    fun geometry(sketch: Sketch,values: Map<String,Quantity>): SketchElementGeometry {
        fun length(text: String): Double {
            val q=ParameterEvaluator.expression(text,values)
            require(q.dimension==Dimension(1) && q.value.isFinite() && kotlin.math.abs(q.value)<=1e9) { "Skizzenkoordinaten benötigen eine Länge bis 1 000 000 000 mm." }
            return q.value
        }
        val frame=sketch.drawingFrame()
        val paths=when(kind) {
            SketchElementKind.PROFILE -> on(sketch).points(values)
            SketchElementKind.LINE,SketchElementKind.POLYLINE -> {
                require(vertices.size in (if(kind==SketchElementKind.LINE) 2..2 else 2..512)) { if(kind==SketchElementKind.LINE) "Eine Linie benötigt zwei Punkte." else "Eine Polylinie benötigt 2 bis 512 Punkte." }
                val rotation=sketch.rotation(values);val c=kotlin.math.cos(rotation);val s=kotlin.math.sin(rotation)
                vertices.map { SketchPoint(length(it.u),length(it.v)) }.map { p -> frame.point(p.u*c-p.v*s,p.u*s+p.v*c) }
            }
            SketchElementKind.ARC -> {
                val radius=length(width);require(radius>0) { "Der Radius des Bogens muss positiv sein." }
                fun radians(text: String): Double {
                    val q=ParameterEvaluator.expression(text,values)
                    require(q.dimension==Dimension(angle=1) && q.value.isFinite() && kotlin.math.abs(q.value)<=2*kotlin.math.PI) { "Bogenwinkel müssen zwischen -360° und 360° liegen." }
                    return q.value
                }
                val start=radians(startAngle);val sweep=radians(sweepAngle);require(kotlin.math.abs(sweep)>1e-8) { "Der Bogenwinkel darf nicht null sein." }
                val count=(kotlin.math.ceil(kotlin.math.abs(sweep)/(kotlin.math.PI/36)).toInt()).coerceIn(2,180)
                val rotation=sketch.rotation(values);val c=kotlin.math.cos(rotation);val s=kotlin.math.sin(rotation);val centerU=length(u);val centerV=length(v)
                (0..count).map { i -> val a=start+sweep*i/count;val x=centerU+radius*kotlin.math.cos(a);val y=centerV+radius*kotlin.math.sin(a);frame.point(x*c-y*s,x*s+y*c) }
            }
        }
        return SketchElementGeometry(id,kind,paths,kind==SketchElementKind.PROFILE || isClosed)
    }
    fun json()=buildJsonObject {
        put("id",id);put("shape",shape.name);put("u",u);put("v",v);put("width",width);put("height",height);put("angle",angle);put("centered",centered)
        put("kind",kind.name);put("vertices",JsonArray(vertices.map { it.json() }));put("startAngle",startAngle);put("sweepAngle",sweepAngle);put("isClosed",isClosed)
    }
    companion object {
        fun from(sketch: Sketch,id: String="profile")=SketchElement(id,sketch.shape,sketch.u,sketch.v,sketch.width,sketch.height,sketch.angle,sketch.centered)
        fun read(j: JsonObject)=SketchElement(j.requiredString("id"),SketchShape.valueOf(j.requiredString("shape")),j.requiredString("u"),j.requiredString("v"),j.requiredString("width"),j.requiredString("height"),j.requiredString("angle"),j.getValue("centered").jsonPrimitive.boolean,
            j["kind"]?.jsonPrimitive?.content?.let(SketchElementKind::valueOf) ?: SketchElementKind.PROFILE,j["vertices"]?.jsonArray?.map { SketchCoordinate.read(it.jsonObject) } ?: emptyList(),j["startAngle"]?.jsonPrimitive?.content ?: "0 deg",j["sweepAngle"]?.jsonPrimitive?.content ?: "90 deg",j["isClosed"]?.jsonPrimitive?.boolean ?: false)
    }
}

data class RegionReference(val sketchId: String,val regionId: String) {
    init { require(sketchId.isNotBlank() && sketchId.length<=128 && regionId.isNotBlank() && regionId.length<=128) }
    fun json()=buildJsonObject { put("sketchId",sketchId);put("regionId",regionId) }
}
sealed interface CadFeature {
    val id: String
    fun json(): JsonObject
    companion object {
        fun read(j: JsonObject): CadFeature=when(j.requiredString("type")) {
            "instance" -> InstanceFeature.read(j)
            "loft" -> LoftFeature.read(j)
            "nativeShape" -> NativeShapeFeature(j.requiredString("id"),j.requiredString("name"),j.getValue("shape").jsonObject,j["x"]?.jsonPrimitive?.content ?: "0 mm",j["y"]?.jsonPrimitive?.content ?: "0 mm",j["z"]?.jsonPrimitive?.content ?: "0 mm",j["singleSolid"]?.jsonPrimitive?.boolean ?: false)
            "extrusion" -> ExtrusionFeature(j.requiredString("id"),RegionReference(j.requiredString("sketchId"),j.requiredString("regionId")),j.requiredString("depth"),j["x"]?.jsonPrimitive?.content ?: "0 mm",j["y"]?.jsonPrimitive?.content ?: "0 mm",j["z"]?.jsonPrimitive?.content ?: "0 mm")
            "faceExtrusion" -> FaceExtrusionFeature(j.requiredString("id"),FaceReference.read(j.getValue("sourceFace").jsonObject),j.requiredString("depth"),j["x"]?.jsonPrimitive?.content ?: "0 mm",j["y"]?.jsonPrimitive?.content ?: "0 mm",j["z"]?.jsonPrimitive?.content ?: "0 mm")
            "primitive" -> PrimitiveFeature(j.requiredString("id"),j.requiredString("name"),PrimitiveKind.valueOf(j.requiredString("kind")),j.requiredString("width"),j.requiredString("depth"),j.requiredString("height"),j.requiredString("radius"),j.requiredString("x"),j.requiredString("y"),j.requiredString("z"))
            "mirror" -> MirrorFeature(j.requiredString("id"),j.requiredString("sourceFeatureId"),SketchPlane.valueOf(j.requiredString("plane")),j.requiredString("offset"),j["x"]?.jsonPrimitive?.content ?: "0 mm",j["y"]?.jsonPrimitive?.content ?: "0 mm",j["z"]?.jsonPrimitive?.content ?: "0 mm")
            else -> throw IllegalArgumentException("Unbekannter Featuretyp.")
        }
    }
}
internal fun CadFeature.withPosition(x:String,y:String,z:String):CadFeature=when(this) {
    is PrimitiveFeature -> copy(x=x,y=y,z=z)
    is ExtrusionFeature -> copy(x=x,y=y,z=z)
    is FaceExtrusionFeature -> copy(x=x,y=y,z=z)
    is MirrorFeature -> copy(x=x,y=y,z=z)
    is LoftFeature -> copy(x=x,y=y,z=z)
    is NativeShapeFeature -> copy(x=x,y=y,z=z)
    is InstanceFeature -> copy(x=x,y=y,z=z)
}
/** Parametric OCCT expression tree used by locally reconstructed multi-part features. */
data class NativeShapeFeature(override val id:String,val name:String,val shape:JsonObject,val x:String="0 mm",val y:String="0 mm",val z:String="0 mm",val singleSolid:Boolean=false):CadFeature {
    init { require(id!="import" && id.isNotBlank() && id.length<=256 && name.isNotBlank() && name.length<=128 && listOf(x,y,z).all { it.length in 1..1024 });require(shape.toString().length<=256_000) { "Die native Körperdefinition ist zu groß." } }
    override fun json()=buildJsonObject { put("type","nativeShape");put("singleSolid",singleSolid);put("id",id);put("name",name);put("shape",shape);put("x",x);put("y",y);put("z",z) }
}
enum class PrimitiveKind { BOX, CYLINDER, SPHERE }
data class PrimitiveFeature(override val id:String,val name:String,val kind:PrimitiveKind,val width:String="20 mm",val depth:String="20 mm",val height:String="20 mm",val radius:String="10 mm",val x:String="0 mm",val y:String="0 mm",val z:String="0 mm"):CadFeature {
    init { require(id!="import" && id.isNotBlank() && id.length<=256 && name.isNotBlank() && name.length<=128 && listOf(width,depth,height,radius,x,y,z).all { it.length in 1..1024 }) }
    override fun json()=buildJsonObject { put("type","primitive");put("id",id);put("name",name);put("kind",kind.name);put("width",width);put("depth",depth);put("height",height);put("radius",radius);put("x",x);put("y",y);put("z",z) }
}
data class FaceExtrusionFeature(override val id:String,val sourceFace:FaceReference,val depth:String,val x:String="0 mm",val y:String="0 mm",val z:String="0 mm"):CadFeature {
    init { require(id!="import" && id.isNotBlank() && id.length<=256 && depth.isNotBlank() && depth.length<=1024 && listOf(x,y,z).all { it.length in 1..1024 }) }
    override fun json()=buildJsonObject { put("type","faceExtrusion");put("id",id);put("sourceFace",sourceFace.json());put("depth",depth);put("x",x);put("y",y);put("z",z) }
}
data class ExtrusionFeature(override val id: String,val region: RegionReference,val depth: String,val x:String="0 mm",val y:String="0 mm",val z:String="0 mm"): CadFeature {
    init { require(id!="import" && id.isNotBlank() && id.length<=256 && depth.isNotBlank() && depth.length<=1024 && listOf(x,y,z).all { it.length in 1..1024 }) }
    override fun json()=buildJsonObject { put("type","extrusion");put("id",id);put("sketchId",region.sketchId);put("regionId",region.regionId);put("depth",depth);put("x",x);put("y",y);put("z",z) }
}
data class MirrorFeature(override val id: String,val sourceFeatureId: String,val plane: SketchPlane,val offset: String="0 mm",val x:String="0 mm",val y:String="0 mm",val z:String="0 mm"): CadFeature {
    init { require(id!="import" && id.isNotBlank() && id.length<=256 && sourceFeatureId.isNotBlank() && sourceFeatureId.length<=256 && offset.length in 1..1024 && listOf(x,y,z).all { it.length in 1..1024 }) }
    override fun json()=buildJsonObject { put("type","mirror");put("id",id);put("sourceFeatureId",sourceFeatureId);put("plane",plane.name);put("offset",offset);put("x",x);put("y",y);put("z",z) }
}
internal fun Sketch.profileElements()=elements.filter { it.kind==SketchElementKind.PROFILE || it.kind==SketchElementKind.POLYLINE && it.isClosed }.ifEmpty { if(elements.isEmpty()) listOf(SketchElement.from(this)) else emptyList() }
internal fun Sketch.elementGeometries(values: Map<String,Quantity>)=(elements.ifEmpty { listOf(SketchElement.from(this)) }).map { it.geometry(this,values) }
internal fun Sketch.editableElements()=elements.ifEmpty { listOf(SketchElement.from(this)) }
internal fun Sketch.withElements(next: List<SketchElement>): Sketch {
    require(next.isNotEmpty() && next.size<=128 && next.map { it.id }.distinct().size==next.size) { "Ungültige Skizzenelemente." }
    return next.first().on(this).copy(elements=next)
}
/** Only legacy editors use the flat fields. Never overwrite a multi-element sketch through this adapter. */
internal fun Sketch.syncLegacyElement(): Sketch {
    require(elements.size<=1) { "Diese Skizze enthält mehrere Elemente. Verwende die Elementbearbeitung." }
    return copy(elements=listOf(SketchElement.from(this,elements.firstOrNull()?.id ?: "profile")))
}
internal fun DocumentState.effectiveFeatures(): List<CadFeature> {
    val result=features.toMutableList();val ids=result.map { it.id }.toMutableSet()
    sketches.forEach { s -> s.depth?.let { depth ->
        val id=bodyId(s.id)
        if(ids.add(id)) result.add(ExtrusionFeature(id,RegionReference(s.id,s.profileElements().first().id),depth))
        s.mirror?.let { plane -> val mirror=bodyId(s.id,true);if(ids.add(mirror)) result.add(MirrorFeature(mirror,id,plane,s.mirrorOffset)) }
    } }
    require(result.size<=1024 && result.map { it.id }.distinct().size==result.size) { "Features benötigen eindeutige IDs (maximal 1024)." }
    return result
}
internal fun DocumentState.primaryExtrusion(sketchId: String)=effectiveFeatures().filterIsInstance<ExtrusionFeature>().firstOrNull { it.region.sketchId==sketchId }

/** Canonical v10 documents contain profile elements and separate features; no extrusion depth on a sketch. */
internal fun DocumentState.canonicalFeatures(): DocumentState {
    val migrated=effectiveFeatures()
    val next=copy(sketches=sketches.map { s ->
        val support=s.support?.let { r ->
            val feature=r.featureId ?: r.sketchId?.let { bodyId(it,r.mirrored) } ?: "import"
            r.copy(featureId=feature)
        }
        s.withElements(s.editableElements()).copy(depth=null,support=support)
    },features=migrated)
    next.featureOrder()
    if(next.sketches.any { it.support!=null && it.support.surfaceId==null }) {
        val bodies=next.sceneBodies().associateBy { it.id }
        return next.copy(sketches=next.sketches.map { s -> s.support?.let { r ->
            val body=bodies[r.featureId] ?: error("Die Trägerfläche fehlt.")
            require(r.triangle in 0 until body.mesh.triangleCount) { "Ungültige Trägerfläche." }
            s.copy(support=r.copy(surfaceId=r.surfaceId ?: body.surfaceId(r.triangle)))
        } ?: s })
    }
    return next
}
/** Edges include source features and sketch-support dependencies, so mixed cycles are rejected. */
internal fun DocumentState.featureOrder(): List<CadFeature> {
    val all=effectiveFeatures();val definitions=all.associateBy { it.id };val profiles=sketches.associateBy { it.id }
    require(profiles.size==sketches.size && sketches.size<=128) { "Skizzen benötigen eindeutige IDs (maximal 128)." }
    require(sketches.sumOf { it.editableElements().size }<=1024) { "Zu viele Skizzenelemente." }
    sketches.forEach { s -> val e=s.editableElements();require(e.size<=128 && e.map { it.id }.distinct().size==e.size) { "Doppelte Element-ID." } }
    val visiting=mutableSetOf<String>();val finished=mutableSetOf<String>();val order=mutableListOf<CadFeature>()
    fun visit(id: String) {
        if(id=="import") { require(mesh!=null) { "Die importierte Trägerfläche fehlt." };return }
        if(id in finished) return
        require(visiting.add(id) && visiting.size<=64) { "Zyklische oder zu tiefe Featureabhängigkeit." }
        val f=definitions[id] ?: throw IllegalArgumentException("Feature existiert nicht: $id")
        when(f) {
            is MirrorFeature -> visit(f.sourceFeatureId)
            is InstanceFeature -> visit(f.sourceFeatureId)
            is PrimitiveFeature -> Unit
            is LoftFeature -> Unit
            is NativeShapeFeature -> Unit
            is FaceExtrusionFeature -> {
                val source=f.sourceFace.featureId ?: throw IllegalArgumentException("Importierte Netzflächen lassen sich nicht zuverlässig rekonstruieren und können nicht extrudiert werden.")
                require(source!="import") { "Importierte Netzflächen lassen sich nicht zuverlässig rekonstruieren und können nicht extrudiert werden." }
                visit(source)
            }
            is ExtrusionFeature -> {
                val s=profiles[f.region.sketchId] ?: throw IllegalArgumentException("Quellskizze fehlt: ${f.region.sketchId}")
                val regions=s.regions(ParameterEvaluator.evaluate(parameters))
                if(regions.none { it.id==f.region.regionId } && s.editableElements().any { it.id==f.region.regionId })
                    throw IllegalArgumentException("Kontur '${f.region.regionId}' ist offen und kann nicht extrudiert werden.")
                require(regions.any { it.id==f.region.regionId }) { "Quellregion fehlt: ${f.region.regionId}" }
                s.support?.let { r -> visit(r.featureId ?: r.sketchId?.let { bodyId(it,r.mirrored) } ?: "import") }
            }
        }
        visiting.remove(id);finished.add(id);order.add(f)
    }
    all.forEach { visit(it.id) }
    // Also validate supports of sketches that do not yet generate a body.
    sketches.forEach { s -> s.support?.let { r -> visit(r.featureId ?: r.sketchId?.let { bodyId(it,r.mirrored) } ?: "import") } }
    return order
}
/** One transaction shared by UI and the legacy MCP adapter. */
internal fun DocumentState.replaceSketch(sketch: Sketch,legacyExtrusion: Boolean): DocumentState {
    val old=sketches.firstOrNull { it.id==sketch.id }
    require(!legacyExtrusion || old==null || old.profileElements().size<=1) { "Der Ein-Profil-Befehl kann eine Mehr-Element-Skizze nicht ersetzen." }
    val singleLegacyProfile=sketch.editableElements().singleOrNull()?.takeIf { it.kind==SketchElementKind.PROFILE }
    val profile=(if(singleLegacyProfile!=null) sketch.syncLegacyElement() else sketch.withElements(sketch.editableElements())).copy(depth=null)
    if(legacyExtrusion) require(profile.profileElements().size==1) { "Die Extrusion benötigt genau eine geschlossene Skizzenregion." }
    var nextFeatures=effectiveFeatures()
    if(legacyExtrusion) {
        val primary=primaryExtrusion(sketch.id)
        val id=primary?.id ?: bodyId(sketch.id)
        val affected=setOf(id,bodyId(sketch.id,true))
        val replacements=mutableListOf<CadFeature>()
        sketch.depth?.let { depth ->
            replacements.add(ExtrusionFeature(id,RegionReference(sketch.id,profile.profileElements().first().id),depth))
            sketch.mirror?.let { replacements.add(MirrorFeature(bodyId(sketch.id,true),id,it,sketch.mirrorOffset)) }
        }
        val byId=replacements.associateBy { it.id };val previousIds=nextFeatures.map { it.id }.toSet()
        nextFeatures=nextFeatures.mapNotNull { if(it.id in affected) byId[it.id] else it }
        replacements.filter { it.id !in previousIds }.forEach { new ->
            val position=if(new is MirrorFeature) nextFeatures.indexOfFirst { it.id==new.sourceFeatureId }.takeIf { it>=0 }?.plus(1) else null
            nextFeatures=nextFeatures.toMutableList().apply { if(position!=null) add(position,new) else add(new) }
        }
    } else if(old!=null && (old.mirror!=profile.mirror || old.mirrorOffset!=profile.mirrorOffset)) {
        primaryExtrusion(sketch.id)?.let { primary ->
            val mirrorId=bodyId(sketch.id,true)
            nextFeatures=nextFeatures.filterNot { it.id==mirrorId }
            profile.mirror?.let { plane ->
                val position=nextFeatures.indexOfFirst { it.id==primary.id }+1
                nextFeatures=nextFeatures.toMutableList().apply { add(position,MirrorFeature(mirrorId,primary.id,plane,profile.mirrorOffset)) }
            }
        }
    }
    return copy(sketches=if(old==null) sketches+profile else sketches.map { if(it.id==sketch.id) profile else it },features=nextFeatures).canonicalFeatures()
}
/** Atomically updates a source sketch and one independently editable extrusion feature. */
internal fun DocumentState.replaceSketchExtrusion(sketch:Sketch,feature:ExtrusionFeature):DocumentState {
    return replaceSketchExtrusions(sketch,listOf(feature))
}
/** Updates a set of region extrusions as one document transaction, preserving feature IDs. */
internal fun DocumentState.replaceSketchExtrusions(sketch:Sketch,featuresToReplace:List<ExtrusionFeature>):DocumentState {
    require(featuresToReplace.isNotEmpty() && featuresToReplace.all { it.region.sketchId==sketch.id }) { "Die Extrusionen müssen auf ihre Quellskizze verweisen." }
    require(featuresToReplace.map { it.region.regionId }.distinct().size==featuresToReplace.size) { "Eine Region darf nur einmal extrudiert werden." }
    val base=replaceSketch(sketch.copy(depth=null),legacyExtrusion=false)
    val before=base.effectiveFeatures()
    val replacements=featuresToReplace.associateBy { it.id }
    val nextFeatures=before.map { replacements[it.id] ?: it } + featuresToReplace.filter { candidate -> before.none { it.id==candidate.id } }
    return base.copy(features=nextFeatures).canonicalFeatures()
}
internal fun DocumentState.withoutSketch(id: String): DocumentState {
    require(sketches.any { it.id==id }) { "Skizze existiert nicht." }
    val removed=effectiveFeatures().filterIsInstance<ExtrusionFeature>().filter { it.region.sketchId==id }.map { it.id }.toMutableSet()
    do { val before=removed.size;effectiveFeatures().filterIsInstance<MirrorFeature>().filter { it.sourceFeatureId in removed }.forEach { removed.add(it.id) } } while(removed.size!=before)
    return copy(sketches=sketches.filterNot { it.id==id },features=effectiveFeatures().filterNot { it.id in removed },
        display=display.copy(hiddenBodyIds=display.hiddenBodyIds-removed)).canonicalFeatures()
}
