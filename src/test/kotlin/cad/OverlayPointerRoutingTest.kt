package cad

import java.awt.event.MouseEvent.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class OverlayPointerRoutingTest {
    @Test fun leavingGlassEndsHoverWithoutTakingTheModelMovement() {
        val router=OverlayPointerRouting()
        assertTrue(router.delivery(MOUSE_MOVED,true).overlay)
        val exit=router.delivery(MOUSE_MOVED,false)
        assertTrue(exit.exitOverlay);assertFalse(exit.overlay)
        assertFalse(router.delivery(MOUSE_MOVED,false).exitOverlay)
        assertTrue(router.delivery(MOUSE_MOVED,true).overlay)
    }
    @Test fun modelDragRemainsWithModelAcrossSidebarAndRelease() {
        val router=OverlayPointerRouting()
        assertFalse(router.delivery(MOUSE_PRESSED,false).overlay)
        assertFalse(router.delivery(MOUSE_DRAGGED,true).overlay)
        assertFalse(router.delivery(MOUSE_RELEASED,true).overlay)
        assertFalse(router.delivery(MOUSE_CLICKED,true).overlay)
    }
    @Test fun controlDragAndReleaseDoNotSelectTheModelOutsideTheControl() {
        val router=OverlayPointerRouting()
        assertTrue(router.delivery(MOUSE_PRESSED,true).overlay)
        assertTrue(router.delivery(MOUSE_DRAGGED,false).overlay)
        assertTrue(router.delivery(MOUSE_RELEASED,false).overlay)
        assertTrue(router.delivery(MOUSE_CLICKED,false).overlay)
        assertFalse(router.delivery(MOUSE_PRESSED,false).overlay)
    }
    @Test fun leavingTheWindowClearsControlHover() {
        val router=OverlayPointerRouting()
        router.delivery(MOUSE_MOVED,true)
        assertTrue(router.delivery(MOUSE_EXITED,false).overlay)
        assertFalse(router.delivery(MOUSE_EXITED,false).overlay)
    }
}
