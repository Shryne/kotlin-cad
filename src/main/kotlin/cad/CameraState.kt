package cad

import kotlin.math.exp

class CameraState {
    var yaw = -35.0
    var pitch = 25.0
    var panX = 0.0
    var panY = 0.0
    var zoom = 1.0
    fun orbit(dx: Double, dy: Double) {
        yaw = (yaw + dx * 0.45) % 360
        pitch = (pitch - dy * 0.45).coerceIn(-89.0, 89.0)
    }
    fun magnify(factor: Double) {
        if (factor.isFinite() && factor > 0) zoom = (zoom * factor).coerceIn(0.05, 40.0)
    }
    fun scrollZoom(delta: Double) = magnify(exp(delta.coerceIn(-100.0, 100.0) * 0.012))
    fun reset() {
        yaw = -35.0; pitch = 25.0; panX = 0.0; panY = 0.0; zoom = 1.0
    }
}

/** Rotate CAD's (x,y,z) -> JavaFX's (x,-z,y) normal toward the camera (-Z).
 * The world transform is Rx(pitch) * Ry(yaw); pan and magnification are retained.
 */
fun CameraPose.normalTo(frame: SketchFrame): CameraPose {
    val n = frame.normal
    val horizontal = kotlin.math.hypot(n.x, n.y)
    val yaw = if (horizontal < 1e-12) 0.0 else Math.toDegrees(kotlin.math.atan2(n.x, -n.y))
    val pitch = Math.toDegrees(kotlin.math.atan2(n.z, horizontal))
    return copy(yaw = yaw, pitch = pitch)
}
