package cad

import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.*

private fun Vec3.toJsonVector()=buildJsonObject { put("x",x);put("y",y);put("z",z) }

/** A bounded, in-memory proposal. It never mutates the imported mesh. */
internal data class MeshCopyProposal(
    val id:String,val sourceRevision:String,val sketches:List<Sketch>,val features:List<CadFeature>,
    val parameters:List<Parameter>,val openingId:String,val originalOpeningDiameter:Double,
    val proposedOpeningDiameter:Double,val sourceVolume:Double,val copyVolume:Double,val body:MeshData,
    val deviation:SurfaceDeviation,val modelKind:String="plate",val uncertainty:String="Einzelne dünne Platte mit durchgehenden Öffnungen.",val baseParameters:List<Parameter>?=null
) {
    val sketch:Sketch get()=sketches.firstOrNull() ?: throw IllegalStateException("Der Vorschlag enthält keine Skizze.")
    val feature:CadFeature get()=features.first()
    fun apply(document:DocumentState):DocumentState {
        require(baseParameters==null || document.parameters==baseParameters) { "Die Parameter haben sich seit dem Vorschlag geändert. Erzeuge einen neuen Vorschlag." }
        require(document.mesh!=null && MeshAnalysisJobs.revisionOf(document.mesh)==sourceRevision) { "Das importierte Netz hat sich seit der Analyse geändert. Starte eine neue Analyse." }
        require(sketches.none { s -> document.sketches.any { it.id==s.id } } && features.none { f -> document.features.any { it.id==f.id } })
        require(parameters.none { p -> document.parameters.any { it.name==p.name } }) { "Ein vorgeschlagener Parametername wird bereits verwendet. Erzeuge einen neuen Vorschlag." }
        return document.copy(sketches=document.sketches+sketches,features=document.effectiveFeatures()+features,parameters=document.parameters+parameters)
            .canonicalFeatures()
    }
    fun description(unit:String)=buildJsonObject {
        put("proposalId",id);put("sourceRevision",sourceRevision);put("unit",unit);put("status","ready")
        if(sketches.size==1) put("sketch",sketches.single().json())
        if(features.size==1) put("feature",features.single().json())
        put("sketches",JsonArray(sketches.map { it.json() }));put("features",JsonArray(features.map { it.json() }))
        put("parameters",JsonArray(parameters.map { buildJsonObject { put("name",it.name);put("expression",it.expression) } }))
        put("modelKind",modelKind)
        if(modelKind=="plate") put("opening",buildJsonObject { put("contourId",openingId);put("originalDiameter",originalOpeningDiameter);put("proposedDiameter",proposedOpeningDiameter) })
        else put("assembly",buildJsonObject { put("partCount",features.size);put("separateBodies",true) })
        put("sourceVolume",sourceVolume);put("copyVolume",copyVolume);put("volumeChange",copyVolume-sourceVolume);put("bodyBounds",buildJsonObject { put("min",body.bounds.low.toJsonVector());put("max",body.bounds.high.toJsonVector()) });put("bodyTriangles",body.triangleCount)
        put("surfaceDeviation",deviation.json())
        put("uncertainty",uncertainty)
    }
}

/** Deterministic symmetric nearest-sample distances, intentionally labeled approximate. */
internal data class SurfaceDeviation(
    val sampleCount:Int,val sourceToCopyRms:Double,val sourceToCopyMax:Double,
    val copyToSourceRms:Double,val copyToSourceMax:Double
) {
    fun json()=buildJsonObject {
        put("method","symmetric nearest-vertex sample distance; deterministic, approximate, not a certified Hausdorff bound")
        put("unit","mm");put("samplesPerDirection",sampleCount)
        put("sourceToCopy",buildJsonObject { put("rms",sourceToCopyRms);put("maximum",sourceToCopyMax) })
        put("copyToSource",buildJsonObject { put("rms",copyToSourceRms);put("maximum",copyToSourceMax) })
    }
    companion object {
        private const val MAX_SAMPLES=512
        private fun sample(mesh:MeshData):List<Vec3> {
            val vertices=mesh.vertices
            if(vertices.size<=MAX_SAMPLES) return vertices.distinct()
            val stride=vertices.size.toDouble()/MAX_SAMPLES
            return (0 until MAX_SAMPLES).map { vertices[(it*stride).toInt()] }.distinct()
        }
        private fun distances(a:List<Vec3>,b:List<Vec3>):Pair<Double,Double> {
            require(a.isNotEmpty() && b.isNotEmpty())
            var sum=0.0;var maximum=0.0
            for(p in a) {
                val d2=b.minOf { q -> val delta=p-q;delta.dot(delta) }
                val d=sqrt(d2);sum+=d2;maximum=max(maximum,d)
            }
            return sqrt(sum/a.size) to maximum
        }
        fun between(source:MeshData,copy:MeshData):SurfaceDeviation {
            val a=sample(source);val b=sample(copy)
            val ab=distances(a,b);val ba=distances(b,a)
            return SurfaceDeviation(min(a.size,b.size),ab.first,ab.second,ba.first,ba.second)
        }
    }
}

/** First reconstruction pass is deliberately gated to a single flat, axis-independent plate. */
internal object MeshReconstructionJobs {
    private val proposals=ConcurrentHashMap<String,MeshCopyProposal>()
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue(2),{Thread(it,"cad-mesh-reconstruction").apply { isDaemon=true }},ThreadPoolExecutor.AbortPolicy())
    fun suggestAsync(document:DocumentState,analysisId:String,openingId:String?,openingDiameter:Double?)=
        java.util.concurrent.CompletableFuture.supplyAsync({ suggest(document,analysisId,openingId,openingDiameter) },worker)
    fun previewAsync(document:DocumentState,id:String)=
        java.util.concurrent.CompletableFuture.supplyAsync({ preview(document,id) },worker)
    fun suggest(document:DocumentState,analysisId:String,openingId:String?,openingDiameter:Double?):JsonObject {
        val mesh=document.mesh ?: throw IllegalArgumentException("Lade zuerst eine STL- oder OBJ-Datei.")
        val report=MeshAnalysisJobs.completedResult(analysisId)
        val revision=report.getValue("revision").jsonPrimitive.content
        require(report.getValue("unit").jsonPrimitive.content=="mm") { "Die Rekonstruktion erwartet Millimeter. Stelle die Projekteinheit auf mm und starte die Netzanalyse erneut." }
        require(MeshAnalysisJobs.revisionOf(mesh)==revision) { "Die Netzanalyse gehört nicht mehr zum aktuell geladenen Modell." }
        if(looksLikeRack(mesh,report)) require(openingId==null && openingDiameter==null) { "Ein Öffnungsdurchmesser gilt nur für den Plattenvorschlag. Für das Rack bitte ohne Öffnungsänderung vorschlagen." }
        val candidates=buildProposal(mesh,report,openingId,openingDiameter)
        if(candidates.second.isNotEmpty()) return buildJsonObject {
            put("status","needs-selection");put("sourceRevision",revision);put("unit",report.getValue("unit"));put("candidateOpenings",JsonArray(candidates.second));put("message","Mehrere vergleichbar große durchgehende Öffnungen passen als mittlere Öffnung. Wähle eine contourId aus, bevor ich die Kopie vorbereite.")
        }
        val proposal=checkNotNull(candidates.first)
        proposals[proposal.id]=proposal
        while(proposals.size>16) proposals.keys.firstOrNull()?.let(proposals::remove) ?: break
        return proposal.description(report.getValue("unit").jsonPrimitive.content)
    }
    /** General measured reconstruction: explicit construction, never a faceted mesh conversion. */
    fun proposeConstructionAsync(document:DocumentState,analysisId:String,name:String,shape:JsonObject,parameters:List<Parameter>)=
        java.util.concurrent.CompletableFuture.supplyAsync({ proposeConstruction(document,analysisId,name,shape,parameters) },worker)
    fun proposeConstruction(document:DocumentState,analysisId:String,name:String,shape:JsonObject,parameters:List<Parameter>):JsonObject {
        val mesh=requireNotNull(document.mesh) { "Lade zuerst eine STL- oder OBJ-Datei." }
        val report=MeshAnalysisJobs.completedResult(analysisId)
        val revision=report.getValue("revision").jsonPrimitive.content
        require(document.display.unit=="mm" && report.getValue("unit").jsonPrimitive.content=="mm") { "Die Rekonstruktion erwartet Millimeter." }
        require(MeshAnalysisJobs.revisionOf(mesh)==revision) { "Die Analyse gehört nicht zum aktuellen Modell." }
        require(parameters.size<=64 && parameters.map { it.name }.distinct().size==parameters.size && parameters.none { p -> document.parameters.any { it.name==p.name } }) { "Verwende höchstens 64 neue, eindeutige Parameternamen." }
        val id="construction-"+UUID.randomUUID()
        val feature=NativeShapeFeature(id,name,shape)
        val candidate=document.copy(features=document.effectiveFeatures()+feature,parameters=document.parameters+parameters).canonicalFeatures()
        val body=candidate.sceneBodies().single { it.id==id }.mesh
        fun volume(m:MeshData):Double { var sum=0.0;for(i in m.indices.indices step 3) {
            val a=m.vertices[m.indices[i]];val b=m.vertices[m.indices[i+1]];val c=m.vertices[m.indices[i+2]];sum+=a.dot(b.cross(c))/6
        };return abs(sum) }
        val proposal=MeshCopyProposal(id,revision,emptyList(),listOf(feature),parameters,"",0.0,0.0,volume(mesh),volume(body),body,
            SurfaceDeviation.between(mesh,body),"construction",
            "Expliziter Konstruktionsvorschlag aus Maßen und Geometrieoperationen; keine automatisch bewiesene Rekonstruktion. Rundungen, Fasen und verdeckte Details mit dem Original vergleichen.",document.parameters)
        proposals[id]=proposal
        while(proposals.size>16) proposals.keys.firstOrNull()?.let(proposals::remove) ?: break
        return proposal.description("mm")
    }
    fun get(id:String)=proposals[id] ?: throw IllegalArgumentException("Der Rekonstruktionsvorschlag ist abgelaufen oder unbekannt.")
    fun remove(id:String)=proposals.remove(id)
    fun preview(document:DocumentState,id:String):Pair<DocumentState,MeshData> {
        val proposal=get(id);val candidate=proposal.apply(document)
        val parts=candidate.sceneBodies().filter { it.id in proposal.features.map(CadFeature::id) }.map { it.mesh }
        require(parts.size==proposal.features.size) { "Ein vorgeschlagener Körper fehlt in der Vorschau." }
        val body=if(parts.size==1) parts.single() else combineMeshes("Parametrische Kopie",parts)
        return candidate to body
    }
    private data class CandidateResult(val first:MeshCopyProposal?,val second:List<JsonObject>)
    private data class P2(val x:Double,val y:Double)
    private data class Loop(val json:JsonObject,val center:P2,val radius:Double?,val points:List<P2>,val area:Double,val kind:String)
    private fun looksLikeRack(mesh:MeshData,report:JsonObject):Boolean {
        val d=mesh.bounds.size
        if(d.z<1200 || d.x<500 || d.y<40 || d.z/max(d.x,d.y)<1.35) return false
        return report.getValue("repeatedCylindricalPatterns").jsonArray.any { e ->
            val p=e.jsonObject;val axis=p.getValue("axis").jsonObject
            abs(axis.getValue("x").jsonPrimitive.double)>.96 && p.getValue("count").jsonPrimitive.int>=8 &&
                p.getValue("meanRadius").jsonPrimitive.double>1.0 && p.getValue("meanRadius").jsonPrimitive.double<d.y*.18
        }
    }
    private fun buildRackProposal(mesh:MeshData,report:JsonObject):MeshCopyProposal {
        val low=mesh.bounds.low;val high=mesh.bounds.high;val extent=mesh.bounds.size
        fun vector(j:JsonElement)=j.jsonObject.let { Vec3(it.getValue("x").jsonPrimitive.double,it.getValue("y").jsonPrimitive.double,it.getValue("z").jsonPrimitive.double) }
        fun fmt(v:Double)="%.6f".format(Locale.US,v)
        fun coord(o:JsonObject,axis:String):Double {
            val n=o.getValue("normal").jsonObject.getValue(axis).jsonPrimitive.double
            return o.getValue("offset").jsonPrimitive.double*(if(n<0) -1 else 1)
        }
        val planes=report.getValue("planarPatches").jsonArray.map { it.jsonObject }
        val cylinders=report.getValue("cylindricalPatches").jsonArray.map { it.jsonObject }
        val pattern=report.getValue("repeatedCylindricalPatterns").jsonArray.map { it.jsonObject }
            .filter { abs(it.getValue("axis").jsonObject.getValue("x").jsonPrimitive.double)>.96 }
            .maxByOrNull { it.getValue("count").jsonPrimitive.int }
            ?: throw IllegalArgumentException("Das Rack-Lochraster hat keine belastbare X-Achsengruppe.")
        val holeRadius=pattern.getValue("meanRadius").jsonPrimitive.double
        val xHoles=cylinders.filter { c ->
            val axis=vector(c.getValue("axis"));abs(axis.x)>.96 && abs(c.getValue("radius").jsonPrimitive.double-holeRadius)<holeRadius*.15
        }.sortedBy { vector(it.getValue("center")).x }
        val xCenters=xHoles.map { vector(it.getValue("center")).x }.distinctBy { round(it*10).toLong() }.sorted()
        require(xCenters.size>=4) { "Ich kann die beiden gelochten Rackstützen nicht sicher voneinander trennen." }
        val split=xCenters.zipWithNext().maxByOrNull { it.second-it.first }!!
        require(split.second-split.first>extent.x*.2) { "Die Lochgruppen zeigen keine klar getrennten Rackstützen." }
        val leftCenters=xCenters.filter { it<=split.first };val rightCenters=xCenters.filter { it>=split.second }
        require(leftCenters.size>=2 && rightCenters.size>=2) { "Für die Rackstützen fehlen gegenüberliegende Lochflächen." }
        val wallCoordinates=listOf("x","y").flatMap { axis ->
            planes.filter { abs(it.getValue("normal").jsonObject.getValue(axis).jsonPrimitive.double)>.98 }
                .map { coord(it,axis) }.distinctBy { round(it*10).toLong() }.sorted().zipWithNext().map { it.second-it.first }
        }.filter { it in .5..min(extent.x,extent.y)*.12 }
        val wall=wallCoordinates.groupingBy { round(it*10).toInt() }.eachCount().maxByOrNull { it.value }?.key?.div(10.0) ?: min(extent.x,extent.y)*.03
        require(wall in .8..extent.y*.12) { "Die Profilwandstärke kann nicht belastbar geschätzt werden." }
        val innerDepthGap=planes.filter { abs(it.getValue("normal").jsonObject.getValue("y").jsonPrimitive.double)>.98 }
            .map { coord(it,"y") }.distinctBy { round(it*10).toLong() }.sorted().zipWithNext().map { it.second-it.first }
            .filter { it in extent.y*.3..extent.y*.8 }.maxOrNull()
        val postDepth=(innerDepthGap?.plus(2*wall) ?: extent.y*.6).coerceIn(extent.y*.35,extent.y*.8)
        val xGapLeft=leftCenters.zipWithNext().map { it.second-it.first }.filter { it>wall*2 }.average()
        val xGapRight=rightCenters.zipWithNext().map { it.second-it.first }.filter { it>wall*2 }.average()
        require(xGapLeft.isFinite() && xGapRight.isFinite()) { "Die Profilbreite lässt sich nicht aus den Bohrungswänden ableiten." }
        val postWidth=(listOf(xGapLeft,xGapRight).average()+wall).coerceAtMost(extent.x*.15)
        val postSpacing=rightCenters.average()-leftCenters.average()
        require(postSpacing>postWidth*2 && postSpacing<extent.x) { "Der Abstand der Rackstützen ist ungültig." }
        val leftPostX=leftCenters.average()-postWidth/2
        val holeY=xHoles.map { vector(it.getValue("center")).y }.average()
        val postY=holeY-postDepth/2
        val zHolePositions=xHoles.map { vector(it.getValue("center")).z }.distinctBy { round(it*10).toLong() }.sorted()
        require(zHolePositions.size>=3) { "Das Rack-Lochraster enthält zu wenige Höhenpositionen." }
        val holePitch=zHolePositions.zipWithNext().map { it.second-it.first }.average()
        require(holePitch>holeRadius*4) { "Der gemessene Lochabstand ist zu klein." }
        val areaThreshold=extent.x*extent.y*.03
        val plateThickness=planes.filter { p ->
            abs(p.getValue("normal").jsonObject.getValue("z").jsonPrimitive.double)>.98 &&
                p.getValue("area").jsonPrimitive.double>areaThreshold && coord(p,"z") in (low.z+1)..(low.z+min(60.0,extent.z*.08))
        }.minOfOrNull { coord(it,"z")-low.z } ?: throw IllegalArgumentException("Die Grundplattenstärke lässt sich aus der STL nicht trennen.")
        val beamBottom=planes.filter { p ->
            abs(p.getValue("normal").jsonObject.getValue("z").jsonPrimitive.double)>.98 &&
                p.getValue("area").jsonPrimitive.double>areaThreshold && coord(p,"z") in (high.z-200)..(high.z-5)
        }.maxOfOrNull { coord(it,"z") } ?: throw IllegalArgumentException("Der obere Querträger ist nicht eindeutig erkennbar.")
        val beamHeight=high.z-beamBottom
        val plateLength=extent.x-postSpacing
        val plateDepth=extent.y
        require(plateLength>postWidth*1.5 && plateDepth>postDepth*1.1 && beamHeight in postWidth*.35..postWidth*2.5) { "Die Grundplatten oder der Querträger passen nicht zu einem rechteckigen Rack." }
        val zCylinders=cylinders.filter { c -> abs(vector(c.getValue("axis")).z)>.96 && abs(c.getValue("radius").jsonPrimitive.double-holeRadius)<holeRadius*.18 }
        val rackMidX=leftPostX+postSpacing/2
        val localBaseHoles=zCylinders.map { vector(it.getValue("center")) }.filter { it.x<rackMidX }.map { (it.x-low.x) to (it.y-low.y) }
        val holeXs=localBaseHoles.map { it.first }.distinctBy { round(it*10).toLong() }.sorted()
        val holeYs=localBaseHoles.map { it.second }.distinctBy { round(it*10).toLong() }.sorted()
        val baseX1=holeXs.getOrNull(0) ?: plateLength*.27;val baseX2=holeXs.lastOrNull()?.takeIf { holeXs.size>=2 } ?: plateLength*.73
        val baseY1=holeYs.getOrNull(0) ?: plateDepth*.25;val baseY2=holeYs.lastOrNull()?.takeIf { holeYs.size>=2 } ?: plateDepth*.75
        val suffix=UUID.randomUUID().toString().replace("-","").take(8)
        val parameters=mutableListOf<Parameter>()
        fun param(key:String,value:Double):String {
            val name="${key}_$suffix";parameters+=Parameter(name,"${fmt(value)} mm");return name
        }
        val names=mapOf(
            "postWidth" to param("rackPostWidth",postWidth),"postDepth" to param("rackPostDepth",postDepth),"wall" to param("rackWallThickness",wall),
            "postHeight" to param("rackPostHeight",high.z-low.z-plateThickness),"postSpacing" to param("rackPostSpacing",postSpacing),
            "plateLength" to param("rackBaseLength",plateLength),"plateDepth" to param("rackBaseDepth",plateDepth),"plateThickness" to param("rackBaseThickness",plateThickness),
            "holeRadius" to param("rackHoleRadius",holeRadius),"holeStart" to param("rackHoleStart",zHolePositions.first()-low.z-plateThickness),
            "holePitch" to param("rackHolePitch",holePitch),"baseHoleRadius" to param("rackFootHoleRadius",holeRadius),
            "baseX1" to param("rackFootHoleX1",baseX1),"baseX2" to param("rackFootHoleX2",baseX2),
            "baseY1" to param("rackFootHoleY1",baseY1),"baseY2" to param("rackFootHoleY2",baseY2),
            "leftX" to param("rackLeftPostX",leftPostX),"postY" to param("rackPostY",postY),
            "baseX" to param("rackBaseX",low.x),"baseY" to param("rackBaseY",low.y),"bottomZ" to param("rackBottomZ",low.z),
            "beamHeight" to param("rackBeamHeight",beamHeight)
        )
        fun r(key:String)=buildJsonObject { put("parameter",names.getValue(key)) }
        fun e(text:String)=buildJsonObject { put("expression",text) }
        fun box(x:JsonElement,y:JsonElement,z:JsonElement)=buildJsonObject { put("type","box");put("x",x);put("y",y);put("z",z) }
        fun translate(shape:JsonObject,x:JsonElement,y:JsonElement,z:JsonElement)=buildJsonObject { put("type","translate");put("shape",shape);put("offset",JsonArray(listOf(x,y,z))) }
        fun cut(a:JsonObject,b:JsonObject)=buildJsonObject { put("type","cut");put("a",a);put("b",b) }
        val postShapeBase=cut(box(r("postWidth"),r("postDepth"),r("postHeight")),translate(
            box(e("${names.getValue("postWidth")} - 2 * ${names.getValue("wall")}"),e("${names.getValue("postDepth")} - 2 * ${names.getValue("wall")}"),e("${names.getValue("postHeight")} + 2 mm")),
            r("wall"),r("wall"),e("-1 mm")))
        var postShape=postShapeBase
        for(index in zHolePositions.indices) for(side in 0..1) {
            val xExpr=if(side==0) "-1 mm" else "${names.getValue("postWidth")} - ${names.getValue("wall")} - 1 mm"
            val length=e("${names.getValue("wall")} + 2 mm")
            val outer=JsonArray((0 until 32).map { i -> val a=2*Math.PI*i/32
                JsonArray(listOf(e(xExpr),e("${names.getValue("postDepth")} / 2 + ${fmt(holeRadius*cos(a))} mm"),e("${names.getValue("holeStart")} + ${names.getValue("holePitch")} * $index + ${fmt(holeRadius*sin(a))} mm")))
            })
            val tool=buildJsonObject { put("type","extrude");put("outer",outer);put("vector",JsonArray(listOf(length,JsonPrimitive(0),JsonPrimitive(0)))) }
            postShape=cut(postShape,tool)
        }
        val beamShape=cut(box(r("postSpacing"),r("postDepth"),r("beamHeight")),translate(
            box(e("${names.getValue("postSpacing")} + 2 mm"),e("${names.getValue("postDepth")} - 2 * ${names.getValue("wall")}"),e("${names.getValue("beamHeight")} - 2 * ${names.getValue("wall")}")),
            e("-1 mm"),r("wall"),r("wall")))
        fun basePlate():JsonObject {
            var shape:JsonObject=box(r("plateLength"),r("plateDepth"),r("plateThickness"))
            for(x in listOf("baseX1","baseX2")) for(y in listOf("baseY1","baseY2")) {
                val height=e("${names.getValue("plateThickness")} + 2 mm")
                val cutter=buildJsonObject { put("type","cylinder");put("radius",r("baseHoleRadius"));put("height",height) }
                shape=cut(shape,translate(cutter,r(x),r(y),e("-1 mm")))
            }
            return shape
        }
        val partFeatures=listOf(
            NativeShapeFeature("rack-left-post-$suffix","Linke gelochte Vierkantstütze",postShape,x=names.getValue("leftX"),y=names.getValue("postY"),z="${names.getValue("bottomZ")} + ${names.getValue("plateThickness")}"),
            NativeShapeFeature("rack-right-post-$suffix","Rechte gelochte Vierkantstütze",postShape,x="${names.getValue("leftX")} + ${names.getValue("postSpacing")}",y=names.getValue("postY"),z="${names.getValue("bottomZ")} + ${names.getValue("plateThickness")}"),
            NativeShapeFeature("rack-top-beam-$suffix","Oberer Hohlprofil-Querträger",beamShape,x="${names.getValue("leftX")} + ${names.getValue("postWidth")} / 2",y=names.getValue("postY"),z="${fmt(high.z)} mm - ${names.getValue("beamHeight")}"),
            NativeShapeFeature("rack-left-foot-$suffix","Linke gelochte Grundplatte",basePlate(),x="${names.getValue("leftX")} - (${names.getValue("plateLength")} - ${names.getValue("postWidth")}) / 2",y=names.getValue("baseY"),z=names.getValue("bottomZ")),
            NativeShapeFeature("rack-right-foot-$suffix","Rechte gelochte Grundplatte",basePlate(),x="${names.getValue("leftX")} + ${names.getValue("postSpacing")} - (${names.getValue("plateLength")} - ${names.getValue("postWidth")}) / 2",y=names.getValue("baseY"),z=names.getValue("bottomZ"))
        )
        val id="reconstruction-rack-$suffix"
        val proposal=MeshCopyProposal(id,MeshAnalysisJobs.revisionOf(mesh),emptyList(),partFeatures,parameters,"",0.0,0.0,meshVolume(mesh),0.0,mesh,SurfaceDeviation(0,0.0,0.0,0.0,0.0),"squat-rack",
            "Vorschlag für zwei rechteckige Hohlprofilstützen, einen oberen Querträger und zwei gelochte Grundplatten. Wandstärke und Lochachsen werden aus planaren und zylindrischen Netzkandidaten geschätzt. Verbindungen und Fertigungsabsicht sind nicht erkannt; Rundungen und verdeckte Teile bleiben unbekannt.")
        val candidate=proposal.apply(DocumentState(mesh=mesh));val parts=candidate.sceneBodies().filter { body -> partFeatures.any { it.id==body.id } }.map { it.mesh }
        require(parts.size==partFeatures.size) { "Nicht alle Rack-Teile ließen sich als OCCT-Körper auswerten." }
        val combined=combineMeshes("Parametrische Rack-Kopie",parts)
        val bounds=combined.bounds
        require(abs(bounds.size.x-extent.x)<max(2.0,extent.x*.03) && abs(bounds.size.y-extent.y)<max(2.0,extent.y*.03) && abs(bounds.size.z-extent.z)<max(2.0,extent.z*.03)) { "Die vorgeschlagenen Rack-Teile weichen zu stark von den Netzausmaßen ab." }
        val copyVolume=parts.sumOf(::meshVolume)
        require(copyVolume>0 && copyVolume<meshVolume(mesh)*1.2) { "Das Rack-Geometrie-Gate hat ein ungültiges Volumen ergeben." }
        return proposal.copy(body=combined,copyVolume=copyVolume,deviation=SurfaceDeviation.between(mesh,combined))
    }
    private fun buildProposal(mesh:MeshData,report:JsonObject,openingId:String?,openingDiameter:Double?):CandidateResult {
        if(looksLikeRack(mesh,report)) return CandidateResult(buildRackProposal(mesh,report),emptyList())
        require(report.getValue("quality").jsonObject.getValue("closedManifold").jsonPrimitive.boolean) { "Für eine sichere Plattenkopie muss das Netz geschlossen und manifold sein." }
        val planes=report.getValue("planarPatches").jsonArray.map { it.jsonObject }
        data class Plane(val json:JsonObject,val n:Vec3,val d:Double,val area:Double)
        fun vecValue(element:JsonElement):Vec3 { val p=element.jsonObject;return Vec3(p.getValue("x").jsonPrimitive.double,p.getValue("y").jsonPrimitive.double,p.getValue("z").jsonPrimitive.double) }
        fun vec(o:JsonObject,key:String)=vecValue(o.getValue(key))
        fun fmt(value:Double)="%.9f".format(Locale.US,value)
        val ps=planes.map { p -> Plane(p,vec(p,"normal").normalized(),p.getValue("offset").jsonPrimitive.double,p.getValue("area").jsonPrimitive.double) }
        val pair=ps.flatMapIndexed { i,a -> ps.drop(i+1).mapNotNull { b ->
            val alignment=a.n.dot(b.n);if(abs(alignment)<.999) null else {
                val bd=if(alignment<0) -b.d else b.d;val separation=abs(a.d-bd)
                val largeArea=min(a.area,b.area);val areaRatio=largeArea/max(a.area,b.area)
                if(separation<1e-5 || areaRatio<.65) null else Triple(a,b,largeArea)
            }
        } }.maxByOrNull { it.third } ?: throw IllegalArgumentException("Ich finde keine zwei ausreichend großen, parallelen Plattenflächen. Profile, gekrümmte oder komplexe Bauteile benötigen eine andere Rekonstruktionsmethode.")
        var normal=pair.first.n;var low=pair.first.d;var high=if(normal.dot(pair.second.n)<0) -pair.second.d else pair.second.d
        if(high<low) { normal=normal*(-1.0);low= -low;high= -high }
        val thickness=high-low;require(thickness>0) { "Die parallelen Flächen ergeben keine gültige Plattendicke." }
        val reference=if(abs(normal.x)<.85) Vec3(1.0,0.0,0.0) else Vec3(0.0,1.0,0.0)
        val u=(reference-normal*reference.dot(normal)).normalized();val v=normal.cross(u).normalized();val frame=SketchFrame(normal*low,u,v)
        val tolerance=max(1e-5,thickness*1e-4)
        val projected=mesh.vertices.mapNotNull { point -> val distance=(point-frame.origin).dot(normal);if(distance in -tolerance..thickness+tolerance) frame.project(point).let { P2(it.u,it.v) } else null }.distinctBy { "${round(it.x*1e5).toLong()}:${round(it.y*1e5).toLong()}" }
        val hull=convexHull(projected)
        require(hull.size in 4..32) { "Der äußere Plattenrand lässt sich nicht sicher aus dem Netz ableiten." }
        val bounds=listOf(hull.minOf { it.x },hull.minOf { it.y },hull.maxOf { it.x },hull.maxOf { it.y })
        val width=bounds[2]-bounds[0];val height=bounds[3]-bounds[1]
        require(width>thickness*2 && height>thickness*2 && thickness<min(width,height)*.4 && polygonArea(hull)>width*height*.6) { "Der gefundene Querschnitt ist keine belastbare dünne Plattenkontur." }
        val planeA=pair.first.json.getValue("id").jsonPrimitive.content;val planeB=pair.second.json.getValue("id").jsonPrimitive.content
        val rawLoops=report.getValue("contours").jsonArray.map { it.jsonObject }.filter { it.getValue("planeId").jsonPrimitive.content in setOf(planeA,planeB) }
        fun toLoop(j:JsonObject):Loop {
            val points=j.getValue("points").jsonArray.map { p -> frame.project(vecValue(p)).let { P2(it.u,it.v) } }
            val centerWorld=j["center"]?.takeIf { it!=JsonNull }?.let(::vecValue)
            val center=centerWorld?.let { frame.project(it).let { p -> P2(p.u,p.v) } } ?: P2(points.map { it.x }.average(),points.map { it.y }.average())
            return Loop(j,center,j["radius"]?.takeIf { it!=JsonNull }?.jsonPrimitive?.double,points,j.getValue("area").jsonPrimitive.double,j.getValue("kind").jsonPrimitive.content)
        }
        val aLoops=rawLoops.filter { it.getValue("planeId").jsonPrimitive.content==planeA }.map(::toLoop)
        val bLoops=rawLoops.filter { it.getValue("planeId").jsonPrimitive.content==planeB }.map(::toLoop)
        val paired=aLoops.mapNotNull { a -> bLoops.firstOrNull { b -> a.kind==b.kind && hypot(a.center.x-b.center.x,a.center.y-b.center.y)<max(.1,thickness*.03) && abs(a.area-b.area)/max(a.area,b.area)<.04 }?.let { a to it } }
            .filter { (a,_) -> pointInPolygon(a.center,hull) && (a.radius==null || distanceToBoundary(a.center,hull)>a.radius+thickness*.02) }
        val circular=paired.filter { it.first.radius!=null && it.first.kind=="circular-contour" }.map { it.first }
        require(circular.size>=2) { "Ich finde nicht genug durchgehende Kreisöffnungen, um eine technische Plattenkopie sicher vorzuschlagen." }
        val sorted=circular.sortedByDescending { it.radius!! }
        val opening=openingId?.let { id -> sorted.firstOrNull { it.json.getValue("id").jsonPrimitive.content==id } ?: throw IllegalArgumentException("Die ausgewählte Öffnung '$id' ist kein erkannter durchgehender Kreis.") }
            ?: sorted.first()
        val similarlyLarge=sorted.filter { it!==opening && abs(it.radius!!-opening.radius!!)/opening.radius!!<.15 }
        if(openingId==null && similarlyLarge.isNotEmpty()) return CandidateResult(null,(listOf(opening)+similarlyLarge).map { c -> buildJsonObject { put("contourId",c.json.getValue("id"));put("center",c.json.getValue("center"));put("diameter",c.radius!!*2);put("unit",report.getValue("unit")) } })
        val originalDiameter=opening.radius!!*2
        val desired=openingDiameter ?: originalDiameter
        require(desired.isFinite() && desired in originalDiameter..min(width,height)*.8) { "Der gewünschte Öffnungsdurchmesser muss mindestens ${fmt(originalDiameter)} mm und kleiner als die Plattenkontur sein." }
        val suffix=UUID.randomUUID().toString().replace("-","").take(8)
        val thicknessName="copyThickness_$suffix";val openingName="openingRadius_$suffix";val fastenerName="fastenerRadius_$suffix"
        val elements=mutableListOf<SketchElement>()
        elements+=SketchElement("plate-outline",SketchShape.RECTANGLE,"${fmt(bounds[0])} mm","${fmt(bounds[1])} mm","${fmt(width)} mm","${fmt(height)} mm",kind=SketchElementKind.PROFILE)
        val smallRadii=sorted.drop(1).map { it.radius!! };val fastenerRadius=smallRadii.groupingBy { round(it*100).toInt() }.eachCount().maxByOrNull { it.value }?.key?.div(100.0) ?: sorted.last().radius!!
        paired.forEachIndexed { index,(loop,_) ->
            val id=loop.json.getValue("id").jsonPrimitive.content
            if(loop.kind=="circular-contour" && loop.radius!=null) {
                val center=loop.center
                val radiusExpression=if(id==opening.json.getValue("id").jsonPrimitive.content) openingName else if(abs(loop.radius-fastenerRadius)<max(.1,fastenerRadius*.03)) fastenerName else "${fmt(loop.radius)} mm"
                elements+=SketchElement("cutout-${index+1}",SketchShape.CIRCLE,"${fmt(center.x)} mm","${fmt(center.y)} mm",radiusExpression,kind=SketchElementKind.PROFILE)
            } else if(loop.points.size>=8) {
                val step=max(1,ceil(loop.points.size/128.0).toInt());val points=loop.points.filterIndexed { i,_ -> i%step==0 }.map { SketchCoordinate("${fmt(it.x)} mm","${fmt(it.y)} mm") }
                if(points.size>=8) elements+=SketchElement("cutout-${index+1}",SketchShape.RECTANGLE,kind=SketchElementKind.POLYLINE,vertices=points,isClosed=true)
            }
        }
        require(elements.size>=4) { "Die durchgehenden Konturen konnten nicht als Platte mit mehreren Öffnungen segmentiert werden." }
        val sketchId="reconstruction-sketch-$suffix";val featureId="reconstruction-body-$suffix"
        val sketch=Sketch(sketchId,"Parametrische Kopie der Monitorhalterung",SketchPlane.XY,SketchShape.RECTANGLE,"0 mm","0 mm","1 mm","1 mm",frame=frame,elements=elements)
        val feature=ExtrusionFeature(featureId,RegionReference(sketchId,"plate-outline"),thicknessName)
        val parameters=listOf(Parameter(thicknessName,"${fmt(thickness)} mm"),Parameter(openingName,"${fmt(desired/2)} mm"),Parameter(fastenerName,"${fmt(fastenerRadius)} mm"))
        val sourceVolume=meshVolume(mesh);require(sourceVolume.isFinite() && sourceVolume>1e-9) { "Das importierte Netz hat kein verlässliches positives Volumen." }
        val proposal=MeshCopyProposal("reconstruction-$suffix",MeshAnalysisJobs.revisionOf(mesh),listOf(sketch),listOf(feature),parameters,opening.json.getValue("id").jsonPrimitive.content,originalDiameter,desired,sourceVolume,0.0,mesh,SurfaceDeviation(0,0.0,0.0,0.0,0.0))
        val candidate=proposal.apply(DocumentState(mesh=mesh)).sceneBodies().first { it.id==featureId }.mesh
        require(candidate.bounds.size.let { abs(it.x-mesh.bounds.size.x)<.15 && abs(it.y-mesh.bounds.size.y)<.15 && abs(it.z-mesh.bounds.size.z)<.15 }) { "Die rekonstruierte Platte passt nicht zu den Außenmaßen des Netzes." }
        return CandidateResult(proposal.copy(body=candidate,copyVolume=meshVolume(candidate),deviation=SurfaceDeviation.between(mesh,candidate)),emptyList())
    }
    private fun convexHull(points:List<P2>):List<P2> {
        val sorted=points.sortedWith(compareBy<P2> { it.x }.thenBy { it.y });if(sorted.size<3)return sorted
        fun cross(o:P2,a:P2,b:P2)=(a.x-o.x)*(b.y-o.y)-(a.y-o.y)*(b.x-o.x)
        val lower=mutableListOf<P2>();for(p in sorted) { while(lower.size>=2 && cross(lower[lower.size-2],lower.last(),p)<=1e-10)lower.removeAt(lower.lastIndex);lower+=p }
        val upper=mutableListOf<P2>();for(p in sorted.asReversed()) { while(upper.size>=2 && cross(upper[upper.size-2],upper.last(),p)<=1e-10)upper.removeAt(upper.lastIndex);upper+=p }
        return (lower.dropLast(1)+upper.dropLast(1))
    }
    private fun polygonArea(points:List<P2>)=abs(points.indices.sumOf { i -> val a=points[i];val b=points[(i+1)%points.size];a.x*b.y-b.x*a.y })/2
    private fun pointInPolygon(p:P2,poly:List<P2>):Boolean { var inside=false;for(i in poly.indices) { val a=poly[i];val b=poly[(i+1)%poly.size];if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)inside=!inside };return inside }
    private fun distanceToBoundary(p:P2,poly:List<P2>):Double=poly.indices.minOf { i -> val a=poly[i];val b=poly[(i+1)%poly.size];val dx=b.x-a.x;val dy=b.y-a.y;val t=(((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0);hypot(p.x-a.x-t*dx,p.y-a.y-t*dy) }
    private fun meshVolume(mesh:MeshData):Double { var value=0.0;for(i in mesh.indices.indices step 3) { val a=mesh.vertices[mesh.indices[i]];val b=mesh.vertices[mesh.indices[i+1]];val c=mesh.vertices[mesh.indices[i+2]];value+=a.dot(b.cross(c))/6.0 };return abs(value) }
}
internal fun combineMeshes(name:String,parts:List<MeshData>):MeshData {
    val vertices=ArrayList<Vec3>();val indices=ArrayList<Int>()
    parts.forEach { part -> val offset=vertices.size;vertices.addAll(part.vertices);part.indices.forEach { indices.add(it+offset) } }
    return MeshData(name,vertices,indices.toIntArray())
}
