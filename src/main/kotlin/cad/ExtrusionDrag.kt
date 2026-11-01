package cad

import kotlin.math.*

/** Same signed axis as the actual solid, including the historical XZ origin plane. */
fun Sketch.extrusionAxis(): Vec3 = frame?.normal ?: when(plane) {
    SketchPlane.XY -> Vec3(0.0,0.0,1.0)
    SketchPlane.XZ -> Vec3(0.0,1.0,0.0)
    SketchPlane.YZ -> Vec3(1.0,0.0,0.0)
}
fun axisDistance(origin: Vec3,axis: Vec3,rayOrigin: Vec3,rayDirection: Vec3): Double? {
    val d=rayDirection.normalized()
    val b=axis.dot(d);val denominator=1-b*b
    if(denominator<.0025) return null
    val w=origin-rayOrigin
    val t=(b*d.dot(w)-axis.dot(w))/denominator
    return t.takeIf { it.isFinite() && d.dot(w)+b*t>=0 }
}
data class ExtrusionDrag(val origin: Vec3,val axis: Vec3,val initialDepth: Double,val grabbedAt: Double) {
    fun depth(rayOrigin: Vec3,rayDirection: Vec3,fine: Boolean): Double? {
        val current=axisDistance(origin,axis,rayOrigin,rayDirection) ?: return null
        val step=if(fine) .1 else 1.0
        return (round((initialDepth+current-grabbedAt)/step)*step).coerceIn(-1e9,1e9)
    }
}
