package cad

import kotlin.math.abs

fun Sketch.drawingFrame(): SketchFrame = frame ?: SketchFrame(Vec3(0.0,0.0,0.0),
    when(plane) { SketchPlane.YZ -> Vec3(0.0,1.0,0.0);else -> Vec3(1.0,0.0,0.0) },
    when(plane) { SketchPlane.XY -> Vec3(0.0,1.0,0.0);else -> Vec3(0.0,0.0,1.0) })

/** Intersect a camera ray with the actual support plane, then return its local drawing coordinates. */
fun SketchFrame.intersectRay(origin: Vec3,direction: Vec3): SketchPoint? {
    val denominator=direction.dot(normal)
    if(direction.norm()<1e-12 || abs(denominator)<direction.norm()*1e-8) return null
    val distance=(this.origin-origin).dot(normal)/denominator
    if(distance<0 || !distance.isFinite()) return null
    val point=origin+direction*distance
    return project(point).takeIf { it.u.isFinite() && it.v.isFinite() }
}
