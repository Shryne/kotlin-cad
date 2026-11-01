package cad

import kotlin.math.*

/** Plane-local interaction geometry, shared by the renderer and interaction tests. */
data class ProfileHandle(val id: Int,val point: SketchPoint)
fun Sketch.handles(values: Map<String,Quantity>): List<ProfileHandle> {
    val x=ParameterEvaluator.expression(u,values).value;val y=ParameterEvaluator.expression(v,values).value
    val w=ParameterEvaluator.expression(width,values).value
    val h=if(shape==SketchShape.CIRCLE) w*2 else ParameterEvaluator.expression(height,values).value
    val a=rotation(values)
    fun p(dx: Double,dy: Double)=SketchPoint(x+cos(a)*dx-sin(a)*dy,y+sin(a)*dx+cos(a)*dy)
    val corners=when(shape) {
        SketchShape.RECTANGLE -> if(centered) listOf(p(-w/2,-h/2),p(w/2,-h/2),p(w/2,h/2),p(-w/2,h/2)) else listOf(p(0.0,0.0),p(w,0.0),p(w,h),p(0.0,h))
        SketchShape.TRIANGLE -> listOf(p(0.0,0.0),p(w,0.0),p(w/2,h))
        SketchShape.CIRCLE -> listOf(p(w,0.0),p(0.0,w),p(-w,0.0),p(0.0,-w))
    }
    return corners.mapIndexed { index,point -> ProfileHandle(index,point) }+
        ProfileHandle(4,if(shape==SketchShape.CIRCLE || centered) SketchPoint(x,y) else p(w/2,h/2))
}
fun sketchMm(value: Double)=java.math.BigDecimal.valueOf(value).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" mm"
fun Sketch.resizeHandle(id: Int,target: SketchPoint,values: Map<String,Quantity>): Sketch? {
    val x=ParameterEvaluator.expression(u,values).value;val y=ParameterEvaluator.expression(v,values).value
    val w=ParameterEvaluator.expression(width,values).value
    val a=rotation(values);val c=cos(a);val s=sin(a)
    val dx=c*(target.u-x)+s*(target.v-y);val dy=-s*(target.u-x)+c*(target.v-y)
    if(id==4) {
        val center=handles(values).last().point
        return copy(u=sketchMm(x+target.u-center.u),v=sketchMm(y+target.v-center.v))
    }
    if(shape==SketchShape.CIRCLE) {
        val radius=hypot(target.u-x,target.v-y)
        return if(radius<1e-6) null else copy(width=sketchMm(radius))
    }
    val h=ParameterEvaluator.expression(height,values).value
    if(centered && shape==SketchShape.RECTANGLE) {
        if(abs(dx)<1e-6 || abs(dy)<1e-6) return null
        return copy(width=sketchMm(2*abs(dx)),height=sketchMm(2*abs(dy)))
    }
    if(shape==SketchShape.TRIANGLE && id==2) return if(dy<1e-6) null else copy(height=sketchMm(dy))
    val left=id==0 || id==3;val bottom=id==0 || id==1
    val lowU=if(left) dx else 0.0;val highU=if(left) w else dx
    val lowV=if(bottom) dy else 0.0;val highV=if(bottom) h else dy
    if(highU-lowU<1e-6 || highV-lowV<1e-6) return null
    return copy(u=sketchMm(x+c*lowU-s*lowV),v=sketchMm(y+s*lowU+c*lowV),width=sketchMm(highU-lowU),height=sketchMm(highV-lowV))
}
data class SketchGuide(val from: SketchPoint,val to: SketchPoint)
data class SketchSnap(val point: SketchPoint,val label: String?,val guides: List<SketchGuide> = emptyList())
/** Construction inference only: no constraints or expression changes are stored. */
fun snapSketchPoint(point: SketchPoint,center: SketchPoint?,tolerance: Double,grid: Boolean,
    edges: List<Pair<SketchPoint,SketchPoint>> = emptyList()): SketchSnap {
    require(tolerance.isFinite() && tolerance>=0)
    val targets=listOf(SketchPoint(0.0,0.0) to "Ursprung")+listOfNotNull(center?.let { it to "Flächenmitte" })+
        edges.flatMap { listOf(it.first to "Ecke",it.second to "Ecke") }.distinct()
    val nearest=targets.minByOrNull { hypot(point.u-it.first.u,point.v-it.first.v) }
    if(nearest!=null && hypot(point.u-nearest.first.u,point.v-nearest.first.v)<=tolerance) return SketchSnap(nearest.first,nearest.second)
    val edge=edges.mapNotNull { (a,b) ->
        val dx=b.u-a.u;val dy=b.v-a.v;val length=dx*dx+dy*dy
        if(length<1e-20) null else {
            val t=(((point.u-a.u)*dx+(point.v-a.v)*dy)/length).coerceIn(0.0,1.0)
            val projected=SketchPoint(a.u+t*dx,a.v+t*dy)
            Triple(projected,hypot(point.u-projected.u,point.v-projected.v),SketchGuide(a,b))
        }
    }.minByOrNull { it.second }
    if(edge!=null && edge.second<=tolerance) return SketchSnap(edge.first,"Kante",listOf(edge.third))
    val axes=listOfNotNull(center?.let { it to "Mittellinie" })+listOf(SketchPoint(0.0,0.0) to "Ursprungsachse")
    val u=axes.filter { abs(point.u-it.first.u)<=tolerance }.minByOrNull { abs(point.u-it.first.u) }
    val v=axes.filter { abs(point.v-it.first.v)<=tolerance }.minByOrNull { abs(point.v-it.first.v) }
    val result=SketchPoint(u?.first?.u ?: if(grid) round(point.u) else point.u,
        v?.first?.v ?: if(grid) round(point.v) else point.v)
    val guides=listOfNotNull(u?.let { SketchGuide(it.first,SketchPoint(it.first.u,result.v)) },v?.let { SketchGuide(it.first,SketchPoint(result.u,it.first.v)) })
    val label=listOfNotNull(u?.let { it.second+" U" },v?.let { it.second+" V" }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    return SketchSnap(result,label,guides)
}

/** Extract real boundary edges; internal triangulation diagonals are excluded. */
fun sketchBoundary(patch: List<Vec3>,frame: SketchFrame): List<Pair<SketchPoint,SketchPoint>> {
    data class Key(val a: Vec3,val b: Vec3)
    val edges=mutableMapOf<Key,Int>()
    val order=compareBy<Vec3> { it.x }.thenBy { it.y }.thenBy { it.z }
    for(triangle in patch.chunked(3).filter { it.size==3 }) for(i in 0..2) {
        val a=triangle[i];val b=triangle[(i+1)%3]
        val key=if(order.compare(a,b)<=0) Key(a,b) else Key(b,a)
        edges[key]=(edges[key] ?: 0)+1
    }
    return edges.filterValues { it==1 }.keys.map { frame.project(it.a) to frame.project(it.b) }
}

/** Switch rectangle anchoring without making an existing contour jump. */
fun Sketch.withCenterAnchor(target: Boolean,values: Map<String,Quantity>): Sketch {
    if(shape!=SketchShape.RECTANGLE) return copy(shape=SketchShape.RECTANGLE,centered=target)
    if(centered==target) return this
    val x=ParameterEvaluator.expression(u,values).value;val y=ParameterEvaluator.expression(v,values).value
    val w=ParameterEvaluator.expression(width,values).value;val h=ParameterEvaluator.expression(height,values).value
    val angle=rotation(values);val sign=if(target) 1 else -1
    return copy(u=sketchMm(x+sign*(cos(angle)*w-sin(angle)*h)/2),v=sketchMm(y+sign*(sin(angle)*w+cos(angle)*h)/2),centered=target)
}
