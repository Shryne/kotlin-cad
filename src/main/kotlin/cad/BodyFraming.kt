package cad

import kotlin.math.*

internal data class ViewArea(val left: Double,val top: Double,val width: Double,val height: Double)
internal fun Bounds.corners()=listOf(low.x,high.x).flatMap { x -> listOf(low.y,high.y).flatMap { y -> listOf(low.z,high.z).map { z -> Vec3(x,y,z) } } }
internal fun cameraBaseDistance(width: Double,height: Double,fov: Double): Double {
    val aspect=(width/height.coerceAtLeast(1.0)).coerceAtLeast(.1)
    return 225.0/sin(atan(tan(Math.toRadians(fov/2))*min(1.0,aspect)))
}
internal fun rotatedWorld(point: Vec3,center: Vec3,scale: Double,yaw: Double,pitch: Double): Vec3 {
    val x=(point.x-center.x)*scale;val y=-(point.z-center.z)*scale;val z=(point.y-center.y)*scale
    val a=Math.toRadians(yaw);val p=Math.toRadians(pitch)
    val rx=x*cos(a)+z*sin(a);val rz=-x*sin(a)+z*cos(a)
    return Vec3(rx,y*cos(p)-rz*sin(p),y*sin(p)+rz*cos(p))
}
/** Solve perspective bounds without changing orientation, the model frame, or document geometry. */
internal fun CameraPose.fittedTo(bounds: Bounds,center: Vec3,scale: Double,width: Double,height: Double,area: ViewArea,fov: Double=35.0): CameraPose {
    require(width>0 && height>0 && area.width>0 && area.height>0)
    val points=bounds.corners().map { rotatedWorld(it,center,scale,yaw,pitch) }
    val target=rotatedWorld(bounds.center,center,scale,yaw,pitch)
    val focal=height/2/tan(Math.toRadians(fov/2))
    val nx=(area.left+area.width/2-width/2)/focal
    val ny=(area.top+area.height/2-height/2)/focal
    val halfW=area.width*.43;val halfH=area.height*.43
    var distance=.2-target.z
    points.forEach { point ->
        val dz=point.z-target.z
        distance=max(distance,max(abs(focal*(point.x-target.x-nx*dz))/halfW-point.z,abs(focal*(point.y-target.y-ny*dz))/halfH-point.z))
        distance=max(distance,.2-point.z)
    }
    val base=cameraBaseDistance(width,height,fov)
    val zoom=(base/distance.coerceAtLeast(.01)).coerceIn(.05,40.0)
    val depth=base/zoom+target.z
    return copy(panX=nx*depth-target.x,panY=ny*depth-target.y,zoom=zoom)
}
