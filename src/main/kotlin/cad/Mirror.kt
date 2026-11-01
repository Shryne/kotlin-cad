package cad

import kotlin.math.abs

/** The offset is measured along the positive normal coordinate: Z for XY, Y for XZ, X for YZ. */
fun SketchPlane.reflect(point: Vec3, offset: Double = 0.0) = when(this) {
    SketchPlane.XY -> point.copy(z=2*offset-point.z)
    SketchPlane.XZ -> point.copy(y=2*offset-point.y)
    SketchPlane.YZ -> point.copy(x=2*offset-point.x)
}
fun Sketch.mirrorDistance(values: Map<String, Quantity>): Double {
    val value=ParameterEvaluator.expression(mirrorOffset,values)
    require(value.dimension == Dimension(1) && abs(value.value) <= 1e9) { "Der Spiegelversatz benötigt eine Länge bis 1 000 000 000 mm." }
    return value.value
}
fun Sketch.profileCopies(values: Map<String, Quantity>): List<List<Vec3>> {
    if(elements.size>1) return elements.filter { it.kind==SketchElementKind.PROFILE }.flatMap { it.on(this).profileCopies(values) }
    if(elements.singleOrNull()?.kind?.let { it!=SketchElementKind.PROFILE }==true) return emptyList()
    val original=points(values)
    val plane=mirror ?: return listOf(original)
    val distance=mirrorDistance(values)
    return listOf(original,original.map { plane.reflect(it,distance) })
}
fun MeshData.mirrored(plane: SketchPlane, offset: Double): MeshData {
    require(offset.isFinite())
    val faces=indices.copyOf()
    // Reflection changes handedness. Reverse each face to retain outward surface normals.
    for (i in faces.indices step 3) { val b=faces[i+1];faces[i+1]=faces[i+2];faces[i+2]=b }
    return MeshData("$name · Spiegelung",vertices.map { plane.reflect(it,offset) },faces)
}
