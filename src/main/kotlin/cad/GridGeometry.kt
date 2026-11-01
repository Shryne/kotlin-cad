package cad

/** Boxes with the same material share one draw call instead of one scene node per line. */
internal data class GridBox(val x: Double, val y: Double, val z: Double, val width: Double, val height: Double, val depth: Double)
internal data class GridGeometry(val points: FloatArray, val faces: IntArray)
internal fun gridGeometry(boxes: List<GridBox>): GridGeometry {
    val points=FloatArray(boxes.size*24)
    val faces=IntArray(boxes.size*72)
    val triangles=intArrayOf(0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5)
    boxes.forEachIndexed { i,b ->
        val corners=arrayOf(intArrayOf(-1,-1,-1),intArrayOf(1,-1,-1),intArrayOf(1,1,-1),intArrayOf(-1,1,-1),intArrayOf(-1,-1,1),intArrayOf(1,-1,1),intArrayOf(1,1,1),intArrayOf(-1,1,1))
        corners.forEachIndexed { j,c ->
            points[i*24+j*3]=(b.x+c[0]*b.width/2).toFloat()
            points[i*24+j*3+1]=(b.y+c[1]*b.height/2).toFloat()
            points[i*24+j*3+2]=(b.z+c[2]*b.depth/2).toFloat()
        }
        triangles.forEachIndexed { j,index -> faces[i*72+j*2]=i*8+index }
    }
    return GridGeometry(points,faces)
}
