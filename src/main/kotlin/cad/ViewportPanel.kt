package cad

import javafx.embed.swing.JFXPanel
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/** AWT still targets the full-window Swing view underneath Compose's transparent overlays. */
internal class ViewportPanel: JFXPanel() {
    var routeOverlayEvent: (MouseEvent)->Boolean = { false }
    override fun processMouseEvent(event: MouseEvent) { if(!routeOverlayEvent(event)) super.processMouseEvent(event) }
    override fun processMouseMotionEvent(event: MouseEvent) { if(!routeOverlayEvent(event)) super.processMouseMotionEvent(event) }
    override fun processMouseWheelEvent(event: MouseWheelEvent) { if(!routeOverlayEvent(event)) super.processMouseWheelEvent(event) }
}
