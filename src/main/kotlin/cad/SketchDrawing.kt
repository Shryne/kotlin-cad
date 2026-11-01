package cad

import kotlin.math.*
import java.util.UUID

data class SketchPoint(val u: Double, val v: Double)
data class SketchView(val center: SketchPoint = SketchPoint(0.0,0.0), val scale: Double = 5.0) {
    init { require(scale.isFinite() && scale > 0 && center.u.isFinite() && center.v.isFinite()) }
    fun world(x: Double, y: Double, width: Double, height: Double) =
        SketchPoint(center.u + (x-width/2)/scale, center.v - (y-height/2)/scale)
    fun screen(point: SketchPoint, width: Double, height: Double) =
        SketchPoint(width/2 + (point.u-center.u)*scale, height/2 - (point.v-center.v)*scale)
    fun pan(dx: Double, dy: Double) = copy(center = SketchPoint(center.u-dx/scale,center.v+dy/scale))
    fun zoom(factor: Double, x: Double, y: Double, width: Double, height: Double): SketchView {
        val anchor = world(x,y,width,height)
        val next = copy(scale = (scale*factor).coerceIn(1e-8,1000.0))
        val shifted = next.world(x,y,width,height)
        return next.copy(center = SketchPoint(next.center.u+anchor.u-shifted.u,next.center.v+anchor.v-shifted.v))
    }
}
fun SketchPlane.project(p: Vec3) = when(this) {
    SketchPlane.XY -> SketchPoint(p.x,p.y)
    SketchPlane.XZ -> SketchPoint(p.x,p.z)
    SketchPlane.YZ -> SketchPoint(p.y,p.z)
}
/** A completed drag changes only the profile's dimensions, preserving its identity and plane. */
fun Sketch.drawn(start: SketchPoint, end: SketchPoint, snap: Boolean, values: Map<String,Quantity> = emptyMap(), fromCenter: Boolean = false): Sketch? {
    fun snapped(x: Double) = if (snap) round(x) else x
    val a = SketchPoint(snapped(start.u),snapped(start.v))
    val b = SketchPoint(snapped(end.u),snapped(end.v))
    fun mm(x: Double) = java.math.BigDecimal.valueOf(x).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" mm"
    return if (shape != SketchShape.CIRCLE) {
        val angle=rotation(values)
        val c=cos(angle);val s=sin(angle)
        val dx=b.u-a.u;val dy=b.v-a.v
        val du=c*dx+s*dy;val dv=-s*dx+c*dy
        if(fromCenter && shape==SketchShape.RECTANGLE) {
            val w=2*abs(du);val h=2*abs(dv)
            return if(w<1e-6 || h<1e-6) null else copy(u=mm(a.u),v=mm(a.v),width=mm(w),height=mm(h),centered=true)
        }
        val w=abs(du);val h=abs(dv)
        val lowU=min(0.0,du);val lowV=min(0.0,dv)
        if (w < 1e-6 || h < 1e-6) null else copy(u=mm(a.u+c*lowU-s*lowV),v=mm(a.v+s*lowU+c*lowV),width=mm(w),height=mm(h),centered=false)
    } else {
        val r = hypot(b.u-a.u,b.v-a.v)
        if (r < 1e-6) null else copy(u=mm(a.u),v=mm(a.v),width=mm(r),centered=false)
    }
}

internal fun sketchLength(value: Double): String =
    java.math.BigDecimal.valueOf(value).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" mm"

internal fun Sketch.addElement(element: SketchElement): Sketch = withElements(editableElements()+element)

internal fun openSketchPath(kind: SketchElementKind,points: List<SketchPoint>,closed: Boolean=false): SketchElement {
    require(kind==SketchElementKind.LINE || kind==SketchElementKind.POLYLINE)
    require(points.size >= (if(closed) 3 else 2) && (kind!=SketchElementKind.LINE || points.size==2) && (!closed || kind==SketchElementKind.POLYLINE))
    return SketchElement(id=UUID.randomUUID().toString(),kind=kind,
        vertices=points.map { SketchCoordinate(sketchLength(it.u),sketchLength(it.v)) },isClosed=closed)
}

internal fun sketchArc(center: SketchPoint,start: SketchPoint,end: SketchPoint): SketchElement {
    val radius=hypot(start.u-center.u,start.v-center.v)
    require(radius>1e-6 && abs(hypot(end.u-center.u,end.v-center.v)-radius)<=max(1e-4,radius*1e-4)) {
        "Bogenstart und Bogenende müssen denselben Abstand zum Mittelpunkt haben."
    }
    val a=atan2(start.v-center.v,start.u-center.u)
    val b=atan2(end.v-center.v,end.u-center.u)
    var sweep=b-a
    while(sweep<=-PI) sweep+=2*PI
    while(sweep>PI) sweep-=2*PI
    require(abs(sweep)>1e-6) { "Der Bogenwinkel darf nicht null sein." }
    fun angle(value: Double)=java.math.BigDecimal.valueOf(Math.toDegrees(value)).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" deg"
    return SketchElement(id=UUID.randomUUID().toString(),kind=SketchElementKind.ARC,
        u=sketchLength(center.u),v=sketchLength(center.v),width=sketchLength(radius),startAngle=angle(a),sweepAngle=angle(sweep))
}
