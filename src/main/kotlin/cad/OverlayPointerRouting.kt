package cad

import java.awt.event.MouseEvent

/** Capture the gesture at press time; hover must also end when leaving a glass surface. */
internal class OverlayPointerRouting {
    data class Delivery(val overlay: Boolean,val exitOverlay: Boolean)
    private var gesture: Boolean?=null
    private var lastGesture=false
    private var hovering=false
    fun delivery(id: Int,inside: Boolean): Delivery {
        val wasHovering=hovering
        if(id==MouseEvent.MOUSE_PRESSED) { gesture=inside;lastGesture=inside }
        val overlay=when(id) {
            MouseEvent.MOUSE_DRAGGED,MouseEvent.MOUSE_RELEASED -> gesture ?: inside
            MouseEvent.MOUSE_CLICKED -> lastGesture
            MouseEvent.MOUSE_EXITED -> wasHovering || gesture==true
            else -> inside
        }
        val exit=wasHovering && !inside && (id==MouseEvent.MOUSE_MOVED || id==MouseEvent.MOUSE_RELEASED)
        hovering=overlay && inside && id!=MouseEvent.MOUSE_EXITED
        if(id==MouseEvent.MOUSE_RELEASED) gesture=null
        return Delivery(overlay,exit)
    }
}
