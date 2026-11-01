package cad

import kotlinx.serialization.json.*

/** Transient spatial intent, not a new feature or a persistent mesh-face identifier. */
internal data class ChatReference(val id:String,val bodyId:String,val bodyName:String,val triangle:Int,val point:Vec3,val normal:Vec3,val radius:Double,val revision:String,val imported:Boolean) {
    fun json()=buildJsonObject {
        put("id",id);put("bodyId",bodyId);put("bodyName",bodyName);put("triangle",triangle)
        put("point",point.measureJson());put("normal",normal.measureJson());put("radiusMm",radius);put("sourceRevision",revision);put("importedMesh",imported)
        put("meaning","User-marked neighborhood around a picked surface point. Radius is a spatial reference, not an exact cut boundary. Triangle index is valid only for this sourceRevision. Ask about direction/extent when ambiguous; do not treat a mark as permission for unrelated changes.")
    }
}
internal fun DocumentState.chatReference(sceneTriangle:Int,point:Vec3,radius:Double,id:String):ChatReference {
    require(display.unit=="mm") { "Markierungen benötigen Millimeter als Projekteinheit." }
    require(sceneTriangle>=0 && radius.isFinite() && radius in .1..10000.0)
    require(listOf(point.x,point.y,point.z).all { it.isFinite() })
    var index=sceneTriangle
    for(body in sceneBodies().filter { it.visible }) {
        val mesh=body.mesh
        if(index<mesh.triangleCount) {
            val a=mesh.vertices[mesh.indices[index*3]];val b=mesh.vertices[mesh.indices[index*3+1]];val c=mesh.vertices[mesh.indices[index*3+2]]
            require((point-closestOnTriangle(point,SurfaceTriangle(a,b,c,index))).norm()<=maxOf(.001,mesh.bounds.span*1e-6)) { "Markierung liegt nicht auf der gewählten Fläche." }
            return ChatReference(id,body.id,mesh.name,index,point,(b-a).cross(c-a).normalized(),radius,MeshAnalysisJobs.revisionOf(mesh),body.id=="import")
        }
        index-=mesh.triangleCount
    }
    error("Die markierte Fläche existiert nicht mehr.")
}
