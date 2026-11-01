@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cad

import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path as DrawPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import javafx.application.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.Timer

private val Chrome get()=if(AppAppearance.dark) Color(0xff202630) else Color(0xffedf2f8)
private val SidebarColor = Color.Transparent
private val Panel get()=if(AppAppearance.dark) Color(0xff2d3542) else Color.White
private val Line get()=if(AppAppearance.dark) Color(0xff485568) else Color(0xff606d80)
private val Ink get()=if(AppAppearance.dark) Color(0xffedf2fa) else Color(0xff243247)
private val Muted get()=if(AppAppearance.dark) Color(0xffa5b3c6) else Color(0xff465468)
private val Accent get()=if(AppAppearance.dark) Color(0xff52a8ff) else Color(0xff0051a6)
private val CanvasColor get()=if(AppAppearance.dark) Color(0xff1b2028) else Color(0xfff8fafc)
private enum class Glyph { Copy, Check, Target, CenterRectangle, Rectangle, Circle, Triangle, Line, Polyline, Arc, Extrude, Sidebar, Cube, Open, Save, Undo, Redo, Parameters, Plus, Delete, Fit, Wire, Grid, Eye, Hidden, Chat, Info, Help, Chevron, Close }

private var controlSmokeEnabled=false
private val controlValues=java.util.concurrent.ConcurrentHashMap<String,String>()
private val controlTargets=java.util.concurrent.ConcurrentHashMap<String,java.awt.Rectangle>()
@Composable private fun Modifier.controlTarget(label: String): Modifier {
    if(!controlSmokeEnabled) return this
    val density=LocalDensity.current.density
    DisposableEffect(label) { onDispose { controlTargets.remove(label) } }
    return onGloballyPositioned {
        val r=it.boundsInWindow()
        controlTargets[label]=java.awt.Rectangle((r.left/density).toInt(),(r.top/density).toInt(),(r.width/density).toInt(),(r.height/density).toInt())
    }
}

fun main(args: Array<String>) {
    controlSmokeEnabled="--assistant-signedout-smoke" in args || "--assistant-smoke" in args || "--assistant-mesh-smoke" in args || "--body-visibility-smoke" in args || "--body-position-smoke" in args || "--sketch-actions-smoke" in args || "--controls-smoke" in args || "--keyboard-smoke" in args
    if (args.firstOrNull() == "--version") { println(AppBuild.description); return }
    if(args.firstOrNull()=="--kernel-check") {
        NativeKernel().use { kernel -> println(kernel.request("self_test").get(40,java.util.concurrent.TimeUnit.SECONDS)) };return
    }
    if (args.firstOrNull() == "--mcp") { runMcpAdapter(args.drop(1)); return }
    if(args.firstOrNull()=="--appearance") {
        val mode=when(args.getOrNull(1)?.lowercase()) { "dark" -> AppearanceMode.DARK;"light" -> AppearanceMode.LIGHT;else -> error("Appearance: dark or light") }
        AppAppearance.select(mode);println("Erscheinungsbild: ${mode.label}");return
    }
    if("--smoke-test" in args) Thread.setDefaultUncaughtExceptionHandler { _,error -> error.printStackTrace();kotlin.system.exitProcess(1) }
    System.setProperty("apple.awt.application.name", "Kotlin CAD")
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("compose.interop.blending", "true")
    if("--dark-mode" in args) AppAppearance.preview(AppearanceMode.DARK)
    if("--light-mode" in args) AppAppearance.preview(AppearanceMode.LIGHT)
    application {
        val controller = remember { ViewerController() }
        val drawnForSmoke = remember { java.util.concurrent.atomic.AtomicReference<Sketch?>() }
        val windowState = rememberWindowState(width = 1360.dp, height = 860.dp)
        fun quit() { controller.close(); Platform.exit(); exitApplication() }
        Window(
            onCloseRequest = { controller.requestClose(next = { quit() }) },
            title = "${if (controller.dirty) "• " else ""}${controller.title}",
            state = windowState
        ) {
            MacMenus(controller)
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(980, 620)
                if (System.getProperty("os.name").startsWith("Mac")) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
                controller.attach(window)
                onDispose { }
            }
            val systemDark=isSystemInDarkTheme()
            SideEffect { AppAppearance.migrateSystem(systemDark) }
            val dark=AppAppearance.dark
            LaunchedEffect(dark) { controller.viewport.setDarkMode(dark) }
            MaterialTheme(colors = if(dark) darkColors(primary=Accent,background=Chrome,surface=Panel,onPrimary=Color.White,onBackground=Ink,onSurface=Ink) else lightColors(
                primary = Accent, background = Chrome, surface = Panel,
                onPrimary = Color.White, onBackground = Ink, onSurface = Ink
            ), typography = Typography(
                defaultFontFamily = FontFamily.SansSerif,
                body1 = TextStyle(fontSize = 13.sp, letterSpacing = 0.sp),
                body2 = TextStyle(fontSize = 12.sp, letterSpacing = 0.sp),
                button = TextStyle(fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium)
            )) {
                Viewer(controller) { drawnForSmoke.set(it) }
                if (controller.parameterEditor) ParameterEditor(controller)
                if (controller.renamingSketchId!=null) RenameSketch(controller)
                if (controller.extrusionRegionChooserSketchId!=null) ExtrusionRegionChooser(controller)
                if (controller.regionExtrusionDraft!=null) RegionExtrusionDialog(controller)
                if (controller.faceExtrusionDialog) FaceExtrusionDialog(controller)
                if (controller.instanceDraft!=null) InstanceDialog(controller)
                if (controller.colorEditorOpen) ObjectColorDialog(controller)
                if (controller.loftDraft!=null) LoftDialog(controller)
                if (controller.primitiveDraft!=null) PrimitiveDialog(controller)
                if (controller.about) AboutWindow(controller)
            }
            LaunchedEffect(Unit) {
                val smokeIndex = args.indexOf("--smoke-test")
                if (smokeIndex >= 0) {
                    val smokeTrace = if("--assistant-mesh-smoke" in args) Paths.get(args[smokeIndex+2]+".trace") else null
                    fun traceSmoke(message:String) { smokeTrace?.let { path -> runCatching { java.nio.file.Files.writeString(path,"$message\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND) } } }
                    traceSmoke("startup args=${args.joinToString("|")} fakeCodex=${System.getenv("KOTLIN_CAD_CODEX")} assistantHome=${System.getenv("KOTLIN_CAD_ASSISTANT_HOME")}")
                    runCatching { java.awt.Desktop.getDesktop().requestForeground(true) }
                    window.toFront();window.requestFocus()
                    controller.open(Paths.get(args[smokeIndex + 1])).thenCompose {
                        controller.viewport.smokeCheck()
                    }.whenComplete { _, failure ->
                        onUi {
                            if (failure != null) {
                                traceSmoke("startup failure=${failure.stackTraceToString()}")
                                failure.printStackTrace()
                                kotlin.system.exitProcess(1)
                            }
                            traceSmoke("model open and viewport smoke succeeded")
                            if("--assistant-signedout-smoke" in args) {
                                controller.chatVisible=true;controller.assistant.connect()
                                Timer(3000) {
                                    check(!controller.assistant.connecting && !controller.assistant.connected && !controller.assistant.signingIn) { "Unsigned account state incorrect" }
                                    check(controller.assistant.error==null) { "Real backend failed: ${controller.assistant.error}" }
                                    check(controlTargets.containsKey("Mit ChatGPT anmelden"))
                                    check(!java.nio.file.Files.exists(assistantRoot().resolve("codex/auth.json")))
                                    val output=Paths.get(args[smokeIndex+2]).toFile();output.parentFile.mkdirs()
                                    ImageIO.write(captureCadWindow(window,controller.viewport),"png",output)
                                    println("ASSISTANT_REAL_BACKEND_OK isolatedAccount=true keyringOnly=true noApiKey=true loginReady=true");quit()
                                }.apply { isRepeats=false;start() }
                                return@onUi
                            }
                            if("--assistant-smoke" in args) {
                                assistantSmoke(controller,window,Paths.get(args[smokeIndex+2]).toFile(),::quit)
                                return@onUi
                            }
                            if("--assistant-mesh-smoke" in args) {
                                traceSmoke("assistant mesh smoke started kind=${args[args.indexOf("--assistant-mesh-smoke")+1]}")
                                assistantMeshSmoke(controller,window,args[args.indexOf("--assistant-mesh-smoke")+1],Paths.get(args[smokeIndex+2]).toFile(),::quit)
                                return@onUi
                            }
                            if("--body-visibility-smoke" in args) {
                                bodyVisibilitySmoke(controller,window,::quit)
                                return@onUi
                            }
                            if("--body-position-smoke" in args) {
                                bodyPositionSmoke(controller,::quit)
                                return@onUi
                            }
                            if("--sketch-actions-smoke" in args) {
                                sketchActionsSmoke(controller,window,::quit)
                                return@onUi
                            }
                            if("--keyboard-smoke" in args) {
                                keyboardSmoke(controller,window,Paths.get(args[smokeIndex+2]).toFile(),::quit)
                                return@onUi
                            }
                            if("--controls-smoke" in args) {
                                controlsSmoke(controller,window,Paths.get(args[smokeIndex+2]).toFile(),::quit)
                                return@onUi
                            }
                            if("--separate-extrusion" in args) {
                                workflowSmoke(controller,window,Paths.get(args[smokeIndex+2]).toFile(),::quit)
                                return@onUi
                            }
                            if("--multi-region-extrusion" in args) {
                                multiRegionExtrusionSmoke(controller,window,Paths.get(args[smokeIndex+2]).toFile(),::quit)
                                return@onUi
                            }
                            if("--show-assistant" in args) { controller.chatVisible=true;controller.assistant.connect() }
                            if ("--show-parameters" in args) { controller.showParameters = true; controller.inspector = true }
                            if ("--support-face" in args) {
                                controller.editSketch();check(controller.selectingSupport && !controller.sketchEditor)
                                controller.chooseSketchPlane(SketchPlane.XZ);check(controller.sketchEditor && !controller.sketchHasProfile)
                                check(controller.sketchDraft?.plane==SketchPlane.XZ);controller.cancelSketch()
                                controller.pickSceneFace(args[args.indexOf("--support-face")+1].toInt());controller.editSketch()
                            }
                            if (("--show-sketch" in args || "--draw-sketch" in args || "--multi-sketch" in args) && !controller.sketchEditor) controller.editSketch(controller.document.sketches.firstOrNull()?.id)
                            if ("--show-editor" in args) controller.editParameter(controller.parameters.firstOrNull()?.name)
                            if("--center-rectangle" in args) { controller.sketchDraft=controller.sketchDraft?.withCenterAnchor(true,controller.parameterValues);controller.sketchCentered=true }
                            if("--glass-smoke" in args) {
                                controller.viewport.navigate(0.0,0.0,-480.0,0.0,1.0)
                                Timer(650) { clickWindow(window,205,23) }.apply { isRepeats=false;start() }
                                Timer(1050) {
                                    check(controller.inspector && controller.showParameters) { "Parameter toolbar button did not receive input" }
                                    clickWindow(window,205,23)
                                }.apply { isRepeats=false;start() }
                                Timer(1450) {
                                    check(!controller.inspector) { "Parameter toolbar button did not close the panel" }
                                    println("GLASS_INPUT_OK parameterToggle=true")
                                }.apply { isRepeats=false;start() }
                            }
                            if("--performance-smoke" in args) {
                                controller.viewport.navigationPerformance().whenComplete { metrics,error ->
                                    if(error!=null) { error.printStackTrace();kotlin.system.exitProcess(1) }
                                    println("NAVIGATION_PERFORMANCE $metrics")
                                }
                            }
                            if("--blur-bars" in args) {
                                controller.selectSketch(controller.document.sketches.first().id)
                                controller.viewport.navigate(0.0,0.0,0.0,-300.0,4.0)
                            }
                            // Let both renderers finish their first frames, then capture only this app window.
                            if ("--draw-sketch" in args) {
                                Timer(900) {
                                    val patch=controller.selectedFace?.patch ?: emptyList()
                                    Thread {
                                        try {
                                            if("--skip-trackpad" !in args) {
                                                controller.viewport.navigate(0.0,0.0,5.0,0.0,1.15).get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                val wheelBefore=controller.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    val target=controller.viewport.panel
                                                    target.dispatchEvent(java.awt.event.MouseWheelEvent(target,java.awt.event.MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,target.width/2,target.height/2,0,false,java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1))
                                                }
                                                val wheelAfter=controller.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                check(wheelAfter[6]>wheelBefore[6]) { "Two-finger movement was not inverted" }
                                                println("TRACKPAD_INVERTED_OK")
                                            }
                                            val before=controller.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                            val points=controller.viewport.drawingTestPoints(patch).get(5,java.util.concurrent.TimeUnit.SECONDS)
                                            val start=points[0];val end=points[1]
                                            fun event(id: Int,x: Int,y: Int,down: Boolean,count: Int = 1) {
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    val target=controller.viewport.panel
                                                    target.dispatchEvent(java.awt.event.MouseEvent(target,id,System.currentTimeMillis(),
                                                        if(down) java.awt.event.InputEvent.BUTTON1_DOWN_MASK else 0,x,y,count,false,
                                                        if(id==java.awt.event.MouseEvent.MOUSE_DRAGGED) 0 else 1))
                                                }
                                            }
                                            if("--support-face" in args) {
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    controller.cancelSketch()
                                                    controller.pickSceneFace(args[args.indexOf("--support-face")+1].toInt())
                                                }
                                                Thread.sleep(250)
                                                event(java.awt.event.MouseEvent.MOUSE_PRESSED,start.u.toInt(),start.v.toInt(),true)
                                                event(java.awt.event.MouseEvent.MOUSE_DRAGGED,start.u.toInt()+1,start.v.toInt()+1,true)
                                                event(java.awt.event.MouseEvent.MOUSE_RELEASED,start.u.toInt()+1,start.v.toInt()+1,false)
                                                event(java.awt.event.MouseEvent.MOUSE_CLICKED,start.u.toInt(),start.v.toInt(),false,2)
                                                Thread.sleep(200)
                                                check(before==controller.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)) { "Selecting a face or trackpad jitter moved the view" }
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    controller.editSketch()
                                                    if("--center-rectangle" in args) { controller.sketchDraft=controller.sketchDraft?.withCenterAnchor(true,controller.parameterValues);controller.sketchCentered=true }
                                                }
                                                Thread.sleep(250)
                                                check(before==controller.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)) { "Starting a sketch changed the viewport or camera" }
                                                println("VIEW_STABLE_OK selection=true jitter=true sketchEntry=true")
                                            }
                                            if("--interactive-sketch" in args) {
                                                for(point in listOf(start,end)) {
                                                    event(java.awt.event.MouseEvent.MOUSE_PRESSED,point.u.toInt(),point.v.toInt(),true)
                                                    event(java.awt.event.MouseEvent.MOUSE_RELEASED,point.u.toInt(),point.v.toInt(),false)
                                                    event(java.awt.event.MouseEvent.MOUSE_CLICKED,point.u.toInt(),point.v.toInt(),false)
                                                    Thread.sleep(180)
                                                }
                                                val handles=controller.viewport.interactionResizePoints().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                val original=java.util.concurrent.atomic.AtomicReference<String>()
                                                val centerBefore=java.util.concurrent.atomic.AtomicReference<Pair<String,String>>()
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    original.set(controller.sketchDraft!!.width)
                                                    if("--center-rectangle" in args) {
                                                        check(controller.sketchDraft!!.centered)
                                                        centerBefore.set(controller.sketchDraft!!.u to controller.sketchDraft!!.v)
                                                    }
                                                }
                                                event(java.awt.event.MouseEvent.MOUSE_PRESSED,handles[0].u.toInt(),handles[0].v.toInt(),true)
                                                for(step in 1..12) {
                                                    event(java.awt.event.MouseEvent.MOUSE_DRAGGED,(handles[0].u+(handles[1].u-handles[0].u)*step/12).toInt(),(handles[0].v+(handles[1].v-handles[0].v)*step/12).toInt(),true)
                                                    Thread.sleep(30)
                                                }
                                                event(java.awt.event.MouseEvent.MOUSE_RELEASED,handles[1].u.toInt(),handles[1].v.toInt(),false)
                                                Thread.sleep(250)
                                                javax.swing.SwingUtilities.invokeAndWait { check(controller.sketchDraft!!.width!=original.get()) { "Dragging the corner did not resize the profile" } }
                                                controller.viewport.interactionDimensionCheck().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                if("--center-rectangle" in args) {
                                                    javax.swing.SwingUtilities.invokeAndWait { check((controller.sketchDraft!!.u to controller.sketchDraft!!.v)==centerBefore.get()) }
                                                    println("CENTER_RECTANGLE_OK create=true resize=true inlineDimensions=true centerFixed=true")
                                                }
                                                println("DIRECT_SKETCH_OK twoClick=true resize=true inlineDimension=true invalidDimension=true planeGrid=true")
                                            } else {
                                                event(java.awt.event.MouseEvent.MOUSE_PRESSED,start.u.toInt(),start.v.toInt(),true)
                                                for(step in 1..20) {
                                                    event(java.awt.event.MouseEvent.MOUSE_DRAGGED,(start.u+(end.u-start.u)*step/20).toInt(),(start.v+(end.v-start.v)*step/20).toInt(),true)
                                                    Thread.sleep(20)
                                                }
                                                event(java.awt.event.MouseEvent.MOUSE_RELEASED,end.u.toInt(),end.v.toInt(),false)
                                            }
                                            Thread.sleep(150)
                                            check(controller.viewport.drawingVisibility().get(5,java.util.concurrent.TimeUnit.SECONDS)) { "The solid or sketch preview disappeared" }
                                            if("--multi-sketch" in args) {
                                                javax.swing.SwingUtilities.invokeAndWait { controller.selectSketchTool(SketchDrawingTool.CIRCLE) }
                                                Thread.sleep(350)
                                                // Keep the second profile away from the first profile's inline dimension controls.
                                                val circleStart=SketchPoint(start.u+120,start.v-55);val circleEnd=SketchPoint(end.u+120,end.v-55)
                                                event(java.awt.event.MouseEvent.MOUSE_PRESSED,circleStart.u.toInt(),circleStart.v.toInt(),true)
                                                for(step in 1..12) event(java.awt.event.MouseEvent.MOUSE_DRAGGED,(circleStart.u+(circleEnd.u-circleStart.u)*step/12).toInt(),(circleStart.v+(circleEnd.v-circleStart.v)*step/12).toInt(),true)
                                                event(java.awt.event.MouseEvent.MOUSE_RELEASED,circleEnd.u.toInt(),circleEnd.v.toInt(),false)
                                                Thread.sleep(350)
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    check(controller.sketchDraft!!.elements.count { it.kind==SketchElementKind.PROFILE }==2) { "A second profile did not join the open sketch: ${controller.sketchDraft!!.elements.map { it.kind to it.shape }}" }
                                                    controller.selectSketchTool(SketchDrawingTool.LINE)
                                                }
                                                Thread.sleep(180)
                                                for(point in listOf(start,end)) {
                                                    event(java.awt.event.MouseEvent.MOUSE_PRESSED,point.u.toInt(),point.v.toInt(),true)
                                                    event(java.awt.event.MouseEvent.MOUSE_RELEASED,point.u.toInt(),point.v.toInt(),false)
                                                    event(java.awt.event.MouseEvent.MOUSE_CLICKED,point.u.toInt(),point.v.toInt(),false)
                                                    Thread.sleep(100)
                                                }
                                                val lineRef=java.util.concurrent.atomic.AtomicReference<SketchElement>()
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    lineRef.set(controller.sketchDraft!!.elements.last().also { check(it.kind==SketchElementKind.LINE) })
                                                    controller.selectSketchElement(lineRef.get().id)
                                                    val old=lineRef.get()
                                                    val x=ParameterEvaluator.expression(old.vertices.last().u,controller.parameterValues).value
                                                    controller.updateSketchElement(old.id) { element -> element.copy(vertices=element.vertices.mapIndexed { index,vertex -> if(index==element.vertices.lastIndex) vertex.copy(u=sketchLength(x+1)) else vertex }) }
                                                    check(controller.sketchDraft!!.elements.last().vertices.last().u==sketchLength(x+1)) { "Selected line did not update" }
                                                    controller.addSketchConstraint(SketchConstraintType.HORIZONTAL,listOf(old.id),listOf(0))
                                                }
                                                Thread.sleep(350)
                                                val handles=controller.viewport.interactionSketchPointHandles().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                val from=handles.last();val to=SketchPoint(from.u+22,from.v+18)
                                                event(java.awt.event.MouseEvent.MOUSE_PRESSED,from.u.toInt(),from.v.toInt(),true)
                                                for(step in 1..8) {
                                                    event(java.awt.event.MouseEvent.MOUSE_DRAGGED,(from.u+(to.u-from.u)*step/8).toInt(),(from.v+(to.v-from.v)*step/8).toInt(),true)
                                                    Thread.sleep(20)
                                                }
                                                event(java.awt.event.MouseEvent.MOUSE_RELEASED,to.u.toInt(),to.v.toInt(),false)
                                                Thread.sleep(250)
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    val movedLine=controller.sketchDraft!!.elements.last()
                                                    val y0=ParameterEvaluator.expression(movedLine.vertices[0].v,controller.parameterValues).value
                                                    val y1=ParameterEvaluator.expression(movedLine.vertices[1].v,controller.parameterValues).value
                                                    check(kotlin.math.abs(y0-y1)<1e-5) { "The horizontal constraint was lost while moving a point" }
                                                    controller.removeSketchElement(lineRef.get().id)
                                                    check(controller.sketchDraft!!.elements.size==2) { "Selected line was not removed" }
                                                }
                                                val pendingStart=SketchPoint(start.u+120,start.v-55)
                                                event(java.awt.event.MouseEvent.MOUSE_PRESSED,pendingStart.u.toInt(),pendingStart.v.toInt(),true)
                                                event(java.awt.event.MouseEvent.MOUSE_RELEASED,pendingStart.u.toInt(),pendingStart.v.toInt(),false)
                                                event(java.awt.event.MouseEvent.MOUSE_CLICKED,pendingStart.u.toInt(),pendingStart.v.toInt(),false)
                                                check(controller.viewport.pendingDrawing().get(5,java.util.concurrent.TimeUnit.SECONDS)) { "Incomplete line did not retain its first point" }
                                                check(controller.viewport.cancelPendingDrawing().get(5,java.util.concurrent.TimeUnit.SECONDS))
                                                check(!controller.viewport.pendingDrawing().get(5,java.util.concurrent.TimeUnit.SECONDS))
                                                controller.finishSketch().get(8,java.util.concurrent.TimeUnit.SECONDS)
                                                check(controller.document.sketches.single().elements.size==2) { "Confirmed elements did not persist in one sketch" }
                                                println("MULTI_SKETCH_OK profiles=2 lineEdited=true constraintPreservedOnMove=true lineDeleted=true pendingCancel=true sameSketch=true")
                                            }
                                            println("IN_SCENE_SKETCH_OK solid=true contour=true")
                                            if("--preview-extrusion" in args) {
                                                val documentBefore=java.util.concurrent.atomic.AtomicReference<DocumentState>()
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    documentBefore.set(controller.document)
                                                    controller.updateSketchDraft(controller.sketchDraft!!.copy(depth="12 mm"))
                                                }
                                                fun awaitPreview(count: Int,error: Boolean=false,previous: List<Vec3>?=null) {
                                                    val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                                                    while(System.nanoTime()<deadline) {
                                                        val uiReady=java.util.concurrent.atomic.AtomicBoolean()
                                                        javax.swing.SwingUtilities.invokeAndWait { uiReady.set(controller.sketchPreviewBodies==count && (controller.sketchPreviewError!=null)==error) }
                                                        val rendered=controller.viewport.extrusionPreviewState().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                        val sizeChanged=previous==null || previous!=controller.viewport.previewExtents().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                        if(uiReady.get() && rendered.first==count && sizeChanged) return
                                                        Thread.sleep(40)
                                                    }
                                                    error("Preview did not reach expected rendered state: bodies=$count error=$error")
                                                }
                                                awaitPreview(1)
                                                val first=controller.viewport.previewExtents().get(5,java.util.concurrent.TimeUnit.SECONDS)
                                                javax.swing.SwingUtilities.invokeAndWait { controller.updateSketchDraft(controller.sketchDraft!!.copy(depth="24 mm")) }
                                                awaitPreview(1,previous=first)
                                                javax.swing.SwingUtilities.invokeAndWait { controller.updateSketchDraft(controller.sketchDraft!!.copy(depth="12 deg")) }
                                                awaitPreview(0,true)
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    check(controller.document==documentBefore.get())
                                                    controller.updateSketchDraft(controller.sketchDraft!!.copy(depth="12 mm"))
                                                }
                                                awaitPreview(1)
                                                check(before==controller.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)) { "Preview moved the camera" }
                                                val restoredDraft=java.util.concurrent.atomic.AtomicReference<Sketch>()
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    restoredDraft.set(controller.sketchDraft!!)
                                                    controller.cancelSketch()
                                                    check(controller.document==documentBefore.get())
                                                    check(!controller.canUndo && !controller.canRedo)
                                                }
                                                awaitPreview(0)
                                                check(!controller.viewport.extrusionPreviewState().get(5,java.util.concurrent.TimeUnit.SECONDS).second) { "Cancel did not restore the original scene" }
                                                javax.swing.SwingUtilities.invokeAndWait {
                                                    controller.pickSceneFace(args[args.indexOf("--support-face")+1].toInt());controller.editSketch()
                                                    controller.updateSketchDraft(restoredDraft.get().copy(id=controller.editingSketchId!!))
                                                }
                                                awaitPreview(1)
                                                println("EXTRUSION_PREVIEW_OK depth=true invalidInput=true documentUnchanged=true cameraStable=true cancelRestoresScene=true historyUnchanged=true")
                                            }
                                        } catch (e: Throwable) { e.printStackTrace(); kotlin.system.exitProcess(1) }
                                    }.start()
                                }.apply { isRepeats=false; start() }
                            }
                            Timer(if ("--performance-smoke" in args) 6500 else if ("--preview-extrusion" in args) 12000 else if ("--draw-sketch" in args) 6000 else 1800) {
                                try {
                                    if ("--draw-sketch" in args) {
                                        check(window.ownedWindows.none { it.isShowing && it is java.awt.Dialog }) { "Sketch opened a separate dialog" }
                                        val drawn = checkNotNull(drawnForSmoke.get()) { "Drawing gesture did not update the profile" }
                                        check(drawn.width.endsWith(" mm") && drawn.height.endsWith(" mm"))
                                        println("DRAWING_OK width=${drawn.width} height=${drawn.height}")
                                    }
                                    if("--blur-bars" in args) controller.viewport.controlGlassSmokeCheck().get(10,java.util.concurrent.TimeUnit.SECONDS)
                                    if("--glass-smoke" in args) {
                                        controller.viewport.glassSmokeCheck(Paths.get(args[smokeIndex+2]).toFile()).get(10,java.util.concurrent.TimeUnit.SECONDS)
                                    }
                                    val image = captureCadWindow(window,controller.viewport)
                                    val output = Paths.get(args[smokeIndex + 2]).toFile()
                                    output.parentFile?.mkdirs()
                                    check(ImageIO.write(image, "png", output))
                                    println("SMOKE_OK compose=true triangles=${controller.sceneModel!!.triangleCount} nativePinch=${controller.viewport.nativePinchInstalled} snapshot=${output.absolutePath}")
                                    if ("--support-face" in args && "--draw-sketch" in args && "--multi-sketch" !in args) {
                                        val before=controller.document
                                        val drawn=checkNotNull(controller.sketchDraft)
                                        checkNotNull(drawn.frame);checkNotNull(drawn.support)
                                        check(drawn.points(controller.parameterValues).all { kotlin.math.abs((it-drawn.frame.origin).dot(drawn.frame.normal))<1e-6 })
                                        controller.cancelSketch();check(controller.document==before)
                                        controller.pickSceneFace(args[args.indexOf("--support-face")+1].toInt());controller.editSketch()
                                        controller.updateSketchDraft(drawn)
                                        controller.finishSketch().whenComplete { _, finishError -> onUi {
                                            try {
                                                check(finishError==null);check(!controller.sketchEditor)
                                                check(controller.document.sketches.size==before.sketches.size+1)
                                                controller.undo().whenComplete { _, undoError -> onUi {
                                                    try { check(undoError==null);check(controller.document==before);println("INLINE_FACE_OK cancel=true finish=true undo=true");quit() }
                                                    catch(e:Throwable) { e.printStackTrace();kotlin.system.exitProcess(1) }
                                                } }
                                            } catch(e:Throwable) { e.printStackTrace();kotlin.system.exitProcess(1) }
                                        } }
                                    } else quit()
                                } catch (e: Throwable) { e.printStackTrace(); kotlin.system.exitProcess(1) }
                            }.apply { isRepeats = false; start() }
                        }
                    }
                } else {
                    val path=args.firstOrNull { !it.startsWith("--") }
                    val opened=path?.let { controller.open(Paths.get(it)) } ?: java.util.concurrent.CompletableFuture.completedFuture(null)
                    if("--show-assistant" in args) opened.whenComplete { _,_ -> onUi { controller.chatVisible=true;controller.assistant.connect() } }
                }
            }
        }
    }
}

@Composable private fun FrameWindowScope.MacMenus(c: ViewerController) {
    MenuBar {
        Menu("Datei") {
            Item("Neues Projekt", shortcut = KeyShortcut(Key.N, meta = true), onClick = { c.newProject() })
            Item("Öffnen …", shortcut = KeyShortcut(Key.O, meta = true), onClick = { c.chooseFile() })
            Item("Speichern", enabled = !c.loading && !c.saving && !c.sketchEditor, shortcut = KeyShortcut(Key.S, meta = true), onClick = { c.saveFromDialog() })
            Item("Als STL exportieren …", enabled=c.canExport, onClick={c.exportFromDialog(MeshFormat.STL)})
            Item("Als OBJ exportieren …", enabled=c.canExport, onClick={c.exportFromDialog(MeshFormat.OBJ)})
            Item("Speichern unter …", shortcut = KeyShortcut(Key.S, meta = true, shift = true), onClick = { c.saveFromDialog(true) })
        }
        Menu("Bearbeiten") {
            Item("Rückgängig", enabled = c.canUndo, shortcut = KeyShortcut(Key.Z, meta = true), onClick = { c.undo() })
            Item("Wiederholen", enabled = c.canRedo, shortcut = KeyShortcut(Key.Z, meta = true, shift = true), onClick = { c.redo() })
            Separator()
            Item("Skizze hinzufügen …", onClick = { c.editSketch() })
            Item("Parameter hinzufügen …", enabled = !c.sketchEditor, onClick = { c.editParameter() })
        }
        Menu("Darstellung") {
            CheckboxItem("Assistent", checked=c.chatVisible,onCheckedChange={ c.chatVisible=it;if(it) c.assistant.connect() })
            CheckboxItem("Performanceanzeige",checked=c.performanceVisible,onCheckedChange={ c.performanceVisible=it })
            CheckboxItem("Seitenleiste", checked = c.sidebar, onCheckedChange = { c.sidebar = it })
            CheckboxItem("Informationen", checked = c.inspector && !c.showParameters, onCheckedChange = { c.inspector = it; c.showParameters = false; c.help = false })
            CheckboxItem("Parameter", checked = c.inspector && c.showParameters, onCheckedChange = { c.inspector = it; c.showParameters = true; c.help = false })
            Separator()
            Menu("Erscheinungsbild") {
                AppearanceMode.entries.forEach { mode -> CheckboxItem(mode.label,checked=AppAppearance.mode==mode,onCheckedChange={ AppAppearance.select(mode) }) }
            }
            Separator()
            Item("Einpassen", onClick = { c.fit() })
            CheckboxItem("Raster", checked = c.grid, onCheckedChange = { if (it != c.grid) c.toggleGrid() })
            Separator()
            for (view in listOf("Iso", "Vorne", "Oben", "Rechts", "Hinten", "Links", "Unten"))
                Item(if (view == "Iso") "Isometrisch" else view, onClick = { c.view(view) })
        }
        Menu("Hilfe") {
            Item("Über Kotlin CAD", onClick = { c.about = true })
            Item("Trackpad und Tastatur", onClick = { c.chatVisible=false;c.help = true; c.inspector = true })
        }
    }
}

@Composable private fun AboutWindow(c: ViewerController) {
    DialogWindow(onCloseRequest = { c.about = false }, title = "Über Kotlin CAD",
        state = rememberDialogState(width = 360.dp, height = 235.dp), resizable = false) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Glyph.Cube, Accent, 36)
            Spacer(Modifier.height(12.dp))
            Text("Kotlin CAD", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("Version ${AppBuild.version}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            Text("Build ${AppBuild.id}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(1f))
            MacButton("Schließen", primary = true) { c.about = false }
        }
    }
}

@Composable private fun ExtrusionRegionChooser(c:ViewerController) {
    val id=c.extrusionRegionChooserSketchId ?: return
    val sketch=c.document.evaluatedSketches().firstOrNull { it.id==id } ?: run { c.cancelExtrusionRegionChooser();return }
    val regions=runCatching { sketch.regions(c.parameterValues) }.getOrElse { c.reportError(it);c.cancelExtrusionRegionChooser();return }
    DialogWindow(onCloseRequest=c::cancelExtrusionRegionChooser,title="Extrusionsregion wählen",resizable=false,
        state=rememberDialogState(width=420.dp,height=(180+regions.size*62).coerceAtMost(620).dp)) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Welche Region soll extrudiert werden?",color=Ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Text(sketch.name,color=Muted,fontSize=12.sp)
            Text("Mit Umschalttaste mehrere Regionen markieren; sie erhalten dieselbe Tiefe.",color=Muted,fontSize=11.sp)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                regions.forEachIndexed { index,region ->
                    val holes=region.holeElementIds.size
                    val feature=c.document.effectiveFeatures().filterIsInstance<ExtrusionFeature>().firstOrNull { it.region==RegionReference(id,region.id) }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(region.id in c.extrusionChooserSelection,{ c.chooseExtrusionRegion(region.id) },colors=CheckboxDefaults.colors(checkedColor=Accent))
                        Column(Modifier.weight(1f)) {
                            Text("Region ${index+1} · ${region.id}",color=Ink,fontSize=12.sp)
                            Text(if(holes==0) "Ohne Innenkonturen" else "$holes Innenkontur${if(holes==1) "" else "en"}${feature?.let { " · Tiefe ${it.depth}" } ?: ""}",color=Muted,fontSize=10.sp)
                        }
                        if(c.extrusionChooserSelection.isEmpty()) MacButton(if(feature==null) "Extrudieren" else "Tiefe bearbeiten",primary=feature==null) {
                            runCatching { c.startExtrusion(id,region.id) }.onFailure(c::reportError)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
                MacButton("Abbrechen") { c.cancelExtrusionRegionChooser() }
                if(c.extrusionChooserSelection.isNotEmpty()) MacButton("${c.extrusionChooserSelection.size} Regionen gemeinsam extrudieren",primary=true) {
                    runCatching { c.startExtrusion(id,c.extrusionChooserSelection) }.onFailure(c::reportError)
                }
            }
        }
    }
}

@Composable private fun RegionExtrusionDialog(c:ViewerController) {
    val draft=c.regionExtrusionDraft ?: return
    DialogWindow(onCloseRequest=c::cancelRegionExtrusion,title="Skizzenregionen extrudieren",resizable=false,state=rememberDialogState(width=420.dp,height=300.dp)) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("${draft.sources.size} Regionen · getrennte Körper",color=Ink,fontSize=16.sp)
            FormField("Tiefe",draft.depth,changed=c::updateRegionDepth)
            c.regionExtrusionError?.let { Text(it,color=Accent,fontSize=12.sp) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
                MacButton("Abbrechen",action=c::cancelRegionExtrusion)
                MacButton("Vorschau übernehmen",primary=true,enabled=c.regionExtrusionError==null) { runCatching { c.finishRegionExtrusion() }.onFailure { c.regionExtrusionError=it.message } }
            }
        }
    }
}

@Composable private fun FaceExtrusionDialog(c:ViewerController) {
    DialogWindow(onCloseRequest=c::cancelFaceExtrusion,title="Flächen extrudieren",resizable=false,state=rememberDialogState(width=420.dp,height=310.dp)) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("${c.selectedFaces.size} planare Fläche${if(c.selectedFaces.size==1) "" else "n"}",color=Ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Text("Jede Fläche wird entlang ihrer eigenen Normalen als separater Körper extrudiert. Die Ausgangskörper bleiben erhalten.",color=Muted,fontSize=12.sp,lineHeight=18.sp)
            FormField("Tiefe",c.faceExtrusionDepth) { c.faceExtrusionDepth=it }
            c.faceExtrusionError?.let { Text(it,color=Color(0xffc9342c),fontSize=11.sp,lineHeight=16.sp) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
                MacButton("Abbrechen") { c.cancelFaceExtrusion() }
                MacButton("Vorschau übernehmen",primary=true,enabled=c.faceExtrusionError==null) { runCatching { c.finishFaceExtrusion() }.onFailure { c.faceExtrusionError=it.message } }
            }
        }
    }
}

@Composable private fun LoftDialog(c:ViewerController) {
    val draft=c.loftDraft ?: return
    var index by remember { mutableStateOf(0) }
    val selected=index.coerceIn(0,draft.sections.lastIndex)
    val section=draft.sections[selected]
    fun edit(next:LoftSection) { c.loftDraft=draft.copy(sections=draft.sections.mapIndexed { i,s -> if(i==selected) next else s });c.loftError=null;c.viewport.setFeaturePreview(emptyList()) }
    DialogWindow(onCloseRequest=c::cancelLoft,title="Loft bearbeiten",state=rememberDialogState(width=520.dp,height=760.dp)) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Loft aus elliptischen Querschnitten",color=Ink,fontSize=16.sp)
            Text("Geordnete Profile · Radien entlang lokaler X/Y-Achsen · Rotation X → Y → Z",color=Muted,fontSize=11.sp)
            if(!c.loftWorking) {
                FormField("Name",draft.name) { c.loftDraft=draft.copy(name=it) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MacButton(if(draft.ruled) "Geradlinig" else "Glatt") { c.loftDraft=draft.copy(ruled=!draft.ruled);c.viewport.setFeaturePreview(emptyList()) }
                    MacButton("←",enabled=selected>0) { index=selected-1 }
                    Text("${selected+1} / ${draft.sections.size}",color=Ink)
                    MacButton("→",enabled=selected<draft.sections.lastIndex) { index=selected+1 }
                }
                FormField("Mitte X",section.x) { edit(section.copy(x=it)) };FormField("Mitte Y",section.y) { edit(section.copy(y=it)) };FormField("Mitte Z",section.z) { edit(section.copy(z=it)) }
                FormField("Rotation X",section.rotationX) { edit(section.copy(rotationX=it)) };FormField("Rotation Y",section.rotationY) { edit(section.copy(rotationY=it)) };FormField("Rotation Z",section.rotationZ) { edit(section.copy(rotationZ=it)) }
                FormField("Radius X",section.radiusX) { edit(section.copy(radiusX=it)) };FormField("Radius Y",section.radiusY) { edit(section.copy(radiusY=it)) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MacButton("Hinzufügen",enabled=draft.sections.size<32) { c.loftDraft=draft.copy(sections=draft.sections+section.copy(z="(${section.z}) + 30 mm"));index=draft.sections.size }
                    MacButton("Entfernen",enabled=draft.sections.size>2) { c.loftDraft=draft.copy(sections=draft.sections.filterIndexed { i,_ -> i!=selected });index=(selected-1).coerceAtLeast(0);c.viewport.setFeaturePreview(emptyList()) }
                    MacButton("Nach vorn",enabled=selected>0) { val list=draft.sections.toMutableList();list[selected]=list[selected-1];list[selected-1]=section;c.loftDraft=draft.copy(sections=list);index=selected-1;c.viewport.setFeaturePreview(emptyList()) }
                }
            }
            c.loftError?.let { Text(it,color=Color(0xffc9342c),fontSize=12.sp) }
            if(c.loftWorking) Text("Loft wird geprüft …",color=Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MacButton("Abbrechen",enabled=!c.loftWorking) { c.cancelLoft() }
                MacButton("Vorschau",enabled=!c.loftWorking) { c.runLoftDraft(false) }
                MacButton("Übernehmen",primary=true,enabled=!c.loftWorking) { c.runLoftDraft(true) }
            }
        }
    }
}

@Composable private fun PrimitiveDialog(c:ViewerController) {
    val draft=c.primitiveDraft ?: return
    DialogWindow(onCloseRequest=c::cancelPrimitive,title=if(c.primitiveEditingExisting) "Grundkörper bearbeiten" else "Grundkörper erstellen",resizable=false,state=rememberDialogState(width=440.dp,height=490.dp)) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("3D-Grundkörper",color=Ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                PrimitiveKind.entries.forEach { kind -> MacButton(when(kind) { PrimitiveKind.BOX->"Quader";PrimitiveKind.CYLINDER->"Zylinder";PrimitiveKind.SPHERE->"Kugel" },primary=draft.kind==kind) {
                    c.updatePrimitive(draft.copy(kind=kind,name=when(kind) { PrimitiveKind.BOX->"Quader";PrimitiveKind.CYLINDER->"Zylinder";PrimitiveKind.SPHERE->"Kugel" }))
                } }
            }
            FormField("Name",draft.name) { c.updatePrimitive(draft.copy(name=it)) }
            when(draft.kind) {
                PrimitiveKind.BOX -> {
                    FormField("Breite X",draft.width) { c.updatePrimitive(draft.copy(width=it)) }
                    FormField("Tiefe Y",draft.depth) { c.updatePrimitive(draft.copy(depth=it)) }
                    FormField("Höhe Z",draft.height) { c.updatePrimitive(draft.copy(height=it)) }
                }
                PrimitiveKind.CYLINDER -> {
                    FormField("Radius",draft.radius) { c.updatePrimitive(draft.copy(radius=it)) }
                    FormField("Höhe Z",draft.height) { c.updatePrimitive(draft.copy(height=it)) }
                }
                PrimitiveKind.SPHERE -> FormField("Radius",draft.radius) { c.updatePrimitive(draft.copy(radius=it)) }
            }
            Text("Position (mm)",color=Muted,fontSize=11.sp)
            FormField("X",draft.x) { c.updatePrimitive(draft.copy(x=it)) }
            FormField("Y",draft.y) { c.updatePrimitive(draft.copy(y=it)) }
            FormField("Z",draft.z) { c.updatePrimitive(draft.copy(z=it)) }
            c.primitiveError?.let { Text(it,color=Color(0xffc9342c),fontSize=11.sp,lineHeight=16.sp) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
                MacButton("Abbrechen") { c.cancelPrimitive() }
                MacButton(if(c.primitiveEditingExisting) "Übernehmen" else "Erstellen",primary=true,enabled=c.primitiveError==null) { runCatching { c.finishPrimitive() }.onFailure { c.primitiveError=it.message } }
            }
        }
    }
}

@Composable private fun BodyPositionPanel(c:ViewerController) {
    val draft=c.bodyPositionDraft ?: return
    Column(Modifier.width(300.dp).sceneBackdrop(c,"body-position",18.dp).glassSurface(18.dp,backdrop=true).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Position des Körpers",color=Ink,fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            Text("×",color=Muted,modifier=Modifier.clickable { c.cancelBodyPosition() }.padding(horizontal=6.dp, vertical=2.dp))
        }
        Text("Ziehe die farbigen X/Y/Z-Griffe direkt am Modell oder gib eine Länge/Formel ein.",color=Muted,fontSize=11.sp,lineHeight=15.sp)
        FormField("X",draft.x) { c.updateBodyPositionDraft(draft.copy(x=it)) }
        FormField("Y",draft.y) { c.updateBodyPositionDraft(draft.copy(y=it)) }
        FormField("Z",draft.z) { c.updateBodyPositionDraft(draft.copy(z=it)) }
        Text("Ziehen setzt die jeweilige Formel sichtbar auf einen festen Wert um.",color=Muted,fontSize=10.sp,lineHeight=14.sp)
        c.bodyPositionError?.let { Text(it,color=Color(0xffc9342c),fontSize=11.sp,lineHeight=16.sp) }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
            MacButton("Abbrechen") { c.cancelBodyPosition() }
            MacButton("Übernehmen",primary=true,enabled=c.bodyPositionError==null) { runCatching { c.finishBodyPosition() }.onFailure { c.bodyPositionError=it.message } }
        }
    }
}

@Composable private fun WindowScope.Viewer(c: ViewerController, onDrawn: (Sketch) -> Unit = {}) {
    val focusManager=LocalFocusManager.current
    DisposableEffect(c,focusManager) { c.releaseInputFocus={ focusManager.clearFocus() };onDispose { c.releaseInputFocus={} } }
    val draft=if(c.sketchEditor) c.sketchDraft else null
    val previewContext=remember(c.document,c.editingSketchId,c.sketchEditor) {
        if(c.sketchEditor && c.editingSketchId!=null) c.document.prepareSketchPreview(c.editingSketchId!!,c.sceneModel) else null
    }
    val preview=remember(previewContext,draft,c.sketchHasProfile,c.extruding,c.extrusionRegionIds,c.extrusionFeatureIds) {
        runCatching {
            if(draft!=null && c.sketchHasProfile) {
                val extrusions=if(c.extruding) c.extrusionRegionIds.map { region -> ExtrusionFeature(checkNotNull(c.extrusionFeatureIds[region]),RegionReference(draft.id,region),checkNotNull(draft.depth)) } else emptyList()
                previewContext!!.bodies(draft,extrusions)
            } else emptyList()
        }
    }
    LaunchedEffect(c.sketchEditor,draft,c.sketchHasProfile,c.sketchSnap,c.sketchCentered,c.sketchTool,c.selectedSketchElementId,c.parameterValues,previewContext,preview,c.extruding,c.extrusionRegionIds,c.extrusionFeatureIds) {
        val bodies=preview.getOrDefault(emptyList())
        c.previewStatus(preview.exceptionOrNull()?.message,bodies.size)
        c.viewport.setSketchEditing(draft,c.parameterValues,c.sketchHasProfile,c.sketchSnap,
            if(preview.isSuccess) previewContext?.geometry ?: if(draft==null) c.sceneModel else null else c.sceneModel,bodies,runCatching { draft?.support?.let { c.document.supportPatch(it) }.orEmpty() }.getOrDefault(emptyList()),c.sketchCentered,c.extruding,c.sketchTool,c.selectedSketchElementId)
        if(draft!=null && c.sketchHasProfile) onDrawn(draft)
    }
    LaunchedEffect(c.performanceVisible,c.document,c.visible) {
        c.viewport.setDiagnostics(c.performanceVisible,c.document.sceneBodies().count { it.visible }) { if(c.performanceVisible) c.performance=it }
        if(!c.performanceVisible) c.performance=null
    }
    LaunchedEffect(c.regionExtrusionDraft,c.document) {
        if(c.regionExtrusionDraft!=null) runCatching { c.previewRegionExtrusion() }.onSuccess { c.regionExtrusionError=null;c.viewport.setFeaturePreview(it) }
            .onFailure { c.regionExtrusionError=it.message;c.viewport.setFeaturePreview(emptyList()) }
    }
    LaunchedEffect(c.faceExtrusionDialog,c.faceExtrusionDepth,c.selectedFaces,c.document) {
        if(c.faceExtrusionDialog) runCatching { c.previewFaceExtrusion() }.onSuccess { c.faceExtrusionError=null;c.viewport.setFeaturePreview(it) }
            .onFailure { c.faceExtrusionError=it.message ?: "Die Fläche kann nicht extrudiert werden.";c.viewport.setFeaturePreview(emptyList()) }
        else c.viewport.setFeaturePreview(emptyList())
    }
    LaunchedEffect(c.primitiveDraft,c.parameterValues) {
        if(c.primitiveDraft!=null) runCatching { c.previewPrimitive() }.onSuccess { mesh ->
            c.primitiveError=null;c.viewport.setFeaturePreview(listOfNotNull(mesh))
        }.onFailure { error -> c.primitiveError=error.message ?: "Der Körper lässt sich nicht erstellen.";c.viewport.setFeaturePreview(emptyList()) }
    }
    LaunchedEffect(c.bodyPositionDraft,c.parameterValues) {
        if(c.bodyPositionDraft!=null) { delay(16);runCatching { withContext(Dispatchers.Default) { c.previewBodyPosition() } }.onSuccess { preview ->
            c.bodyPositionError=null;c.viewport.setBodyPositionPreview(preview?.environment,preview?.body)
            val draft=checkNotNull(c.bodyPositionDraft)
            val position=preview?.body?.bounds?.center
            c.viewport.setPositionGizmo(position,if(position!=null) c::nudgeBodyPosition else null)
        }.onFailure { error -> c.bodyPositionError=error.message ?: "Die Körperposition ist ungültig.";c.viewport.clearBodyPositionPreview();c.viewport.setPositionGizmo(null) } }
        else { c.viewport.clearBodyPositionPreview();c.viewport.setPositionGizmo(null) }
    }
    Box(Modifier.fillMaxSize()) {
        // Keep the same full-window renderer under every floating control.
        SwingPanel(factory={ c.viewport.panel },background=CanvasColor,modifier=Modifier.fillMaxSize())
        if(c.bodyPositionDraft!=null) Box(Modifier.align(Alignment.TopStart).padding(start=54.dp,top=62.dp)) { BodyPositionPanel(c) }
        if(c.performanceVisible) c.performance?.let { p ->
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom=12.dp).background(Panel.copy(alpha=.95f),RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text("Viewport-Pulse: ${number(p.fps)} FPS · Ø ${number(p.meanMs)} ms · p95 ${number(p.p95Ms)} ms",color=Ink,fontSize=11.sp)
                Text("Fenster ${number(p.windowSeconds)} s · ${p.triangles} Dreiecke · ${p.bodies} Körper · JVM ${p.memoryMiB} MiB",color=Muted,fontSize=10.sp)
            }
        }
        Column(Modifier.fillMaxSize()) {
            if(c.sketchEditor) SketchModeToolbar(c) else MacToolbar(c)
            Box(Modifier.weight(1f).fillMaxWidth().padding(top=8.dp,bottom=10.dp)) {
                val leftOpen=c.sidebar || (c.inspector && c.showParameters && !c.help)
                val requestedLeft=if(c.inspector && c.showParameters && !c.sketchEditor && !c.help) 282.dp else 218.dp
                var leftContentWidth by remember { mutableStateOf(requestedLeft) }
                var leftParameters by remember { mutableStateOf(false) }
                if(leftOpen) { leftContentWidth=requestedLeft;leftParameters=c.inspector && c.showParameters && !c.help }
                val leftWidth by animateDpAsState(if(leftOpen) requestedLeft else 0.dp,tween(MotionPreferences.panelMillis))
                if(leftWidth>0.dp) Box(Modifier.align(Alignment.CenterStart).padding(start=10.dp).width(leftWidth).fillMaxHeight().clipToBounds()
                    .sceneBackdrop(c,"left",22.dp).glassSurface(22.dp,backdrop=true)) {
                    Box(Modifier.requiredWidth(leftContentWidth).fillMaxHeight()) {
                        if(c.sketchEditor) { if(c.extruding) ExtrusionProperties(c) else SketchProperties(c) }
                        else if(leftParameters) Inspector(c) else Sidebar(c)
                    }
                }
                Box(Modifier.align(Alignment.CenterStart).padding(start=if(leftWidth>0.dp) leftWidth+12.dp else 3.dp)
                    .controlTarget("Seitenleiste").size(22.dp,42.dp).sceneBackdrop(c,"sidebar-toggle",8.dp).glassSurface(8.dp,1.dp,backdrop=true,captureInput=false)
                    .clickable { if(leftOpen) { c.sidebar=false;if(c.showParameters && !c.help) c.inspector=false } else c.sidebar=true }
                    .semantics { contentDescription=if(leftOpen) "Seitenleiste ausblenden" else "Seitenleiste einblenden" },contentAlignment=Alignment.Center) {
                    Text(if(leftOpen) "‹" else "›",color=Ink,fontSize=22.sp)
                }
                Box(Modifier.align(Alignment.TopStart).padding(start=if(leftWidth>0.dp) leftWidth+42.dp else 14.dp)) { ModelActionBar(c) }
                if(c.sketchEditor) Box(Modifier.align(Alignment.TopEnd).padding(end=12.dp)) { ViewControls(c) }
                val rightOpen=c.chatVisible || (c.inspector && (!c.showParameters || c.help))
                var rightChat by remember { mutableStateOf(c.chatVisible) }
                if(rightOpen) rightChat=c.chatVisible
                val rightContentWidth=if(rightChat) 360.dp else 282.dp
                val rightWidth by animateDpAsState(if(rightOpen) rightContentWidth else 0.dp,tween(MotionPreferences.panelMillis))
                if(rightWidth>0.dp) Box(Modifier.align(Alignment.CenterEnd).padding(end=10.dp).width(rightWidth).fillMaxHeight().clipToBounds()
                    .sceneBackdrop(c,"right",22.dp).glassSurface(22.dp,backdrop=true)) {
                    Box(Modifier.requiredWidth(rightContentWidth).fillMaxHeight()) { if(rightChat) AssistantPane(c) else Inspector(c) }
                }
            }
        }
    }
}

@Composable private fun Modifier.sceneBackdrop(c: ViewerController,key: String,radius: Dp): Modifier {
    val density=LocalDensity.current.density
    DisposableEffect(c.viewport,key) { onDispose { c.viewport.setGlassRegion(key,null) } }
    return onGloballyPositioned { coordinates ->
        val r=coordinates.boundsInWindow()
        c.viewport.setGlassRegion(key,doubleArrayOf((r.left/density).toDouble(),(r.top/density).toDouble(),(r.width/density).toDouble(),(r.height/density).toDouble(),radius.value.toDouble()))
    }
}

@Composable private fun WindowScope.MacToolbar(c: ViewerController) {
    WindowDraggableArea {
        Row(Modifier.fillMaxWidth().height(46.dp).sceneBackdrop(c,"main-header",0.dp).padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Spacer(Modifier.width(80.dp))
            Spacer(Modifier.width(8.dp))
            ToolButton(icon=Glyph.Parameters,selected=c.inspector && c.showParameters,hint="Parameter") { c.inspector=!(c.inspector && c.showParameters);c.showParameters=true;c.help=false }
            Spacer(Modifier.width(16.dp))
            Text(c.title.substringBeforeLast('.'),color=Ink,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=390.dp))
            if(c.dirty) Text(" •",color=Muted,fontSize=13.sp)
            Spacer(Modifier.weight(1f))
            SaveStatus(c)
            Spacer(Modifier.width(8.dp))
            ToolButton(icon=Glyph.Chat,selected=c.chatVisible,hint="Assistent") { c.chatVisible=!c.chatVisible;if(c.chatVisible) c.assistant.connect() }
            Spacer(Modifier.width(8.dp))
            ViewControls(c)
        }
    }
}
@Composable private fun ToolbarDivider() {
    Spacer(Modifier.width(9.dp)); Box(Modifier.width(1.dp).height(20.dp).background(Line)); Spacer(Modifier.width(9.dp))
}
@Composable private fun Segment(selected: Boolean, hint: String, icon: Glyph, action: () -> Unit) {
    Box(Modifier.size(34.dp, 27.dp).then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(6.dp)) else Modifier)
        .clip(RoundedCornerShape(6.dp)).background(if (selected) Panel else Color.Transparent)
        .clickable(onClick = action).semantics { contentDescription = hint }, contentAlignment = Alignment.Center) {
        Icon(icon, if (selected) Ink else Muted, 16)
    }
}

@Composable private fun Sidebar(c: ViewerController) {
    Column(Modifier.width(218.dp).fillMaxHeight().background(SidebarColor).padding(horizontal=12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(16.dp))
        Text("INHALT",color=Muted,fontSize=10.sp,fontWeight=FontWeight.SemiBold,letterSpacing=.9.sp,modifier=Modifier.padding(start=9.dp,bottom=10.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SidebarRow("Skizzen",Glyph.Grid,false,if(c.sketchesExpanded) "⌄" else "›") { c.sketchesExpanded=!c.sketchesExpanded } }
            ToolButton(icon=Glyph.Plus,small=true,hint="Neue Skizze") { c.editSketch() }
        }
        AnimatedVisibility(c.sketchesExpanded,enter=expandVertically(tween(MotionPreferences.panelMillis)),exit=shrinkVertically(tween(MotionPreferences.panelMillis))) { Column { c.document.sketches.forEachIndexed { index,sketch ->
            key(sketch.id) {
                val menu=remember { ContextMenuState() }
                DisposableEffect(menu.status) {
                    c.viewport.captureContextMenu(sketch.id,menu.status is ContextMenuState.Status.Open)
                    onDispose { c.viewport.captureContextMenu(sketch.id,false) }
                }
                CompositionLocalProvider(LocalContextMenuRepresentation provides if(AppAppearance.dark) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation) {
                ContextMenuArea(state=menu,items={ listOf(
                ContextMenuItem("Skizze bearbeiten") { c.editSketch(sketch.id) },
                ContextMenuItem("Extrudieren …") { c.startExtrusion(sketch.id) },
                ContextMenuItem("Umbenennen …") { c.renamingSketchId=sketch.id },
                ContextMenuItem("Duplizieren") { runCatching { c.duplicateSketch(sketch.id) }.onFailure(c::reportError) },
                ContextMenuItem(if(sketch.visible) "Ausblenden" else "Einblenden") { c.setSketchVisibility(sketch.id,!sketch.visible) }
            ) }) { TreeEntry(sketch.name,c.selectedSketchId==sketch.id,sketch.visible,last=index==c.document.sketches.lastIndex,
                onSelect={ c.selectSketch(sketch.id) },onEdit={ c.editSketch(sketch.id) },
                onVisibility={ c.setSketchVisibility(sketch.id,!sketch.visible) }) } } }
        }

        } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SidebarRow("Objekte",Glyph.Cube,false,if(c.objectsExpanded) "⌄" else "›") { c.objectsExpanded=!c.objectsExpanded } }
            ToolButton(icon=Glyph.Plus,small=true,hint="Grundkörper erstellen") { c.startPrimitive(PrimitiveKind.BOX) }
            ToolButton(icon=if(c.visible) Glyph.Eye else Glyph.Hidden,small=true,hint="Alle Objekte ein- oder ausblenden") { c.toggleVisible() }
        }
        Row(Modifier.fillMaxWidth().padding(start=12.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ToolButton(icon=Glyph.Target,small=true,hint="Loft aus Querschnitten erstellen") { c.startLoft() }
            Text("Loft aus Querschnitten",color=Muted,fontSize=11.sp)
        }
        val bodies=remember(c.document) { c.document.sceneBodies() }
        AnimatedVisibility(c.objectsExpanded,enter=expandVertically(tween(MotionPreferences.panelMillis)),exit=shrinkVertically(tween(MotionPreferences.panelMillis))) { Column { bodies.forEachIndexed { index,body ->
            val sketch=body.sketch
            val name=if(sketch==null) body.mesh.name else "${if(body.mirrored) "Spiegelung" else "Extrusion"} · ${sketch.name} · ${body.regionId ?: "Profil"}"
            val isLoft=c.document.effectiveFeatures().any { it.id==body.id && it is LoftFeature }
            val isPrimitive=c.document.effectiveFeatures().any { it.id==body.id && it is PrimitiveFeature }
            BodyEntry(c,body.id,name,editable=sketch!=null || isLoft,last=index==bodies.lastIndex,
                onSelect={ if(isLoft) c.startLoft(body.id) else if(isPrimitive) c.editPrimitive(body.id) else if(sketch==null) c.select(true) else c.startExtrusion(sketch.id) },
                onEdit={ if(isLoft) c.startLoft(body.id) else if(isPrimitive) c.editPrimitive(body.id) else if(sketch==null) c.select(true) else c.startExtrusion(sketch.id) })
        } } }
    }
}
@Composable private fun BodyEntry(c: ViewerController,id: String,name: String,editable: Boolean=false,last: Boolean=false,onSelect: ()->Unit,onEdit: ()->Unit) {
    key(id) {
        val menu=remember { ContextMenuState() }
        val visible=id !in c.document.display.hiddenBodyIds
        DisposableEffect(menu.status) {
            c.viewport.captureContextMenu("body:$id",menu.status is ContextMenuState.Status.Open)
            onDispose { c.viewport.captureContextMenu("body:$id",false) }
        }
        CompositionLocalProvider(LocalContextMenuRepresentation provides if(AppAppearance.dark) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation) {
            ContextMenuArea(state=menu,items={ buildList {
                if(id!="import") add(ContextMenuItem("Verknüpfte Instanz …") { runCatching { c.startInstance(id) }.onFailure(c::reportError) })
                if(c.document.effectiveFeatures().any { it.id==id && it is InstanceFeature }) add(ContextMenuItem("Instanz bearbeiten …") { runCatching { c.startInstance(id,true) }.onFailure(c::reportError) })
                add(ContextMenuItem("Fertigungsprüfung") { runCatching { c.startAssemblyTask("manufacturing",buildJsonObject { put("bodyIds",JsonArray(listOf(JsonPrimitive(id)))) }) }.onFailure(c::reportError) })
                add(ContextMenuItem("Farbe und Bereiche …") { runCatching { c.startColorEditor(id) }.onFailure(c::reportError) })
                add(ContextMenuItem("Nur diesen Körper anzeigen") { c.isolateBody(id) })
                add(ContextMenuItem("Alle Körper anzeigen") { c.showAllBodies() })
                add(ContextMenuItem(if(visible) "Ausblenden" else "Einblenden") { c.setBodyVisibility(id,!visible) })
                if(visible && c.visible) add(ContextMenuItem("Auf diesen Körper einpassen") {
                    runCatching { c.fitBody(id).exceptionally { onUi { c.reportError(it) };Unit } }.onFailure(c::reportError)
                })
                if(editable) add(ContextMenuItem("Extrusion bearbeiten …",onEdit))
                if(c.document.effectiveFeatures().any { it.id==id && it is PrimitiveFeature }) add(ContextMenuItem("Grundkörper bearbeiten …",onEdit))
                if(c.document.effectiveFeatures().any { it.id==id }) add(ContextMenuItem("Position X/Y/Z …") { runCatching { c.startBodyPosition(id) }.onFailure(c::reportError) })
                if(id=="import") add(ContextMenuItem("Importiertes Objekt entfernen") {
                    runCatching { c.removeImportedBody().exceptionally { onUi { c.reportError(it) };Unit } }.onFailure(c::reportError)
                })
            } }) {
                TreeEntry(name,false,visible,onSelect,onEdit,last=last,color=c.document.bodyColors[id]?.color ?: DEFAULT_BODY_COLOR,onVisibility={ c.setBodyVisibility(id,!visible) })
            }
        }
    }
}

@Composable private fun TreeEntry(name: String,selected: Boolean,visible: Boolean,onSelect: ()->Unit,onEdit: ()->Unit,last: Boolean=false,onVisibility: (() -> Unit)?=null,color:String?=null) {
    val selectionTint by animateColorAsState(if(selected) Accent.copy(alpha=.12f) else Color.Transparent,tween(MotionPreferences.controlMillis))
    Row(Modifier.fillMaxWidth().height(34.dp),verticalAlignment=Alignment.CenterVertically) {
        Canvas(Modifier.width(27.dp).fillMaxHeight()) {
            drawLine(Line,Offset(12.dp.toPx(),0f),Offset(12.dp.toPx(),if(last) size.height/2 else size.height),1.dp.toPx())
            drawLine(Line,Offset(12.dp.toPx(),size.height/2),Offset(size.width-3.dp.toPx(),size.height/2),1.dp.toPx())
        }
        Row(Modifier.controlTarget("sketch:$name").weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(selectionTint)
            .combinedClickable(onClick=onSelect,onDoubleClick=onEdit).padding(start=5.dp),verticalAlignment=Alignment.CenterVertically) {
            if(color!=null) Box(Modifier.padding(end=6.dp).size(10.dp).background(Color(0xff000000L or color.removePrefix("#").toLong(16)),RoundedCornerShape(3.dp)))
            Text(name,color=if(visible) Ink else Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            onVisibility?.let { action -> Box(Modifier.controlTarget("visibility:$name")) { ToolButton(icon=if(visible) Glyph.Eye else Glyph.Hidden,small=true,hint=if(visible) "Ausblenden" else "Einblenden",action=action) } }
        }
    }
}
@Composable private fun SidebarRow(text: String, icon: Glyph, selected: Boolean, detail: String, action: () -> Unit) {
    Row(Modifier.controlTarget("row:$text").fillMaxWidth().height(36.dp).clip(RoundedCornerShape(11.dp))
        .background(if (selected) Accent.copy(alpha = .11f) else Color.Transparent).clickable(onClick = action).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, Accent, 17); Spacer(Modifier.width(10.dp))
        Text(text, color = Ink, fontSize = 13.sp); Spacer(Modifier.weight(1f))
        Text(detail, color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun Inspector(c: ViewerController) {
    Column(Modifier.width(282.dp).fillMaxHeight().background(Color.Transparent).verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (c.help) "Bedienung" else if (c.showParameters) "Parameter" else "Informationen", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            ToolButton(icon = Glyph.Close, small = true, hint = "Schließen") { c.inspector = false; c.help = false }
        }
        Spacer(Modifier.height(20.dp))
        when {
            c.help -> {
                HelpItem("Bewegen", "Mit zwei Fingern verschieben")
                HelpItem("Drehen", "⌥ gedrückt halten und zwei Finger bewegen")
                HelpItem("Zoomen", "Zwei Finger auseinander- oder zusammenziehen")
                HelpItem("Einpassen", "F oder Doppelklick")
                HelpItem("Datei öffnen", "⌘O oder ins Fenster ziehen")
                HelpItem("Projekt speichern", "⌘S · Speichern unter mit ⇧⌘S")
                HelpItem("Regionen extrudieren", "Fertige Regionen direkt anklicken, mit SHIFT über mehrere Skizzen sammeln und in der Werkzeugleiste extrudieren. Ursprungsebenen brauchen keinen Trägerkörper.")
                HelpItem("Rückgängig", "⌘Z · Wiederholen mit ⇧⌘Z")
                HelpItem("Alternative", "Mit einem Finger klickziehen zum Drehen; Zwei-Finger-Sliden zum Verschieben, Pinch zum Zoomen.")
                if (c.bridgeAvailable) Text("Lokale Steuerung über MCP verfügbar.", color = Muted, fontSize = 10.sp)
            }
            c.showParameters -> ParameterPane(c)
            c.sceneModel == null -> Text("Öffne ein Modell, um seine Abmessungen und Eigenschaften zu sehen.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            else -> {
                Text("Baugruppe · ${c.document.sceneBodies().size} Körper",color=Ink,fontSize=13.sp)
                MacButton("Explosionsansicht") { runCatching { val (meshes,_)=c.document.explodedMeshes(20.0);c.viewport.showAssemblyPreview(combineMeshes(meshes),c.document.renderColors()) }.onFailure(c::reportError) }
                MacButton("Normale Ansicht") { c.viewport.clearAssemblyPreview() }
                c.assemblyTaskState?.takeIf { it["kind"]?.jsonPrimitive?.content=="manufacturing" && it["status"]?.jsonPrimitive?.content=="complete" }?.let { job ->
                    MacButton("Warnstellen anzeigen") { c.executeCommand(buildJsonObject { put("action","cad_highlight_manufacturing");put("arguments",buildJsonObject { put("jobId",job.getValue("jobId")) }) }).exceptionally { onUi { c.reportError(it) };buildJsonObject {} } }
                }
                Spacer(Modifier.height(12.dp))
                val mesh = c.sceneModel!!
                GroupBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.Cube, Accent, 24); Spacer(Modifier.width(11.dp))
                        Column {
                            Text(if (c.document.effectiveFeatures().isNotEmpty()) "Projektgeometrie" else "Polygonnetz", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(mesh.name, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                SectionTitle("Abmessungen")
                GroupBox {
                    PropertyRow("Breite · X", "${number(mesh.bounds.size.x)} ${c.sceneUnit}")
                    PropertyRow("Tiefe · Y", "${number(mesh.bounds.size.y)} ${c.sceneUnit}")
                    PropertyRow("Höhe · Z", "${number(mesh.bounds.size.z)} ${c.sceneUnit}")
                }
                SectionTitle("Einheit")
                GroupBox {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Chrome).padding(2.dp)) {
                        for (unit in listOf("mm", "cm", "m", "in")) Box(Modifier.weight(1f).clip(RoundedCornerShape(5.dp))
                            .background(if (c.unit == unit) Panel else Color.Transparent).clickable { c.setUnit(unit) }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text(unit, color = if (c.unit == unit) Ink else Muted, fontSize = 11.sp)
                        }
                    }
                }
                Text("Die Einheit wird den Dateikoordinaten zugewiesen.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 7.dp))
                SectionTitle("Geometrie")
                GroupBox {
                    PropertyRow("Punkte", "%,d".format(Locale.GERMANY, mesh.vertices.size))
                    PropertyRow("Dreiecke", "%,d".format(Locale.GERMANY, mesh.triangleCount))
                    PropertyRow("Format", mesh.name.substringAfterLast('.').uppercase())
                }
            }
        }
        c.error?.let { message ->
            Spacer(Modifier.height(16.dp))
            Column(Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xffffeeee)).padding(12.dp)) {
                Text("Vorgang fehlgeschlagen", color = Color(0xffc9342c), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(message, color = Ink, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Schließen", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp).clickable { c.dismissError() })
            }
        }
    }
}

@Composable private fun ParameterPane(c: ViewerController) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        ToolButton(icon=Glyph.Plus,hint="Parameter hinzufügen",small=true,enabled=!c.loading) { c.editParameter() }
    }
    if(c.parameters.isEmpty()) Text("Noch keine Parameter. Mit + einen Wert hinzufügen.",color=Muted,fontSize=12.sp)
    c.parameters.forEach { parameter ->
        TooltipArea(tooltip={ Surface(color=Panel,shape=RoundedCornerShape(7.dp)) {
            Text("${parameter.name} = ${parameter.expression}",color=Ink,fontSize=11.sp,modifier=Modifier.padding(8.dp))
        } },delayMillis=500) {
            Row(Modifier.controlTarget("parameter:${parameter.name}").fillMaxWidth().height(30.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("${parameter.name} = ${c.parameterValues[parameter.name]?.label().orEmpty()}",color=Ink,fontSize=12.sp,
                    maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).fillMaxHeight().clickable(enabled=!c.loading) { c.editParameter(parameter.name) }.padding(top=7.dp))
                ToolButton(icon=Glyph.Delete,small=true,hint="${parameter.name} löschen",enabled=!c.loading) {
                    runCatching { c.removeParameter(parameter.name) }.onFailure(c::reportError)
                }
            }
        }
    }
}

@Composable private fun AssistantPane(c: ViewerController) {
    val chat=c.assistant
    val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
    var copiedMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedMessage) { if(copiedMessage!=null) { kotlinx.coroutines.delay(1800);copiedMessage=null } }
    val focusManager=LocalFocusManager.current
    var input by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    val scroll=rememberScrollState()
    DisposableEffect(Unit) { onDispose { c.assistantTyping=false;c.stopChatMarking() } }
    DisposableEffect(picker) { c.viewport.captureContextMenu("assistant-model",picker);onDispose { c.viewport.captureContextMenu("assistant-model",false) } }
    LaunchedEffect(chat.messages.lastOrNull()?.text,chat.busy) { scroll.scrollTo(scroll.maxValue) }
    SideEffect { if(controlSmokeEnabled) controlValues["chat-input"]=input }
    fun send() { if(input.isNotBlank() && !chat.busy && !c.assistantSubmitting) {
        val submitted=input
        c.sendAssistant(submitted) { if(input==submitted) input="";focusManager.clearFocus();c.assistantTyping=false }
    } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(Glyph.Chat,Accent,18);Spacer(Modifier.width(9.dp));Text("Assistent",color=Ink,fontSize=15.sp,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            ToolButton(Glyph.Plus,small=true,hint="Neuer Chat",enabled=!chat.busy && !chat.connecting && !c.assistantSubmitting) { c.clearChatReferences();chat.newConversation() }
            ToolButton(Glyph.Close,small=true,hint="Chat schließen") { c.chatVisible=false }
        }
        Spacer(Modifier.height(12.dp))
        if(chat.connected) {
            Text(chat.account,color=Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("Verwendet dein ChatGPT-Kontingent",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=3.dp))
            Row(Modifier.padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    MacButton(chat.models.firstOrNull { it.id==chat.model }?.label ?: "Modell wählen",enabled=!chat.busy && !chat.connecting && !c.assistantSubmitting) { picker=true }
                    DropdownMenu(expanded=picker,onDismissRequest={ picker=false }) {
                        chat.models.forEach { model -> DropdownMenuItem(onClick={ chat.model=model.id;picker=false }) { Text(model.label,fontSize=12.sp) } }
                    }
                }
                Text("Abmelden",color=Muted,fontSize=11.sp,modifier=Modifier.controlTarget("Chat abmelden").clickable(enabled=!chat.busy && !chat.connecting && !c.assistantSubmitting) { chat.logout() }.padding(5.dp))
            }
        } else {
            Text("Mit deinem ChatGPT-Konto konstruieren.",color=Ink,fontSize=12.sp)
            Text("Ohne API-Key. Die Anmeldung bleibt im Schlüsselbund dieses Macs.",color=Muted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=5.dp,bottom=10.dp))
            if(chat.signingIn) {
                Text("Bitte schließe die Anmeldung im Browser ab.",color=Muted,fontSize=11.sp)
                MacButton("Anmeldung abbrechen") { chat.cancelLogin() }
            } else MacButton(if(chat.connecting) "Verbindung wird hergestellt …" else "Mit ChatGPT anmelden",primary=true,enabled=!chat.connecting) { chat.signIn() }
        }
        if(chat.error!=null) {
            Text(chat.error!!,color=Accent,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=10.dp))
            if(!chat.busy && !chat.signingIn) Text("Erneut verbinden",color=Accent,fontSize=11.sp,modifier=Modifier.controlTarget("Chat erneut verbinden").clickable { chat.connect() }.padding(vertical=6.dp))
        }
        HorizontalDividerCompat()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            if(chat.messages.isEmpty()) {
                Text("Was möchtest du erstellen?",color=Ink,fontSize=14.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=12.dp))
                Text("Wähle eine Fläche aus und beschreibe die Skizze oder Extrusion. Zum Beispiel: „Erstelle darauf ein Rechteck mit 40 × 20 mm.“",color=Muted,fontSize=12.sp,lineHeight=19.sp,modifier=Modifier.padding(top=9.dp))
                Text("Nachrichten und benötigter Modellkontext werden an OpenAI übertragen. Deine CAD-Datei bleibt lokal.",color=Muted,fontSize=10.sp,lineHeight=16.sp,modifier=Modifier.padding(top=18.dp))
            }
            chat.messages.forEach { message ->
                if(message.text.isNotBlank()) Column(Modifier.fillMaxWidth().padding(vertical=7.dp).clip(RoundedCornerShape(12.dp)).background(if(message.role=="user") Accent.copy(alpha=.12f) else Panel.copy(alpha=.28f)).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(message.role=="user") "Du" else "Assistent",color=Muted,fontSize=10.sp,modifier=Modifier.weight(1f).padding(bottom=5.dp))
                        ToolButton(if(copiedMessage==message.id) Glyph.Check else Glyph.Copy,small=true,hint=if(copiedMessage==message.id) "Nachricht kopiert" else "Nachricht kopieren") {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text));copiedMessage=message.id
                        }
                    }
                    BasicTextField(value=message.text,onValueChange={},readOnly=true,
                        textStyle=TextStyle(color=Ink,fontSize=12.sp,lineHeight=19.sp),cursorBrush=SolidColor(Color.Transparent),
                        modifier=Modifier.fillMaxWidth().onFocusChanged { c.assistantTyping=it.isFocused })
                }
            }
            if(chat.busy) Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(11.dp),color=Accent,strokeWidth=1.dp);Spacer(Modifier.width(9.dp));Text(chat.activity,color=Muted,fontSize=11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            ToolButton(Glyph.Target,selected=c.chatMarking,small=true,hint="Bereich am Modell markieren",enabled=c.chatMarking || c.canMarkChat) { c.toggleChatMarking() }
            Text(if(c.chatMarking) "Auf das Modell klicken · SHIFT fügt hinzu" else "Bereich als Bezug markieren",color=Muted,fontSize=10.sp,modifier=Modifier.weight(1f))
            if(c.chatReferences.isNotEmpty())ToolButton(Glyph.Close,small=true,hint="Markierungen entfernen",enabled=!chat.busy && !c.assistantSubmitting) { c.clearChatReferences() }
        }
        if(c.chatMarking) Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Radius",color=Muted,fontSize=10.sp,modifier=Modifier.padding(end=8.dp))
            BasicTextField(value=c.chatMarkRadius,onValueChange={c.chatMarkRadius=it.take(128)},singleLine=true,textStyle=TextStyle(color=Ink,fontSize=12.sp),
                modifier=Modifier.weight(1f).onFocusChanged { c.assistantTyping=it.isFocused }.padding(6.dp))
        }
        c.chatReferences.forEach { reference -> Text("${reference.id} · ${reference.bodyName} · Radius ${number(reference.radius)} mm",color=Accent,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
        if(c.chatReferences.isNotEmpty())Text("Wird mit deiner Nachricht als räumlicher Bezug gesendet.",color=Muted,fontSize=10.sp,modifier=Modifier.padding(bottom=6.dp))
        BasicTextField(value=input,onValueChange={ input=it.take(12000) },enabled=chat.connected && !chat.busy && !c.assistantSubmitting,
            textStyle=TextStyle(color=Ink,fontSize=12.sp,lineHeight=18.sp),cursorBrush=SolidColor(Accent),maxLines=6,
            modifier=Modifier.controlTarget("Chat Eingabe").fillMaxWidth().heightIn(min=76.dp,max=150.dp).glassSurface(13.dp,1.dp,strong=true,captureInput=false)
                .onFocusChanged { c.assistantTyping=it.isFocused }.onPreviewKeyEvent { e ->
                    if(e.type==KeyEventType.KeyDown && (e.key==Key.Enter || e.key==Key.NumPadEnter) && !e.isShiftPressed) { send();true } else false
                }.padding(12.dp),decorationBox={ field -> Box { if(input.isEmpty()) Text("Beschreibe dein Bauteil …",color=Muted,fontSize=12.sp);field() } })
        Row(Modifier.fillMaxWidth().padding(top=9.dp),horizontalArrangement=Arrangement.End) {
            if(chat.busy) MacButton("Anhalten") { chat.stop() }
            else MacButton("Senden",primary=true,enabled=chat.connected && input.isNotBlank() && chat.model.isNotBlank() && !c.assistantSubmitting) { send() }
        }
    }
}
@Composable private fun HorizontalDividerCompat() { Box(Modifier.fillMaxWidth().padding(vertical=12.dp).height(.7.dp).background(Line.copy(alpha=.5f))) }

@Composable private fun SaveStatus(c: ViewerController) {
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.widthIn(max=210.dp)) {
        val job=c.assemblyTaskState
        val working=job?.get("status")?.jsonPrimitive?.content in setOf("running","committing")
        if(working) {
            Text(job?.get("message")?.jsonPrimitive?.content ?: "Baugruppe wird berechnet …",color=Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            if(job?.get("status")?.jsonPrimitive?.content=="running") MacButton("Abbrechen") { c.cancelAssemblyTask(job.getValue("jobId").jsonPrimitive.content) }
            return@Row
        }
        if(c.loading || c.saving || c.exporting) {
            CircularProgressIndicator(Modifier.size(10.dp),color=Muted,strokeWidth=1.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(when {
            job?.get("status")?.jsonPrimitive?.content in setOf("failed","stale","cancelled") -> job?.get("message")?.jsonPrimitive?.content ?: ""
            c.error!=null -> c.error!!
            c.exporting -> "Wird exportiert …"
            c.saving -> "Wird gespeichert …"
            c.loading -> "Wird geöffnet …"
            c.sketchEditor -> "Entwurf"
            c.notice!=null -> c.notice!!
            c.dirty -> "Bearbeitet"
            c.projectPath!=null -> "Gespeichert"
            else -> ""
        },color=if(c.error!=null) Color(0xffc9342c) else Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@Composable private fun ViewControls(c: ViewerController) {
    Row(Modifier.sceneBackdrop(c,"view-controls-${c.sketchEditor}",18.dp).glassSurface(18.dp,2.dp,backdrop=true).padding(horizontal=3.dp,vertical=1.dp)) {
        ToolButton(icon=Glyph.Fit,hint="Einpassen · F") { c.fit() }
        ToolButton(icon=Glyph.Grid,selected=c.grid,hint="Raster") { c.toggleGrid() }
        ToolButton(icon=Glyph.Info,selected=c.inspector && !c.showParameters,hint="Informationen") { c.chatVisible=false;c.inspector=!(c.inspector && !c.showParameters);c.showParameters=false;c.help=false }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp, bottom = 9.dp, start = 2.dp))
}
@Composable private fun GroupBox(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel.copy(alpha=.50f))
        .border(1.dp, Line.copy(alpha = .7f), RoundedCornerShape(10.dp)).padding(13.dp), content = content)
}
@Composable private fun PropertyRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Muted, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text(value, color = Ink, fontSize = 12.sp)
    }
}
@Composable private fun HelpItem(title: String, detail: String) {
    Text(title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
}
@Composable private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = .7f)))
private fun number(value: Double) = java.text.DecimalFormat("0.###", java.text.DecimalFormatSymbols(Locale.GERMANY)).format(value)

@Composable private fun ToolButton(icon: Glyph, selected: Boolean = false, hint: String, small: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverColor by animateColorAsState(if(selected) Accent.copy(alpha=.18f) else if(hovered && enabled) Panel.copy(alpha=.75f) else Color.Transparent,tween(MotionPreferences.controlMillis))
    TooltipArea(tooltip = {
        Surface(color = Panel.copy(alpha=.98f), shape = RoundedCornerShape(10.dp), elevation = 4.dp) {
            Text(hint, color = Ink, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
        }
    }, delayMillis = 650) {
        Box(Modifier.controlTarget(hint).size(if (small) 25.dp else 32.dp).clip(RoundedCornerShape(if(small) 9.dp else 12.dp))
            .background(hoverColor).background(Brush.verticalGradient(when { selected -> listOf(Panel.copy(alpha=.96f),(if(AppAppearance.dark) Color(0xff25496c) else Color(0xffd8e9ff)).copy(alpha=.85f)); hovered && enabled -> listOf(Panel.copy(alpha=.85f),Panel.copy(alpha=.40f)); else -> listOf(Color.Transparent,Color.Transparent) }))
            .then(if(selected) Modifier.border(.7.dp,Line.copy(alpha=.65f),RoundedCornerShape(12.dp)) else Modifier)
            .hoverable(interaction).clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = action)
            .semantics { contentDescription = hint }, contentAlignment = Alignment.Center) {
            Icon(icon, if (!enabled) Muted.copy(alpha = .3f) else if (selected) Accent else if(AppAppearance.dark) Color(0xffc2cddc) else Color(0xff52647c), if (small) 14 else 18)
        }
    }
}
@Composable private fun MacButton(text: String,primary: Boolean=false,enabled: Boolean=true,action: ()->Unit) {
    val shape=RoundedCornerShape(15.dp)
    val material=if(primary && enabled) Modifier.shadow(2.dp,shape,ambientColor=Accent.copy(alpha=.12f),spotColor=Accent.copy(alpha=.18f)).clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xff389cff),Color(0xff0870ef)))).border(.7.dp,Color.White.copy(alpha=.35f),shape)
        else Modifier.glassSurface(15.dp,1.dp,strong=true)
    Box(Modifier.controlTarget(text).height(29.dp).then(material).clickable(enabled=enabled,onClick=action).padding(horizontal=14.dp),contentAlignment=Alignment.Center) {
        Text(text,color=if(!enabled) Muted.copy(alpha=.55f) else if(primary) Color.White else Ink,fontSize=12.sp,fontWeight=FontWeight.Medium)
    }
}
@Composable private fun Icon(glyph: Glyph, color: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        val scale = this.size.width / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1*scale, y1*scale), Offset(x2*scale, y2*scale), 1.5f*scale)
        fun path(vararg coordinates: Float) {
            val p = DrawPath()
            p.moveTo(coordinates[0]*scale, coordinates[1]*scale)
            for (i in 2 until coordinates.size step 2) p.lineTo(coordinates[i]*scale, coordinates[i+1]*scale)
            drawPath(p, color, style = Stroke(1.5f*scale))
        }
        when (glyph) {
            Glyph.CenterRectangle -> { path(3f,5f,21f,5f,21f,19f,3f,19f,3f,5f);line(9f,12f,15f,12f);line(12f,9f,12f,15f) }
            Glyph.Rectangle -> path(3f,5f,21f,5f,21f,19f,3f,19f,3f,5f)
            Glyph.Circle -> drawCircle(color,8f*scale,center=Offset(12f*scale,12f*scale),style=Stroke(1.5f*scale))
            Glyph.Triangle -> path(3f,20f,12f,4f,21f,20f,3f,20f)
            Glyph.Line -> line(4f,19f,20f,5f)
            Glyph.Polyline -> path(3f,18f,8f,7f,14f,15f,21f,4f)
            Glyph.Arc -> path(4f,18f,4f,14f,5f,10f,8f,7f,12f,5f,16f,7f,19f,10f)
            Glyph.Extrude -> { path(4f,15f,12f,11f,20f,15f,12f,20f,4f,15f); line(12f,14f,12f,2f); path(8f,6f,12f,2f,16f,6f) }
            Glyph.Sidebar -> { path(3f,4f,21f,4f,21f,20f,3f,20f,3f,4f); line(9f,4f,9f,20f); line(5f,8f,7f,8f); line(5f,12f,7f,12f) }
            Glyph.Save -> { line(12f,3f,12f,15f); path(7f,10f,12f,15f,17f,10f); path(4f,15f,4f,21f,20f,21f,20f,15f) }
            Glyph.Undo -> { path(8f,4f,3f,9f,8f,14f); path(3f,9f,14f,9f,19f,12f,20f,16f,18f,20f) }
            Glyph.Redo -> { path(16f,4f,21f,9f,16f,14f); path(21f,9f,10f,9f,5f,12f,4f,16f,6f,20f) }
            Glyph.Parameters -> { path(15f,3f,12f,3f,9f,6f,7f,21f,4f,21f); line(4f,10f,13f,10f); path(14f,13f,21f,21f); path(21f,13f,14f,21f) }
            Glyph.Plus -> { line(12f,4f,12f,20f); line(4f,12f,20f,12f) }
            Glyph.Delete -> { path(5f,7f,6f,21f,18f,21f,19f,7f); line(3f,7f,21f,7f); path(8f,7f,8f,3f,16f,3f,16f,7f); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
            Glyph.Cube, Glyph.Wire -> {
                path(12f,2f, 22f,7f, 22f,17f, 12f,22f, 2f,17f, 2f,7f, 12f,2f)
                path(2f,7f, 12f,12f, 22f,7f); line(12f,12f,12f,22f)
                if (glyph == Glyph.Wire) { path(12f,2f,12f,12f,2f,17f); line(12f,12f,22f,17f) }
            }
            Glyph.Open -> path(2f,19f,2f,5f,9f,5f,12f,8f,21f,8f,21f,11f,7f,11f,2f,19f,19f,19f,22f,11f)
            Glyph.Fit -> {
                path(9f,3f,3f,3f,3f,9f); path(15f,3f,21f,3f,21f,9f)
                path(3f,15f,3f,21f,9f,21f); path(15f,21f,21f,21f,21f,15f)
                drawRect(color, Offset(8f*scale,8f*scale), androidx.compose.ui.geometry.Size(8f*scale,8f*scale), style=Stroke(1.3f*scale))
            }
            Glyph.Grid -> {
                for (i in listOf(4f,12f,20f)) { line(i,4f,i,20f); line(4f,i,20f,i) }
            }
            Glyph.Eye, Glyph.Hidden -> {
                path(2f,12f,6f,7f,12f,5f,18f,7f,22f,12f,18f,17f,12f,19f,6f,17f,2f,12f)
                drawCircle(color, 3f*scale, Offset(12f*scale,12f*scale), style=Stroke(1.5f*scale))
                if (glyph == Glyph.Hidden) line(3f,3f,21f,21f)
            }
            Glyph.Copy -> { path(8f,8f,21f,8f,21f,21f,8f,21f,8f,8f);path(16f,5f,16f,3f,3f,3f,3f,16f,5f,16f) }
            Glyph.Check -> path(4f,12f,9f,18f,21f,5f)
            Glyph.Target -> { drawCircle(color,7f*scale,Offset(12f*scale,12f*scale),style=Stroke(1.5f*scale));line(12f,1f,12f,7f);line(12f,17f,12f,23f);line(1f,12f,7f,12f);line(17f,12f,23f,12f) }
            Glyph.Chat -> { path(3f,4f,21f,4f,21f,17f,10f,17f,5f,21f,5f,17f,3f,17f,3f,4f);line(7f,9f,17f,9f);line(7f,13f,14f,13f) }
            Glyph.Info, Glyph.Help -> {
                drawCircle(color, 9f*scale, Offset(12f*scale,12f*scale), style=Stroke(1.5f*scale))
                if (glyph == Glyph.Info) { line(12f,11f,12f,17f); line(12f,7f,12f,8f) }
                else { path(9f,8f,10f,6f,14f,6f,16f,9f,15f,11f,12f,13f,12f,14f); line(12f,17f,12f,18f) }
            }
            Glyph.Chevron -> path(5f,9f,12f,16f,19f,9f)
            Glyph.Close -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
        }
    }
}


@Composable private fun RenameSketch(c: ViewerController) {
    val id=c.renamingSketchId ?: return
    val sketch=c.document.sketches.firstOrNull { it.id==id } ?: return
    var name by remember(id) { mutableStateOf(sketch.name) }
    var problem by remember(id) { mutableStateOf<String?>(null) }
    val focus=remember { FocusRequester() }
    fun commit() {
        runCatching { c.renameSketch(id,name);c.renamingSketchId=null }.onFailure { problem=it.message }
    }
    DialogWindow(onCloseRequest={ c.renamingSketchId=null },title="Skizze umbenennen",resizable=false,
        state=rememberDialogState(width=420.dp,height=230.dp),onPreviewKeyEvent={ event ->
            if(event.type!=KeyEventType.KeyDown) false else when(event.key) {
                Key.Escape -> { c.renamingSketchId=null;true }
                Key.Enter,Key.NumPadEnter -> { commit();true }
                else -> false
            }
        }) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxSize().background(Chrome).padding(24.dp)) {
            Text("Skizze umbenennen",color=Ink,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            FormField("Name",name,focus=focus) { name=it;problem=null }
            problem?.let { Text(it,color=Accent,fontSize=11.sp,modifier=Modifier.padding(top=8.dp)) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                MacButton("Abbrechen") { c.renamingSketchId=null };Spacer(Modifier.width(8.dp))
                MacButton("Umbenennen",primary=true) { commit() }
            }
        }
    }
}

@Composable private fun ParameterEditor(c: ViewerController) {
    val existing = c.parameters.firstOrNull { it.name == c.editingParameterName }
    var name by remember(c.editingParameterName) { mutableStateOf(existing?.name ?: "") }
    var expression by remember(c.editingParameterName) { mutableStateOf(existing?.expression ?: "20 mm") }
    var commitError by remember { mutableStateOf<String?>(null) }
    val preview = remember(name, expression, c.parameters) { runCatching {
        require(existing != null || c.parameters.none { it.name == ParameterEvaluator.name(name) }) { "Dieser Name existiert bereits." }
        ParameterEvaluator.evaluate(c.parameters.filterNot { it.name == existing?.name } + Parameter(name, expression)).getValue(ParameterEvaluator.name(name))
    } }
    val focus=remember { FocusRequester() }
    fun commit() {
        if(!c.parameterEditor || c.loading) return
        try {
            require(existing!=null || c.parameters.none { it.name==ParameterEvaluator.name(name) }) { "Dieser Name existiert bereits." }
            ParameterEvaluator.evaluate(c.parameters.filterNot { it.name==existing?.name }+Parameter(name,expression))
            c.setParameter(name,expression);c.parameterEditor=false
        }
        catch(error: Exception) { commitError=error.message }
    }
    DialogWindow(onCloseRequest = { c.parameterEditor = false }, onPreviewKeyEvent={ event ->
        if(event.type!=KeyEventType.KeyDown) false else when(event.key) {
            Key.Escape -> { c.parameterEditor=false;true }
            Key.Enter,Key.NumPadEnter -> { commit();true }
            else -> false
        }
    }, title = if (existing == null) "Neuer Parameter" else "Parameter bearbeiten",
        state = rememberDialogState(width = 460.dp, height = 350.dp), resizable = false) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxSize().background(Chrome).padding(24.dp)) {
            Text(if (existing == null) "Parameter hinzufügen" else "Parameter bearbeiten", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Ein Wert oder eine Formel mit Einheit.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 22.dp))
            FormField("Name", name, readOnly = existing != null,focus=if(existing==null) focus else null) { name = it; commitError = null }
            Spacer(Modifier.height(12.dp))
            FormField("Formel", expression,focus=if(existing!=null) focus else null) { expression = it; commitError = null }
            Text("Zum Beispiel 60 mm, 2,5 cm oder breite / 2", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp, start = 77.dp))
            Spacer(Modifier.height(16.dp))
            val problem = commitError ?: preview.exceptionOrNull()?.message
            if (problem == null) GroupBox {
                Row(Modifier.fillMaxWidth()) {
                    Text("Ergebnis", color = Muted, fontSize = 12.sp); Spacer(Modifier.weight(1f))
                    Text(preview.getOrNull()?.label() ?: "", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            } else Text(if (name.isBlank()) "Gib einen Namen für den Parameter ein." else problem,
                color = if (name.isBlank()) Muted else Color(0xffc9342c), fontSize = 11.sp, lineHeight = 17.sp)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MacButton("Abbrechen") { c.parameterEditor = false }; Spacer(Modifier.width(10.dp))
                MacButton("Übernehmen", primary = true, enabled = preview.isSuccess && !c.loading) {
                    commit()
                }
            }
        }
    }
}
@Composable private fun FormField(label: String, value: String, readOnly: Boolean = false,focus: FocusRequester?=null, changed: (String) -> Unit) {
    if(controlSmokeEnabled) {
        SideEffect { controlValues[label]=value }
        DisposableEffect(label) { onDispose { controlValues.remove(label) } }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 12.sp, modifier = Modifier.width(77.dp))
        BasicTextField(value, changed, singleLine = true, readOnly = readOnly, cursorBrush = SolidColor(Accent),
            textStyle = TextStyle(color = if (readOnly) Muted else Ink, fontSize = 13.sp, fontFamily = FontFamily.SansSerif),
            modifier = Modifier.controlTarget("field:$label").then(if(focus!=null) Modifier.focusRequester(focus) else Modifier).weight(1f).glassSurface(11.dp,0.dp,strong=true,captureInput=false)
                .border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

@Composable private fun ExtrusionProperties(c: ViewerController) {
    Column(Modifier.width(218.dp).fillMaxHeight().background(SidebarColor).padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Extrusion",color=Ink,fontSize=15.sp,fontWeight=FontWeight.SemiBold)
        Text(c.sketchDraft?.name ?: "",color=Muted,fontSize=12.sp)
        Text("Am blauen Griff ziehen oder die Tiefe direkt am Modell anklicken. ⌘ beim Ziehen: 0,1 mm. Negative Werte kehren die Richtung um.",color=Muted,fontSize=12.sp,lineHeight=18.sp)
        c.sketchPreviewError?.let { Text(it,color=Color(0xffc9342c),fontSize=12.sp) }
    }
}
@Composable private fun SketchProperties(c: ViewerController) {
    val existing = c.document.sketches.firstOrNull { it.id == c.editingSketchId }
    val draft = c.sketchDraft ?: return
    var problem by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var constraintMeasure by remember { mutableStateOf("10 mm") }
    val evaluated = runCatching { draft.profileCopies(c.parameterValues); draft.extrudedMesh(c.parameterValues) }
        Column(Modifier.width(218.dp).fillMaxHeight().background(SidebarColor).padding(12.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) "Neue Skizze" else "Skizze bearbeiten", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("${c.sketchSupportLabel} · Maße und Formeln", color = Muted, fontSize = 12.sp)
            MacButton("Normal auf Skizze") { c.alignToSketch().exceptionally { onUi { c.reportError(it) }; Unit } }
            FormField("Name", draft.name) { c.sketchDraft = draft.copy(name = it); problem = null }
            val selectedElement=draft.elements.firstOrNull { it.id==c.selectedSketchElementId } ?: draft.elements.firstOrNull()
            if(draft.elements.size>1) {
                Text("Elemente",color=Muted,fontSize=11.sp)
                draft.elements.forEachIndexed { index,element ->
                    val label=when(element.kind) {
                        SketchElementKind.PROFILE -> element.shape.label
                        SketchElementKind.LINE -> "Linie"
                        SketchElementKind.POLYLINE -> "Polylinie"
                        SketchElementKind.ARC -> "Bogen"
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(element.id in c.constraintSelection,{ c.toggleConstraintElement(element.id,it) },colors=CheckboxDefaults.colors(checkedColor=Accent))
                        MacButton("${index+1} · $label",primary=element.id==selectedElement?.id) { c.selectSketchElement(element.id) }
                        Spacer(Modifier.weight(1f))
                        ToolButton(icon=Glyph.Delete,small=true,hint="$label entfernen",enabled=draft.elements.size>1) {
                            runCatching { c.removeSketchElement(element.id) }.onFailure { problem=it.message }
                        }
                    }
                }
            } else draft.elements.singleOrNull()?.let { element ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(element.id in c.constraintSelection,{ c.toggleConstraintElement(element.id,it) },colors=CheckboxDefaults.colors(checkedColor=Accent))
                    Text("Für Constraints auswählen",color=Muted,fontSize=10.sp)
                }
            }
            val constraintElements=draft.elements.filter { it.id in c.constraintSelection }
            if(draft.elements.isNotEmpty()) {
                Text("Constraints · Elemente links markieren",color=Muted,fontSize=11.sp)
                val twoLines=constraintElements.size==2 && constraintElements.all { it.kind==SketchElementKind.LINE || it.kind==SketchElementKind.POLYLINE }
                val twoCircles=constraintElements.size==2 && constraintElements.all { it.kind==SketchElementKind.PROFILE && it.shape==SketchShape.CIRCLE }
                val oneLine=constraintElements.size==1 && (constraintElements.single().kind==SketchElementKind.LINE || constraintElements.single().kind==SketchElementKind.POLYLINE)
                val oneCircle=constraintElements.size==1 && (constraintElements.single().kind==SketchElementKind.ARC || constraintElements.single().kind==SketchElementKind.PROFILE && constraintElements.single().shape==SketchShape.CIRCLE)
                val lineAndCircle=constraintElements.size==2 && constraintElements.any { it.kind==SketchElementKind.LINE || it.kind==SketchElementKind.POLYLINE } && constraintElements.any { it.kind==SketchElementKind.PROFILE && it.shape==SketchShape.CIRCLE }
                val lineAndArc=constraintElements.size==2 && constraintElements.any { it.kind==SketchElementKind.LINE || it.kind==SketchElementKind.POLYLINE } && constraintElements.any { it.kind==SketchElementKind.ARC }
                if(oneLine || oneCircle || twoCircles) {
                    FormField(if(oneLine && constraintMeasure.endsWith("deg")) "Winkel / Formel" else "Maß / Formel",constraintMeasure) { constraintMeasure=it }
                }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    if(oneLine) {
                        MacButton("Horizontal") { runCatching { c.addSketchConstraint(SketchConstraintType.HORIZONTAL,constraintElements.map { it.id },listOf(0)) }.onFailure { problem=it.message } }
                        MacButton("Vertikal") { runCatching { c.addSketchConstraint(SketchConstraintType.VERTICAL,constraintElements.map { it.id },listOf(0)) }.onFailure { problem=it.message } }
                        MacButton("Länge") { runCatching { c.addSketchConstraint(SketchConstraintType.FIXED_LENGTH,constraintElements.map { it.id },listOf(0),constraintMeasure) }.onFailure { problem=it.message } }
                        MacButton("Winkel") { runCatching { c.addSketchConstraint(SketchConstraintType.ANGLE,constraintElements.map { it.id },listOf(0),constraintMeasure) }.onFailure { problem=it.message } }
                    }
                    if(twoLines) {
                        MacButton("Parallel") { runCatching { c.addSketchConstraint(SketchConstraintType.PARALLEL,constraintElements.map { it.id },listOf(0,0)) }.onFailure { problem=it.message } }
                        MacButton("Senkrecht") { runCatching { c.addSketchConstraint(SketchConstraintType.PERPENDICULAR,constraintElements.map { it.id },listOf(0,0)) }.onFailure { problem=it.message } }
                        MacButton("Gleich") { runCatching { c.addSketchConstraint(SketchConstraintType.EQUAL_LENGTH,constraintElements.map { it.id },listOf(0,0)) }.onFailure { problem=it.message } }
                        MacButton("Koinzident") { runCatching { c.addSketchConstraint(SketchConstraintType.COINCIDENT,constraintElements.map { it.id },listOf(0,0)) }.onFailure { problem=it.message } }
                    }
                    if(oneCircle) {
                        MacButton("Radius") { runCatching { c.addSketchConstraint(SketchConstraintType.RADIUS,constraintElements.map { it.id },value=constraintMeasure) }.onFailure { problem=it.message } }
                        MacButton("Durchmesser") { runCatching { c.addSketchConstraint(SketchConstraintType.DIAMETER,constraintElements.map { it.id },value=constraintMeasure) }.onFailure { problem=it.message } }
                    }
                    if(twoCircles) {
                        MacButton("Konzentrisch") { runCatching { c.addSketchConstraint(SketchConstraintType.CONCENTRIC,constraintElements.map { it.id }) }.onFailure { problem=it.message } }
                        MacButton("Tangential außen") { runCatching { c.addSketchConstraint(SketchConstraintType.TANGENT_CIRCLES,constraintElements.map { it.id }) }.onFailure { problem=it.message } }
                        MacButton("Tangential innen") { runCatching { c.addSketchConstraint(SketchConstraintType.TANGENT_CIRCLES,constraintElements.map { it.id },internal=true) }.onFailure { problem=it.message } }
                        MacButton("Abstand") { runCatching { c.addSketchConstraint(SketchConstraintType.DISTANCE,constraintElements.map { it.id },listOf(0,0),constraintMeasure) }.onFailure { problem=it.message } }
                    }
                    if(lineAndCircle) MacButton("Tangential") { runCatching {
                        val line=constraintElements.first { it.kind==SketchElementKind.LINE || it.kind==SketchElementKind.POLYLINE }
                        val circle=constraintElements.first { it.kind==SketchElementKind.PROFILE && it.shape==SketchShape.CIRCLE }
                        c.addSketchConstraint(SketchConstraintType.TANGENT_LINE_CIRCLE,listOf(line.id,circle.id),listOf(0))
                    }.onFailure { problem=it.message } }
                    if(lineAndArc) MacButton("Bogen anschließen") { runCatching {
                        val line=constraintElements.first { it.kind==SketchElementKind.LINE || it.kind==SketchElementKind.POLYLINE }
                        val arc=constraintElements.first { it.kind==SketchElementKind.ARC }
                        c.addSketchConstraint(SketchConstraintType.TANGENT_ARC,listOf(line.id,arc.id),listOf(0,1,0))
                    }.onFailure { problem=it.message } }
                }
                c.constraintStatus?.let { Text(it,color=Muted,fontSize=10.sp) }
                draft.constraints.forEach { constraint ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(constraint.type.name.lowercase().replace('_',' ')+(constraint.value?.let { " · $it" } ?: ""),color=Muted,fontSize=10.sp)
                        Spacer(Modifier.weight(1f))
                        ToolButton(icon=Glyph.Delete,small=true,hint="Constraint entfernen") { runCatching { c.removeSketchConstraint(constraint.id) }.onFailure { problem=it.message } }
                    }
                }
            }
            if(selectedElement!=null) {
                Text("${when(selectedElement.kind) { SketchElementKind.PROFILE -> selectedElement.shape.label; SketchElementKind.LINE -> "Linie"; SketchElementKind.POLYLINE -> "Polylinie"; SketchElementKind.ARC -> "Bogen" }} bearbeiten",color=Muted,fontSize=11.sp)
                fun edit(change:(SketchElement)->SketchElement) { c.updateSketchElement(selectedElement.id,change);problem=null }
                when(selectedElement.kind) {
                    SketchElementKind.PROFILE -> {
                        FormField("Position U",selectedElement.u) { value -> edit { it.copy(u=value) } }
                        FormField("Position V",selectedElement.v) { value -> edit { it.copy(v=value) } }
                        FormField(if(selectedElement.shape==SketchShape.CIRCLE) "Radius" else "Breite",selectedElement.width) { value -> edit { it.copy(width=value) } }
                        if(selectedElement.shape!=SketchShape.CIRCLE) FormField("Höhe",selectedElement.height) { value -> edit { it.copy(height=value) } }
                        if(selectedElement.shape!=SketchShape.CIRCLE) FormField("Drehung",selectedElement.angle) { value -> edit { it.copy(angle=value) } }
                    }
                    SketchElementKind.LINE,SketchElementKind.POLYLINE -> selectedElement.vertices.forEachIndexed { index,point ->
                        FormField("Punkt ${index+1} U",point.u) { value -> edit { element -> element.copy(vertices=element.vertices.mapIndexed { i,p -> if(i==index) p.copy(u=value) else p }) } }
                        FormField("Punkt ${index+1} V",point.v) { value -> edit { element -> element.copy(vertices=element.vertices.mapIndexed { i,p -> if(i==index) p.copy(v=value) else p }) } }
                    }
                    SketchElementKind.ARC -> {
                        FormField("Mittelpunkt U",selectedElement.u) { value -> edit { it.copy(u=value) } }
                        FormField("Mittelpunkt V",selectedElement.v) { value -> edit { it.copy(v=value) } }
                        FormField("Radius",selectedElement.width) { value -> edit { it.copy(width=value) } }
                        FormField("Startwinkel",selectedElement.startAngle) { value -> edit { it.copy(startAngle=value) } }
                        FormField("Bogenwinkel",selectedElement.sweepAngle) { value -> edit { it.copy(sweepAngle=value) } }
                    }
                }
            } else Text("Maße direkt am Profil anklicken. Weiße Ziehpunkte ändern die Größe; der Mittelpunkt verschiebt das Profil.", color=Muted, fontSize=12.sp, lineHeight=18.sp)
            MacButton(if(advanced) "Weniger Einstellungen" else "Lage und Spiegelung …") { advanced=!advanced }
            if(advanced && draft.elements.size<=1) {
            FormField(if(draft.centered) "Mittelpunkt U" else "Position U", draft.u) { c.updateSketchDraft(draft.copy(u = it)); problem = null }
            FormField(if(draft.centered) "Mittelpunkt V" else "Position V", draft.v) { c.updateSketchDraft(draft.copy(v = it)); problem = null }
            if (draft.shape != SketchShape.CIRCLE) FormField("Drehung", draft.angle) { c.updateSketchDraft(draft.copy(angle = it)); problem = null }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.mirror != null, { c.sketchDraft = draft.copy(mirror = if (it) SketchPlane.YZ else null); problem = null },
                    colors = CheckboxDefaults.colors(checkedColor = Accent))
                Text("Gespiegelte Kopie", color = Ink, fontSize = 13.sp)
            }
            draft.mirror?.let { plane ->
                Text("Spiegelebene", color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SketchPlane.entries.forEach { target -> MacButton(target.name, primary = plane == target) { c.sketchDraft = draft.copy(mirror = target); problem = null } }
                }
                FormField("Versatz", draft.mirrorOffset) { c.sketchDraft = draft.copy(mirrorOffset = it); problem = null }
                Text("Versatz entlang ${when(plane) { SketchPlane.XY -> "Z"; SketchPlane.XZ -> "Y"; SketchPlane.YZ -> "X" }}. Original und Kopie bleiben verknüpft und separat.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
            Text("Zum Beispiel 60 mm oder breite / 2. U und V sind die Achsen der gewählten Ebene. U/V beschreibt bei Mittelpunkt-Rechteck und Kreis die Mitte, bei den anderen Profilen die linke untere Ecke. Die Dreiecksspitze liegt mittig über der Basis. Drehung: z. B. 30 deg, um Position U/V.",
                color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
            }
            Text(problem ?: c.sketchPreviewError ?: evaluated.exceptionOrNull()?.message ?: if(c.sketchHasProfile) "Profil ist gültig" else "Profil auf der Fläche aufziehen.", color = if (problem != null || c.sketchPreviewError!=null || evaluated.isFailure) Color(0xffc9342c) else Accent, fontSize = 11.sp)
        }
            Spacer(Modifier.height(12.dp))
            if(existing!=null) MacButton("Skizze löschen") { runCatching { c.deleteEditingSketch() }.onFailure { problem=it.message } }
        }
}

@Composable private fun ModelActionBar(c: ViewerController) {
    if(!c.sketchEditor && c.selectedSketchId==null && !c.selectingSupport && c.selectedFace==null) return
    Box(Modifier.height(40.dp),contentAlignment=Alignment.CenterStart) {
      Row(Modifier.height(40.dp).sceneBackdrop(c,"actions",20.dp).glassSurface(20.dp,2.dp,backdrop=true).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        when {
            c.sketchEditor && c.extruding -> {
                Icon(Glyph.Extrude,Accent,20)
                Text("Extrusion · Tiefe direkt am Modell eingeben",color=Muted,fontSize=12.sp)
            }
            c.sketchEditor -> {
                listOf(
                    SketchDrawingTool.RECTANGLE to Glyph.Rectangle,
                    SketchDrawingTool.CENTER_RECTANGLE to Glyph.CenterRectangle,
                    SketchDrawingTool.CIRCLE to Glyph.Circle,
                    SketchDrawingTool.TRIANGLE to Glyph.Triangle,
                    SketchDrawingTool.LINE to Glyph.Line,
                    SketchDrawingTool.POLYLINE to Glyph.Polyline,
                    SketchDrawingTool.ARC to Glyph.Arc
                ).forEach { (tool,glyph) -> ToolButton(icon=glyph,selected=c.sketchTool==tool,hint=when(tool) {
                    SketchDrawingTool.POLYLINE -> "Polylinie · Punkte setzen, Doppelklick oder Return beendet"
                    SketchDrawingTool.ARC -> "Bogen · Mittelpunkt, Start und Ende anklicken"
                    SketchDrawingTool.LINE -> "Linie · Anfang und Ende anklicken"
                    SketchDrawingTool.CENTER_RECTANGLE -> "Mittelpunkt-Rechteck · Mitte und Ecke"
                    else -> tool.label
                }) { c.selectSketchTool(tool);c.sketchCentered=tool==SketchDrawingTool.CENTER_RECTANGLE }
                }
                Box(Modifier.width(1.dp).height(22.dp).background(Line))
                Checkbox(c.sketchSnap,{c.sketchSnap=it},colors=CheckboxDefaults.colors(checkedColor=Accent));Text("1 mm",fontSize=11.sp,color=Muted)
            }
            c.selectedRegions.isNotEmpty() -> {
                MacButton("${c.selectedRegions.size} Regionen extrudieren") { runCatching { c.startRegionExtrusion() }.onFailure(c::reportError) }
                Text("SHIFT: Region hinzufügen oder abwählen",color=Muted,fontSize=12.sp)
            }
            c.selectedSketchId!=null && !c.selectingSupport && c.document.sketches.any { it.id==c.selectedSketchId } -> {
                ToolButton(icon=Glyph.Extrude,hint="Aus gewählter Skizze extrudieren") { c.startExtrusion(c.selectedSketchId!!) }
                MacButton("Skizze bearbeiten") { c.editSketch(c.selectedSketchId) }
                Text(c.document.sketches.first { it.id==c.selectedSketchId }.name,color=Muted,fontSize=12.sp)
            }
            c.selectingSupport -> {
                SketchPlane.entries.forEach { plane -> MacButton(plane.name) { c.chooseSketchPlane(plane) } }
                MacButton("Abbrechen") { c.cancelSupportSelection() }
            }
            c.selectedFace!=null -> {
                MacButton("Flächenfarbe …") { runCatching { c.startColorEditor() }.onFailure(c::reportError) }
                Text(c.selectedFace!!.label,color=Ink,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                MacButton("${c.selectedFaces.size.coerceAtLeast(1)} Fläche${if(c.selectedFaces.size==1) "" else "n"} extrudieren") { runCatching { c.startFaceExtrusion() }.onFailure(c::reportError) }
                MacButton("Normal auf Fläche") { c.alignToSketch().exceptionally { onUi { c.reportError(it) }; Unit } }
            }
            else -> Unit
        }
      }
    }
}
@Composable private fun WindowScope.SketchModeToolbar(c: ViewerController) {
    WindowDraggableArea {
        Row(Modifier.fillMaxWidth().height(46.dp).sceneBackdrop(c,"sketch-header",0.dp).padding(end=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.width(80.dp))
            Icon(Glyph.Grid,Accent,20)
            Column(Modifier.weight(1f)) {
                Text(if(c.extruding) "Extrusion" else "Skizze bearbeiten",color=Ink,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                Text(c.sketchSupportLabel,color=Muted,fontSize=11.sp)
            }
            MacButton("Abbrechen") { c.cancelSketch() }
            MacButton(if(c.extruding) "Objekt übernehmen" else "Skizze fertigstellen",primary=true,enabled=c.sketchHasProfile && c.sketchPreviewError==null) { runCatching { c.finishSketch().exceptionally { onUi { c.reportError(it) }; Unit } }.onFailure(c::reportError) }
        }
    }
}

private fun keyboardSmoke(c: ViewerController,window: java.awt.Window,output: java.io.File,quit: ()->Unit) {
    val original=c.document
    check(c.parameters.none { it.name=="q" })
    var finished: Sketch?=null
    fun dialog()=checkNotNull(window.ownedWindows.firstOrNull { it.isShowing && it is java.awt.Dialog })
    fun click(label: String,owner: java.awt.Window=window) {
        val r=checkNotNull(controlTargets[label]) { "Missing control: $label" }
        val content=(owner as javax.swing.RootPaneContainer).contentPane
        val origin=javax.swing.SwingUtilities.convertPoint(content,0,0,owner)
        clickWindow(owner,r.x+r.width/2+origin.x,r.y+r.height/2+origin.y)
    }
    fun key(owner: java.awt.Window,code: Int,modifiers: Int=0) {
        val target=checkNotNull(owner.focusOwner)
        val manager=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        for(id in listOf(java.awt.event.KeyEvent.KEY_PRESSED,java.awt.event.KeyEvent.KEY_RELEASED))
            manager.dispatchEvent(java.awt.event.KeyEvent(target,id,System.currentTimeMillis(),modifiers,code,java.awt.event.KeyEvent.CHAR_UNDEFINED,java.awt.event.KeyEvent.KEY_LOCATION_STANDARD))
    }
    fun modelClick(x: Int,y: Int) {
        clickWindow(window,x,y)
        val target=windowTarget(window,x,y);val p=javax.swing.SwingUtilities.convertPoint(window,x,y,target)
        target.dispatchEvent(java.awt.event.MouseEvent(target,java.awt.event.MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,p.x,p.y,1,false,java.awt.event.MouseEvent.BUTTON1))
    }
    val meta=java.awt.event.InputEvent.META_DOWN_MASK
    val shift=java.awt.event.InputEvent.SHIFT_DOWN_MASK
    val steps=listOf<()->Unit>(
        { click("Parameter") },
        { click("Parameter hinzufügen") },
        { check(c.parameterEditor && !c.canUndo && !c.canRedo);key(dialog(),java.awt.event.KeyEvent.VK_ENTER) },
        { check(c.parameterEditor && c.document==original);typeWindow(dialog(),"q") },
        { check(controlValues["Name"]=="q") { "Name focus: ${controlValues["Name"]}, formula: ${controlValues["Formel"]}, owner: ${dialog().focusOwner}" };key(dialog(),java.awt.event.KeyEvent.VK_Z,meta) },
        { check(controlValues["Name"]=="") { "Text undo did not stay in parameter field" };check(c.document==original)
          key(dialog(),java.awt.event.KeyEvent.VK_Z,meta or shift)
        },
        { check(controlValues["Name"]=="q");check(c.document==original);click("field:Formel",dialog()) },
        { selectWindowText(dialog()) },
        { typeWindow(dialog(),"42 mm") },
        { check(controlValues["Formel"]=="42 mm") { "Formula=${controlValues["Formel"]}, Name=${controlValues["Name"]}" };key(dialog(),java.awt.event.KeyEvent.VK_ENTER) },
        { check(!c.parameterEditor && c.parameters.any { it.name=="q" && it.expression=="42 mm" }) { "Enter: editor=${c.parameterEditor}, parameters=${c.parameters}, fields=$controlValues" };key(window,java.awt.event.KeyEvent.VK_Z,meta) },
        { check(c.document==original);key(window,java.awt.event.KeyEvent.VK_Z,meta or shift) },
        { check(c.parameters.any { it.name=="q" });key(window,java.awt.event.KeyEvent.VK_Z,meta) },
        { check(c.document==original);click("Parameter hinzufügen") },
        { typeWindow(dialog(),"q") },
        { key(dialog(),java.awt.event.KeyEvent.VK_ESCAPE) },
        { check(!c.parameterEditor && c.document==original);click("Schließen") },
        { click("Neue Skizze") },
        { click("XY") },
        { check(c.sketchEditor && !c.sketchHasProfile);modelClick(700,550) },
        { check(c.viewport.pendingDrawing().get(5,java.util.concurrent.TimeUnit.SECONDS));key(window,java.awt.event.KeyEvent.VK_ESCAPE) },
        { check(!c.viewport.pendingDrawing().get(5,java.util.concurrent.TimeUnit.SECONDS));check(c.sketchEditor && !c.sketchHasProfile && c.document==original)
          modelClick(700,550)
        },
        { check(c.viewport.pendingDrawing().get(5,java.util.concurrent.TimeUnit.SECONDS));modelClick(780,590) },
        { check(c.sketchHasProfile);finished=c.sketchDraft;key(window,java.awt.event.KeyEvent.VK_ESCAPE) },
        { check(c.sketchEditor && c.sketchHasProfile && c.sketchDraft==finished && c.document==original);click("Abbrechen") },
        { check(!c.sketchEditor && c.document==original)
          output.parentFile.mkdirs();ImageIO.write(captureCadWindow(window,c.viewport),"png",output)
          println("KEYBOARD_OK autoFocus=true invalidEnterSafe=true textUndo=true textRedo=true parameterEnter=true projectUndo=true projectRedo=true dialogEscape=true pendingDrawingEscape=true finishedProfilePreserved=true")
          quit()
        }
    )
    var index=0
    Timer(500) { event ->
        if(index==steps.size) (event.source as Timer).stop() else steps[index++]()
    }.apply { initialDelay=800;start() }
}

private fun assistantSmoke(c: ViewerController,window: java.awt.Window,output: java.io.File,quit: ()->Unit) {
    val original=c.document
    var pose=emptyList<Double>()
    fun click(label: String) {
        val r=checkNotNull(controlTargets[label]) { "Missing control $label" }
        if(label=="Chat Eingabe") clickWindowWithRobot(window,r.x+24,r.y+18) else clickWindow(window,r.x+r.width/2,r.y+r.height/2)
    }
    fun key(code: Int,modifiers: Int=0) {
        pressWindowKeyWithRobot(code,modifiers)
    }
    val steps=listOf<()->Unit>(
        { pose=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS);c.pickSceneFace(4);click("Assistent") },
        { check(c.chatVisible);c.viewport.assertCubeUnobscured().get(5,java.util.concurrent.TimeUnit.SECONDS) },
        { check(c.assistant.connected && c.assistant.models.isNotEmpty()) { "Chat did not connect: ${c.assistant.error}" };click("Chat Eingabe") },
        { typeWindow(window,"f") },
        { check(controlValues["chat-input"]=="f");check(c.assistantTyping);check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==pose);selectWindowText(window) },
        { typeWindow(window,"Breite") },
        { key(java.awt.event.KeyEvent.VK_ENTER) },
        { check(!c.assistant.busy && c.assistant.messages.lastOrNull()?.text=="Breite auf 42 mm gesetzt.") { "Chat failed: ${c.assistant.error}, ${c.assistant.messages}" }
          check(c.parameterValues["chat_breite"]?.value==42.0);check(c.document.sketches==original.sketches);check(c.canUndo)
          output.parentFile.mkdirs();ImageIO.write(captureCadWindow(window,c.viewport),"png",output)
          click("Chat Eingabe") },
        { typeWindow(window,"warte") },{ key(java.awt.event.KeyEvent.VK_ENTER) },
        { check(c.assistant.busy);click("Anhalten") },
        { check(!c.assistant.busy && c.assistant.messages.last().text=="Angehalten.");c.undo() },
        { check(c.document==original);click("Chat abmelden") },
        { check(!c.assistant.connected && c.assistant.messages.isEmpty());click("Chat schließen") },
        { check(!c.chatVisible && !c.assistantTyping);check(c.document==original);check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==pose)
          println("ASSISTANT_UI_OK input=true textShortcuts=true streaming=true cadTool=true selection=true stop=true logout=true undo=true projectPreserved=true cameraStable=true");quit() }
    )
    var index=0
    Timer(650) { event ->
        if(index==steps.size) (event.source as Timer).stop() else try { steps[index++]() }
        catch(error:Throwable) {
            runCatching { output.resolveSibling(output.name+".log").writeText(error.stackTraceToString()) }
            error.printStackTrace();kotlin.system.exitProcess(1)
        }
    }.apply { initialDelay=800;start() }
}

private fun assistantMeshSmoke(c:ViewerController,window:java.awt.Window,kind:String,output:java.io.File,quit:()->Unit) {
    require(kind=="monitor" || kind=="rack")
    val original=c.document;checkNotNull(original.mesh)
    var pose=emptyList<Double>()
    fun click(label:String) { val r=checkNotNull(controlTargets[label]) { "Missing control $label" };if(label=="Chat Eingabe") clickWindowWithRobot(window,r.x+24,r.y+18) else clickWindow(window,r.x+r.width/2,r.y+r.height/2) }
    fun key(code:Int) {
        pressWindowKeyWithRobot(code)
    }
    val prompt=if(kind=="monitor") "Erstelle eine parametrische Kopie der Monitorhalterung. Vergroessere die mittlere Oeffnung auf 22 mm." else "Erstelle eine parametrische Kopie des Squat rack."
    val expectedFeatures=if(kind=="monitor") 1 else 5
    fun trace(phase:String) {
        output.resolveSibling(output.name+".trace").appendText("phase=$phase input=${controlValues["chat-input"]} typing=${c.assistantTyping} connected=${c.assistant.connected} model=${c.assistant.model} busy=${c.assistant.busy} loading=${c.loading} saving=${c.saving} exporting=${c.exporting} sketchEditor=${c.sketchEditor} parameterEditor=${c.parameterEditor} error=${c.assistant.error} controllerError=${c.error} messages=${c.assistant.messages}\n")
    }
    val deadline=SmokeDeadline()
    fun awaitState(label:String,timeoutSeconds:Long=30,condition:()->Boolean) {
        deadline.await(label,timeoutSeconds,c.assistant.error ?: c.error,condition)
    }
    var focusAttempt=0L
    var typing:java.util.concurrent.CompletableFuture<Void>?=null
    val steps=listOf<()->Unit>(
        { pose=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS);click("Assistent") },
        { awaitState("chat-layout") { c.chatVisible && runCatching { c.viewport.assertCubeUnobscured().get(5,java.util.concurrent.TimeUnit.SECONDS) }.isSuccess } },
        { awaitState("connect") { c.assistant.connected && c.assistant.models.isNotEmpty() };c.assistant.newConversation();click("Chat Eingabe") },
        { if(!c.assistantTyping && System.nanoTime()-focusAttempt>500_000_000) { click("Chat Eingabe");focusAttempt=System.nanoTime() };awaitState("focus") { c.assistantTyping };typing=java.util.concurrent.CompletableFuture.runAsync { typeWindow(window,prompt,25) } },
        { awaitState("input") { typing?.isDone==true && controlValues["chat-input"]==prompt };typing!!.join();trace("input-verified") },
        { trace("before-submit");if(System.getenv("KOTLIN_CAD_SMOKE_SEND")=="button") click("Senden") else key(java.awt.event.KeyEvent.VK_ENTER) },
        { awaitState("submitted") { c.assistant.messages.count { it.role=="user" && it.text==prompt }==1 };trace("submitted") },
        { awaitState("completion",120) { !c.assistant.busy && c.assistant.messages.lastOrNull()?.text?.contains("Parametrische Kopie bestätigt")==true };trace("completed")
          check(c.assistant.messages.lastOrNull()?.text?.contains("Parametrische Kopie bestätigt")==true) { "Chat did not confirm the reconstruction: ${c.assistant.messages}" }
          check(c.document.mesh===original.mesh) { "The source mesh was replaced" }
          check(c.document.effectiveFeatures().size==expectedFeatures) { "Unexpected reconstruction features: ${c.document.effectiveFeatures().size}" }
          check(c.document.sceneBodies().size==expectedFeatures+1) { "The source body or a proposed body is missing" }
          if(kind=="monitor") {
              check(c.document.sketches.size==1)
              val opening=c.parameterValues.entries.single { it.key.startsWith("openingRadius_") }.value.value
              check(kotlin.math.abs(opening-11.0)<.01) { "The central 22 mm opening was not applied: $opening" }
          } else {
              check(c.document.sketches.isEmpty())
              val wall=c.parameterValues.entries.single { it.key.startsWith("rackWallThickness_") }.value.value
              check(kotlin.math.abs(wall-3.0)<.2) { "The rack reference thickness was not reconstructed: $wall" }
          }
          check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==pose) { "Opening the chat changed the camera" }
          output.parentFile.mkdirs();ImageIO.write(captureCadWindow(window,c.viewport),"png",output)
          trace("ASSISTANT_MESH_UI_OK");println("ASSISTANT_MESH_UI_OK kind=$kind features=$expectedFeatures sourcePreserved=true parameterized=true previewAccepted=true cameraStable=true");click("Chat schließen") },
        { check(!c.chatVisible && c.document.mesh===original.mesh);quit() }
    )
    var index=0
    Timer(100) { event ->
        if(index==steps.size) (event.source as Timer).stop() else try { steps[index]();index++;deadline.nextPhase() }
        catch(_:SmokePending) { /* Wait for observable state without blocking the event thread. */ }
        catch(error:Throwable) {
            runCatching { trace("failure");ImageIO.write(captureCadWindow(window,c.viewport),"png",output) }
            runCatching { output.resolveSibling(output.name+".log").writeText(error.stackTraceToString()) }
            error.printStackTrace();kotlin.system.exitProcess(1)
        }
    }.apply { initialDelay=800;start() }
}

private fun bodyVisibilitySmoke(c: ViewerController,window: java.awt.Window,quit: ()->Unit) {
    val original=c.document;val first=original.sceneBodies().first { it.sketch!=null }.sketch!!
    val firstBody=original.sceneBodies().first { it.sketch?.id==first.id }
    val firstBodyName="${if(firstBody.mirrored) "Spiegelung" else "Extrusion"} · ${first.name} · ${firstBody.regionId ?: "Profil"}"
    val importName=checkNotNull(original.mesh).name
    val originalCount=checkNotNull(c.sceneModel).triangleCount
    val importedCount=original.mesh.triangleCount
    val bodyCount=first.bodyCopies(c.parameterValues).first().triangleCount
    var pose=emptyList<Double>()
    fun stable() { check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==pose) { "Visibility moved the camera" } }
    fun click(label: String) {
        val r=checkNotNull(controlTargets[label]) { "Missing control $label" }
        clickWindow(window,r.x+r.width/2,r.y+r.height/2)
    }
    fun context(name: String) {
        val r=checkNotNull(controlTargets["sketch:$name"])
        val x=r.x+40;val y=r.y+r.height/2
        val target=windowTarget(window,x,y);val p=javax.swing.SwingUtilities.convertPoint(window,x,y,target)
        for(id in listOf(java.awt.event.MouseEvent.MOUSE_PRESSED,java.awt.event.MouseEvent.MOUSE_RELEASED))
            target.dispatchEvent(java.awt.event.MouseEvent(target,id,System.currentTimeMillis(),0,p.x,p.y,1,true,java.awt.event.MouseEvent.BUTTON3))
    }
    fun menu(name: String,index: Int) {
        val r=checkNotNull(controlTargets["sketch:$name"])
        clickWindow(window,r.x+185,r.y+r.height/2+8+index*32+16)
    }
    val steps=listOf<()->Unit>(
        { pose=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS);click("visibility:$importName") },
        { check("import" in c.document.display.hiddenBodyIds);check(c.sceneModel?.triangleCount==originalCount-importedCount);stable();click("visibility:$firstBodyName") },
        { check(c.sceneModel?.triangleCount==originalCount-importedCount-bodyCount);check(c.document.sketches==original.sketches);stable();click("Alle Objekte ein- oder ausblenden") },
        { check(!c.visible && c.document.display.hiddenBodyIds.size==2);click("Alle Objekte ein- oder ausblenden") },
        { check(c.visible && c.document.display.hiddenBodyIds.size==2);stable();c.undo() },
        { c.undo() },{ c.undo() },{ c.undo() },
        { check(c.document==original);stable();c.redo() },
        { check("import" in c.document.display.hiddenBodyIds);stable();c.undo() },
        { click("visibility:${first.name}") },
        { check(!c.document.sketches.first { it.id==first.id }.visible);stable();c.undo() },
        { check(c.document==original);c.document.sceneBodies().forEach { c.setBodyVisibility(it.id,false) } },
        { check(c.sceneModel==null && c.canExport);check(c.document.sketches==original.sketches);stable()
          repeat(original.sceneBodies().size) { c.undo() } },
        { check(c.document==original);stable();context(importName) },
        { menu(importName,0) },
        { check(c.document.sceneBodies().filter { it.visible }.map { it.id }==listOf("import"));stable();context(firstBodyName) },
        { menu(firstBodyName,0) },
        { check(c.document.sceneBodies().filter { it.visible }.map { it.id }==listOf(bodyId(first.id)));check(c.document.sketches==original.sketches);stable();context(importName) },
        { menu(importName,1) },
        { check(c.document.sceneBodies().all { it.visible });stable();c.undo() },
        { check(c.document.sceneBodies().filter { it.visible }.map { it.id }==listOf(bodyId(first.id)));c.undo() },
        { check(c.document.sceneBodies().filter { it.visible }.map { it.id }==listOf("import"));c.undo() },
        { check(c.document==original);c.redo() },
        { check(c.document.sceneBodies().filter { it.visible }.map { it.id }==listOf("import"));c.undo() },
        { check(c.document==original);stable();context(firstBodyName) },
        { menu(firstBodyName,3) },
        { val actual=c.viewport.cameraPose().get(5,java.util.concurrent.TimeUnit.SECONDS)
          check(actual.zoom>pose[7]);check(actual.yaw==pose[3] && actual.pitch==pose[4]);check(c.document==original)
          c.viewport.assertBodyFramed(first.bodyCopies(c.parameterValues).first().bounds).get(5,java.util.concurrent.TimeUnit.SECONDS)
          c.fit() },
        { check(c.document==original);stable();context(importName) },
        { menu(importName,4) },
        { check(c.document.mesh==null);check(c.document.source==null);check(c.document.sketches.size==original.sketches.size);check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS).take(8)==pose.take(8));c.undo() },
        { check(c.document==original);c.redo() },
        { check(c.document.mesh==null);c.undo() },
        { check(c.document==original);stable();println("BODY_VISIBILITY_OK importRemoval=true fitBody=true isolation=true showAll=true contextMenu=true import=true extrusion=true sketchIndependent=true allHidden=true exportAvailable=true globalPreservesLocal=true undo=true redo=true cameraStable=true");quit() }
    )
    var index=0
    Timer(500) { event -> if(index==steps.size) (event.source as Timer).stop() else steps[index++]() }.apply { initialDelay=800;start() }
}

private fun sketchActionsSmoke(c: ViewerController,window: java.awt.Window,quit: ()->Unit) {
    val original=c.document;val first=original.sketches.first()
    var pose=emptyList<Double>()
    fun context() {
        val r=checkNotNull(controlTargets["sketch:${c.document.sketches.first().name}"])
        val x=r.x+40;val y=r.y+r.height/2
        val target=windowTarget(window,x,y);val point=javax.swing.SwingUtilities.convertPoint(window,x,y,target)
        for(id in listOf(java.awt.event.MouseEvent.MOUSE_PRESSED,java.awt.event.MouseEvent.MOUSE_RELEASED))
            target.dispatchEvent(java.awt.event.MouseEvent(target,id,System.currentTimeMillis(),0,point.x,point.y,1,true,java.awt.event.MouseEvent.BUTTON3))
    }
    fun menu(index: Int) {
        val popup=java.awt.Window.getWindows().lastOrNull { it.isVisible && it!==window && it !is java.awt.Frame && it !is java.awt.Dialog }
        ImageIO.write(captureCadWindow(window,c.viewport),"png",java.io.File("build/qa/context128.png"))
        if(popup!=null) clickWindow(popup,70,8+index*32+16) else {
            val r=checkNotNull(controlTargets["sketch:${c.document.sketches.first().name}"])
            clickWindow(window,r.x+185,r.y+r.height/2+8+index*32+16)
        }
    }
    fun dialog()=java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().first { it.isVisible }
    fun key(code: Int) {
        val target=checkNotNull(dialog().focusOwner)
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(java.awt.event.KeyEvent(target,java.awt.event.KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,code,java.awt.event.KeyEvent.CHAR_UNDEFINED,java.awt.event.KeyEvent.KEY_LOCATION_STANDARD))
    }
    val steps=listOf<()->Unit>(
        { pose=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS);context() },{ menu(2) },
        { check(c.renamingSketchId==first.id);selectWindowText(dialog()) },
        { typeWindow(dialog(),"Q") },{ key(java.awt.event.KeyEvent.VK_ENTER) },
        { check(c.renamingSketchId==null);check(c.document.sketches.first().name=="Q");context() },
        { menu(3) },
        { check(c.document.sketches.size==original.sketches.size+1);check(c.document.sketches.last().name=="Q Kopie");check(c.document.sketches.last().depth==null);c.undo() },
        { check(c.document.sketches.size==original.sketches.size);c.undo() },
        { check(c.document==original);c.redo() },
        { check(c.document.sketches.first().name=="Q");c.redo() },
        { check(c.document.sketches.size==original.sketches.size+1);c.undo() },
        { c.undo() },
        { check(c.document==original);context() },{ menu(2) },
        { selectWindowText(dialog()) },{ typeWindow(dialog(),"X") },{ key(java.awt.event.KeyEvent.VK_ESCAPE) },
        { check(c.renamingSketchId==null && c.document==original)
          check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==pose) { "Camera changed: $pose -> ${c.viewport.framingSignature().get()}" }
          println("SKETCH_ACTIONS_OK contextMenu=true rename=true duplicate=true profileOnly=true undo=true redo=true cancel=true cameraStable=true");quit() }
    )
    var index=0
    Timer(500) { event -> if(index==steps.size) (event.source as Timer).stop() else steps[index++]() }.apply { initialDelay=800;start() }
}

private fun controlsSmoke(c: ViewerController,window: java.awt.Window,output: java.io.File,quit: ()->Unit) {
    val original=c.document
    var frameBefore=emptyList<Double>()
    val first=c.document.sketches.first()
    fun click(label: String) {
        val r=checkNotNull(controlTargets[label]) { "Missing control: $label" }
        clickWindow(window,r.x+r.width/2,r.y+r.height/2)
    }
    val steps=listOf<()->Unit>(
        { c.performanceVisible=true },
        { check(c.performance?.fps?.let { it>0 }==true);check(c.document==original);c.performanceVisible=false },
        { check(c.performance==null);c.sidebar=false
          Timer(65) { e -> c.sidebar=true;(e.source as Timer).stop() }.apply { isRepeats=false;start() } },
        { check(c.sidebar && c.document==original);click("Seitenleiste") },
        { check(!c.sidebar);c.viewport.assertGlassRegionAbsent("left").get(5,java.util.concurrent.TimeUnit.SECONDS);click("Seitenleiste") },
        { check(c.sidebar && !c.wireframe);click("row:Skizzen") },
        { check(!c.sketchesExpanded && c.document==original);click("row:Objekte") },
        { check(!c.objectsExpanded && c.document==original);click("row:Skizzen") },
        { check(c.sketchesExpanded);click("row:Objekte") },
        { check(c.objectsExpanded && c.document==original);click("Raster") },
        { check(!c.grid);click("Raster") },
        { check(c.grid);click("Alle Objekte ein- oder ausblenden") },
        { check(!c.visible);click("Alle Objekte ein- oder ausblenden") },
        { check(c.visible);frameBefore=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
          wheelWindow(window,100,500)
        },
        { check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==frameBefore) { "Scrolling the sidebar moved the model" }
          dragWindow(window,100,window.height-40,800,500)
        },
        { check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==frameBefore) { "Dragging from the sidebar moved the model" }
          dragWindow(window,800,500,100,window.height-40)
        },
        { val actual=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
          check(actual!=frameBefore) { "Model drag stopped at the sidebar" }
          check(kotlin.math.abs(actual[3]-((frameBefore[3]-700*.45)%360))<.001) { "Primary drag did not orbit horizontally: $actual" }
          check(kotlin.math.abs(actual[4]-(frameBefore[4]+(window.height-540)*.45).coerceIn(-89.0,89.0))<.001) { "Primary drag vertical direction incorrect" }
          frameBefore=actual;wheelWindow(window,800,500) },
        { val actual=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
          check(actual[6]<frameBefore[6] && actual[3]==frameBefore[3]) { "Two-finger slide direction changed" };c.fit() },
        { click("Informationen") },
        { check(c.inspector && !c.showParameters);click("Schließen") },
        { check(!c.inspector);click("Parameter") },
        { check(c.inspector && c.showParameters);c.setParameter("prüfung","12 mm") },
        { click("parameter:prüfung") },
        { check(c.parameterEditor);check(controlTargets["Werte und Formeln"]==null)
          val dialog=java.awt.Window.getWindows().first { it.isVisible && it!==window && it is java.awt.Dialog }
          dialog.requestFocus()
          val target=dialog.focusOwner ?: dialog
          for(id in listOf(java.awt.event.KeyEvent.KEY_PRESSED,java.awt.event.KeyEvent.KEY_RELEASED)) java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(target,java.awt.event.KeyEvent(target,id,System.currentTimeMillis(),0,java.awt.event.KeyEvent.VK_ESCAPE,java.awt.event.KeyEvent.CHAR_UNDEFINED)) },
        { check(!c.parameterEditor);click("prüfung löschen") },
        { check(c.parameters.none { it.name=="prüfung" });check(!c.parameterEditor) { "Delete opened editor" };c.undo() },
        { check(c.parameters.any { it.name=="prüfung" });c.undo() },
        { check(c.document==original);click("Schließen") },
        { check(!c.inspector);click("Neue Skizze") },
        { check(c.selectingSupport);click("XY") },
        { check(c.sketchEditor && !c.extruding);click("Kreis") },
        { check(c.sketchTool==SketchDrawingTool.CIRCLE);click("field:Name") },
        { selectWindowText(window) },
        { typeWindow(window,"Q") },
        { check(c.sketchDraft?.name=="Q") { "Typing did not reach the sketch name: ${c.sketchDraft?.name}" }
          check(c.document==original);click("Abbrechen")
        },
        { check(!c.sketchEditor);click("sketch:${first.name}") },
        { check(c.selectedSketchId==first.id);click("Aus gewählter Skizze extrudieren") },
        { check(c.extruding);click("Abbrechen") },
        { check(!c.sketchEditor && c.document==original);clickWindow(window,window.width-72,166) },
        { val pose=c.viewport.cameraPose().get(5,java.util.concurrent.TimeUnit.SECONDS)
          check(pose.yaw != -35.0 || pose.pitch != 25.0) { "View cube did not receive a native click" }
          c.view("Iso")
        },
        { check(c.document==original)
          output.parentFile.mkdirs();ImageIO.write(captureCadWindow(window,c.viewport),"png",output)
          println("CONTROLS_OK nativeHitTesting=true sidebar=true viewTools=true visibility=true parameter=true info=true plane=true circle=true cancel=true extrusion=true cube=true sidebarScrollSafe=true dragCapture=true typing=true")
          quit()
        }
    )
    var index=0
    Timer(450) { timer ->
        if(index==steps.size) (timer.source as Timer).stop() else steps[index++]()
    }.apply { initialDelay=800;start() }
}

private fun bodyPositionSmoke(c:ViewerController,quit:()->Unit) {
    fun failed(error:Throwable) { error.printStackTrace();kotlin.system.exitProcess(1) }
    val feature=PrimitiveFeature("position-smoke","Positionstest",PrimitiveKind.BOX,width="20 mm",depth="16 mm",height="12 mm")
    c.createPrimitive(feature).whenComplete { _,createError -> onUi {
        if(createError!=null) { failed(createError);return@onUi }
        val beforeMove=c.document
        try { c.startBodyPosition(feature.id) } catch(error:Throwable) { failed(error);return@onUi }
        Timer(500) { event ->
            (event.source as Timer).stop()
            bodyPositionDragSmoke(c).whenComplete { _,dragError -> onUi {
                if(dragError!=null) { failed(dragError);return@onUi }
                c.finishBodyPosition().whenComplete { _,finishError -> onUi {
                    if(finishError!=null) { failed(finishError);return@onUi }
                    try {
                        val moved=c.document.effectiveFeatures().first { it.id==feature.id } as PrimitiveFeature
                        check(kotlin.math.abs(ParameterEvaluator.expression(moved.x,c.parameterValues).value-12.0)<.2) { "X handle did not commit its drag" }
                        c.undo().whenComplete { undone,undoError -> onUi {
                            if(undoError!=null) { failed(undoError);return@onUi }
                            if(c.document!=beforeMove) { failed(IllegalStateException("The confirmed move was not one undo step"));return@onUi }
                            c.redo().whenComplete { _,redoError -> onUi {
                                if(redoError!=null) { failed(redoError);return@onUi }
                                try {
                                    check(c.document.effectiveFeatures().any { it.id==feature.id })
                                    println("BODY_POSITION_OK nativeAxisDrag=true previewOnly=true singleUndo=true redo=true")
                                    quit()
                                } catch(error:Throwable) { failed(error) }
                            } }
                        } }
                    } catch(error:Throwable) { failed(error) }
                } }
            } }
        }.apply { isRepeats=false;start() }
    } }
}

private fun bodyPositionDragSmoke(c:ViewerController):java.util.concurrent.CompletableFuture<Void> = java.util.concurrent.CompletableFuture.runAsync {
    val camera=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
    val document=c.document;val draft=checkNotNull(c.bodyPositionDraft)
    val points=c.viewport.positionGizmoDragTestPoints("X",12.0).get(5,java.util.concurrent.TimeUnit.SECONDS)
    fun dispatch(id:Int,point:SketchPoint,down:Boolean) {
        javax.swing.SwingUtilities.invokeAndWait {
            val target=c.viewport.panel
            target.dispatchEvent(java.awt.event.MouseEvent(target,id,System.currentTimeMillis(),if(down) java.awt.event.InputEvent.BUTTON1_DOWN_MASK else 0,
                point.u.toInt(),point.v.toInt(),1,false,if(id==java.awt.event.MouseEvent.MOUSE_DRAGGED) java.awt.event.MouseEvent.NOBUTTON else java.awt.event.MouseEvent.BUTTON1))
        }
    }
    dispatch(java.awt.event.MouseEvent.MOUSE_PRESSED,points[0],true)
    for(step in 1..14) {
        dispatch(java.awt.event.MouseEvent.MOUSE_DRAGGED,SketchPoint(points[0].u+(points[1].u-points[0].u)*step/14,points[0].v+(points[1].v-points[0].v)*step/14),true)
        Thread.sleep(20)
    }
    dispatch(java.awt.event.MouseEvent.MOUSE_RELEASED,points[1],false)
    Thread.sleep(250)
    javax.swing.SwingUtilities.invokeAndWait {
        val updated=checkNotNull(c.bodyPositionDraft)
        check(updated.x!=draft.x) { "The native X handle did not update the move draft" }
        check(c.document==document) { "Dragging committed a body-position edit before confirmation" }
    }
    check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==camera) { "Body move changed the camera" }
}

private fun windowTarget(window: java.awt.Window,x: Int,y: Int): java.awt.Component {
    val content=(window as javax.swing.RootPaneContainer).contentPane
    val at=javax.swing.SwingUtilities.convertPoint(window,x,y,content)
    return checkNotNull(javax.swing.SwingUtilities.getDeepestComponentAt(content,at.x,at.y))
}
private fun wheelWindow(window: java.awt.Window,x: Int,y: Int) {
    val target=windowTarget(window,x,y);val p=javax.swing.SwingUtilities.convertPoint(window,x,y,target)
    target.dispatchEvent(java.awt.event.MouseWheelEvent(target,java.awt.event.MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,p.x,p.y,0,false,java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,3,3))
}
private fun dragWindow(window: java.awt.Window,x: Int,y: Int,endX: Int,endY: Int) {
    val target=windowTarget(window,x,y)
    for((id,point) in listOf(java.awt.event.MouseEvent.MOUSE_PRESSED to java.awt.Point(x,y),java.awt.event.MouseEvent.MOUSE_DRAGGED to java.awt.Point(endX,endY),java.awt.event.MouseEvent.MOUSE_RELEASED to java.awt.Point(endX,endY))) {
        val p=javax.swing.SwingUtilities.convertPoint(window,point,target)
        val drag=id==java.awt.event.MouseEvent.MOUSE_DRAGGED
        target.dispatchEvent(java.awt.event.MouseEvent(target,id,System.currentTimeMillis(),if(id==java.awt.event.MouseEvent.MOUSE_RELEASED) 0 else java.awt.event.InputEvent.BUTTON1_DOWN_MASK,p.x,p.y,1,false,if(drag) java.awt.event.MouseEvent.NOBUTTON else java.awt.event.MouseEvent.BUTTON1))
    }
}
private fun selectWindowText(window: java.awt.Window) {
    pressWindowKeyWithRobot(java.awt.event.KeyEvent.VK_A,java.awt.event.InputEvent.META_DOWN_MASK)
 }
private fun typeWindow(window: java.awt.Window,text: String,delay:Int=0) {
    val robot=java.awt.Robot().apply { autoDelay=delay }
    for(char in text) {
        val shifted=char.isUpperCase()
        if(shifted) robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT)
        val key=java.awt.event.KeyEvent.getExtendedKeyCodeForChar(char.uppercaseChar().code)
        check(key!=java.awt.event.KeyEvent.VK_UNDEFINED) { "Unsupported smoke-test character: $char" }
        robot.keyPress(key);robot.keyRelease(key)
        if(shifted) robot.keyRelease(java.awt.event.KeyEvent.VK_SHIFT)
    }
}

private fun pressWindowKeyWithRobot(keyCode:Int,modifiers:Int=0) {
    val robot=java.awt.Robot()
    val modifierKeys=listOf(
        java.awt.event.InputEvent.META_DOWN_MASK to java.awt.event.KeyEvent.VK_META,
        java.awt.event.InputEvent.CTRL_DOWN_MASK to java.awt.event.KeyEvent.VK_CONTROL,
        java.awt.event.InputEvent.ALT_DOWN_MASK to java.awt.event.KeyEvent.VK_ALT,
        java.awt.event.InputEvent.SHIFT_DOWN_MASK to java.awt.event.KeyEvent.VK_SHIFT
    ).filter { (mask,_) -> modifiers and mask != 0 }.map { it.second }
    modifierKeys.forEach(robot::keyPress)
    robot.keyPress(keyCode);robot.keyRelease(keyCode)
    modifierKeys.asReversed().forEach(robot::keyRelease)
}

private fun clickWindow(window: java.awt.Window,x: Int,y: Int) {
    fun canvas(component: java.awt.Component): java.awt.Component? {
        if(component is org.jetbrains.skiko.SkiaLayer) return component.canvas
        return (component as? java.awt.Container)?.components?.firstNotNullOfOrNull { canvas(it) }
    }
    val content=(window as javax.swing.RootPaneContainer).contentPane
    val at=javax.swing.SwingUtilities.convertPoint(window,x,y,content)
    val target=javax.swing.SwingUtilities.getDeepestComponentAt(content,at.x,at.y) ?: checkNotNull(canvas(window))
    val point=javax.swing.SwingUtilities.convertPoint(window,x,y,target)
    for(id in listOf(java.awt.event.MouseEvent.MOUSE_PRESSED,java.awt.event.MouseEvent.MOUSE_RELEASED)) {
        target.dispatchEvent(java.awt.event.MouseEvent(target,id,System.currentTimeMillis(),0,point.x,point.y,1,false,java.awt.event.MouseEvent.BUTTON1))
    }
}

private fun clickWindowWithRobot(window:java.awt.Window,x:Int,y:Int) {
    val origin=window.locationOnScreen
    val robot=java.awt.Robot().apply { autoDelay=30 }
    robot.mouseMove(origin.x+x,origin.y+y)
    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
}

private fun captureCadWindow(window: java.awt.Window,viewport: Viewport3D): java.awt.image.BufferedImage {
    fun layers(component: java.awt.Component): Sequence<org.jetbrains.skiko.SkiaLayer> = sequence {
        if(component is org.jetbrains.skiko.SkiaLayer) yield(component)
        if(component is java.awt.Container) component.components.forEach { yieldAll(layers(it)) }
    }
    val layer=layers(window).firstOrNull() ?: error("Missing Compose renderer")
    val bitmap=layer.screenshot() ?: error("Missing rendered window frame")
    val chrome=bitmap.use { org.jetbrains.skia.Image.makeFromBitmap(it).use { image ->
        image.encodeToData()!!.use { data -> ImageIO.read(java.io.ByteArrayInputStream(data.bytes)) }
    } }
    val scene=ImageIO.read(java.io.ByteArrayInputStream(viewport.preview(4096).get(10,java.util.concurrent.TimeUnit.SECONDS).png))
    val combined=java.awt.image.BufferedImage(chrome.width,chrome.height,java.awt.image.BufferedImage.TYPE_INT_ARGB)
    combined.createGraphics().apply {
        drawImage(scene,0,0,chrome.width,chrome.height,null)
        drawImage(chrome,0,0,null)
        dispose()
    }
    return combined
}

private fun workflowSmoke(c: ViewerController,window: java.awt.Window,output: java.io.File,quit: ()->Unit) {
    fun failed(error: Throwable) { error.printStackTrace();kotlin.system.exitProcess(1) }
    val before=c.document
    val profile=Sketch(id="workflow-profile",name="Workflow-Profil",width="24 mm",height="16 mm")
    c.setSketch(profile).whenComplete { _,error -> onUi {
        if(error!=null) { failed(error);return@onUi }
        try {
            check(c.document.sketches.last().depth==null)
            val flat=c.document
            c.selectSketch(profile.id);c.startExtrusion(profile.id)
            check(c.extruding && c.sketchEditor && c.document==flat)
            c.cancelSketch();check(c.document==flat)
            c.startExtrusion(profile.id)
            Timer(500) {
                dragExtrusionSmoke(c).thenCompose { c.viewport.extrusionControlsCheck() }.whenComplete { _,controlError -> onUi {
                    try {
                        check(controlError==null) { controlError.toString() }
                        check(c.document==flat && c.sketchDraft!!.depth=="18 mm")
                        c.finishSketch().whenComplete { _,finishError -> onUi {
                            try {
                                check(finishError==null)
                                check(!c.sketchEditor && !c.extruding)
                                check(c.document.primaryExtrusion(c.document.sketches.last().id)?.depth=="18 mm" && !c.document.sketches.last().visible)
                                check(c.document.bodyMeshes().size==before.bodyMeshes().size+1)
                                Timer(300) {
                                    try {
                                        output.parentFile?.mkdirs();ImageIO.write(captureCadWindow(window,c.viewport),"png",output)
                                        c.undo().whenComplete { _,undoError -> onUi {
                                            try {
                                                check(undoError==null && c.document==flat)
                                                c.undo().whenComplete { _,lastError -> onUi {
                                                    try { check(lastError==null && c.document==before);println("SEPARATE_EXTRUSION_OK flatSketch=true cancel=true numericDepth=true objectCreated=true sketchHidden=true undo=true");quit() }
                                                    catch(e:Throwable) { failed(e) }
                                                } }
                                            } catch(e:Throwable) { failed(e) }
                                        } }
                                    } catch(e:Throwable) { failed(e) }
                                }.apply { isRepeats=false;start() }
                            } catch(e:Throwable) { failed(e) }
                        } }
                    } catch(e:Throwable) { failed(e) }
                } }
            }.apply { isRepeats=false;start() }
        } catch(e:Throwable) { failed(e) }
    } }
}

private fun multiRegionExtrusionSmoke(c:ViewerController,window:java.awt.Window,output:java.io.File,quit:()->Unit) {
    fun fail(error:Throwable) { error.printStackTrace();kotlin.system.exitProcess(1) }
    val before=c.document
    val source=Sketch(id="multi-region-smoke",name="Mehrregionen-Test").withElements(listOf(
        SketchElement(id="plate",width="40 mm",height="30 mm"),
        SketchElement(id="opening",shape=SketchShape.CIRCLE,u="20 mm",v="15 mm",width="5 mm"),
        SketchElement(id="boss",shape=SketchShape.CIRCLE,u="60 mm",v="10 mm",width="4 mm")
    ))
    c.setSketch(source).whenComplete { _,setError -> onUi {
        if(setError!=null) { fail(setError);return@onUi }
        try {
            check(c.document.sketches.single { it.id==source.id }.regions(c.parameterValues).first().holeElementIds==listOf("opening"))
            c.startExtrusion(source.id)
            check(c.extrusionRegionChooserSketchId==source.id && !c.sketchEditor)
            c.startExtrusion(source.id,"plate")
            check(c.sketchEditor && c.extruding && c.extrusionRegionId=="plate")
            c.sketchDraft=c.sketchDraft!!.copy(depth="5 mm")
            c.finishSketch().whenComplete { _,firstError -> onUi {
                if(firstError!=null) { fail(firstError);return@onUi }
                try {
                    check(c.document.effectiveFeatures().filterIsInstance<ExtrusionFeature>().single().depth=="5 mm")
                    c.startExtrusion(source.id)
                    check(c.extrusionRegionChooserSketchId==source.id)
                    c.startExtrusion(source.id,"boss")
                    c.sketchDraft=c.sketchDraft!!.copy(depth="12 mm")
                    c.finishSketch().whenComplete { _,secondError -> onUi {
                        if(secondError!=null) { fail(secondError);return@onUi }
                        try {
                            val features=c.document.effectiveFeatures().filterIsInstance<ExtrusionFeature>().associateBy { it.region.regionId }
                            check(features.keys==setOf("plate","boss") && features.getValue("plate").depth=="5 mm" && features.getValue("boss").depth=="12 mm")
                            check(c.document.sketches.single { it.id==source.id }.elements.size==3)
                            check(c.document.bodyMeshes().map { it.bounds.size.z }==listOf(5.0,12.0))
                            c.document.replaceSketch(source.withElements(source.elements.map { if(it.id=="plate") it.copy(width="50 mm") else it }),false).bodyMeshes()
                            output.parentFile?.mkdirs();ImageIO.write(captureCadWindow(window,c.viewport),"png",output)
                            c.undo().whenComplete { _,undoSecond -> onUi {
                                if(undoSecond!=null) { fail(undoSecond);return@onUi }
                                c.undo().whenComplete { _,undoFirst -> onUi {
                                    if(undoFirst!=null) { fail(undoFirst);return@onUi }
                                    c.undo().whenComplete { _,undoSource -> onUi {
                                        if(undoSource!=null) { fail(undoSource);return@onUi }
                                        try { check(c.document==before);println("MULTI_REGION_EXTRUSION_OK chooser=true holes=true distinctDepths=true sourceEditValid=true undo=true");quit() }
                                        catch(e:Throwable) { fail(e) }
                                    } }
                                } }
                            } }
                        } catch(e:Throwable) { fail(e) }
                    } }
                } catch(e:Throwable) { fail(e) }
            } }
        } catch(e:Throwable) { fail(e) }
    } }
}

private fun dragExtrusionSmoke(c: ViewerController): java.util.concurrent.CompletableFuture<Void> = java.util.concurrent.CompletableFuture.runAsync {
    val camera=c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)
    val document=c.document
    fun event(id: Int,point: SketchPoint,down: Boolean) {
        javax.swing.SwingUtilities.invokeAndWait {
            val target=c.viewport.panel
            target.dispatchEvent(java.awt.event.MouseEvent(target,id,System.currentTimeMillis(),if(down) java.awt.event.InputEvent.BUTTON1_DOWN_MASK else 0,
                point.u.toInt(),point.v.toInt(),1,false,if(id==java.awt.event.MouseEvent.MOUSE_DRAGGED) 0 else 1))
        }
    }
    for(delta in listOf(10.0,-35.0)) {
        val points=c.viewport.extrusionDragTestPoints(delta).get(5,java.util.concurrent.TimeUnit.SECONDS)
        event(java.awt.event.MouseEvent.MOUSE_PRESSED,points[0],true)
        for(step in 1..14) {
            event(java.awt.event.MouseEvent.MOUSE_DRAGGED,SketchPoint(points[0].u+(points[1].u-points[0].u)*step/14,points[0].v+(points[1].v-points[0].v)*step/14),true)
            Thread.sleep(20)
        }
        event(java.awt.event.MouseEvent.MOUSE_RELEASED,points[1],false)
        Thread.sleep(250)
        javax.swing.SwingUtilities.invokeAndWait {
            val depth=ParameterEvaluator.expression(c.sketchDraft!!.depth!!,c.parameterValues).value
            check(if(delta>0) depth>15 else depth<0) { "Extrusion handle did not change the signed depth: $depth" }
            check(c.document==document) { "Dragging committed the draft" }
        }
        check(c.viewport.framingSignature().get(5,java.util.concurrent.TimeUnit.SECONDS)==camera) { "Dragging moved the camera" }
    }
    println("EXTRUSION_DRAG_OK increase=true reverse=true documentUnchanged=true cameraStable=true")
}

@Composable private fun ObjectColorDialog(c:ViewerController) {
    val id=c.colorEditorBody
    var hex by remember { mutableStateOf(if(id==null) "#4F86E8" else c.document.bodyColors[id]?.color ?: DEFAULT_BODY_COLOR) }
    var name by remember { mutableStateOf("Farbbereich") }
    var problem by remember { mutableStateOf<String?>(null) }
    var editingArea by remember { mutableStateOf<String?>(null) }
    fun applyColor() {
        runCatching {
            val color=checkedColor(hex)
            val area=id?.let { c.document.bodyColors[it]?.areas?.firstOrNull { a -> a.id==editingArea } }
            val result=if(id==null) c.colorSelectedFaces(color,name) else if(area!=null) c.setAreaColor(id,area.copy(name=name,color=color)) else c.setObjectColor(id,color)
            result.whenComplete { _,failure -> onUi { if(failure==null) c.colorEditorOpen=false else problem=failure.message } }
        }.onFailure { problem=it.message }
    }
    DialogWindow(onCloseRequest={c.colorEditorOpen=false},title=if(id==null) "Flächenfarbe" else "Farbe und Bereiche",resizable=false,state=rememberDialogState(width=430.dp,height=490.dp)) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(id==null) "Ausgewählte Flächen" else if(editingArea!=null) "Bereich bearbeiten" else "Objektfarbe",color=Ink,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("#4F86E8","#E76F51","#E9C46A","#52B788","#9B72CF","#E78AC3","#F2F2F2","#444444").forEach { color ->
                    Box(Modifier.size(34.dp).background(Color(0xff000000L or color.drop(1).toLong(16)),RoundedCornerShape(6.dp)).clickable { hex=color })
                }
            }
            OutlinedTextField(hex,{hex=it},label={Text("Farbe · #RRGGBB")},singleLine=true,colors=TextFieldDefaults.outlinedTextFieldColors(textColor=Ink,cursorColor=Accent,focusedBorderColor=Accent,unfocusedBorderColor=Muted,focusedLabelColor=Accent,unfocusedLabelColor=Muted),modifier=Modifier.fillMaxWidth())
            if(id==null || editingArea!=null) OutlinedTextField(name,{name=it},label={Text("Bereichsname")},singleLine=true,colors=TextFieldDefaults.outlinedTextFieldColors(textColor=Ink,cursorColor=Accent,focusedBorderColor=Accent,unfocusedBorderColor=Muted,focusedLabelColor=Accent,unfocusedLabelColor=Muted),modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MacButton("Übernehmen",primary=true) { applyColor() }
                if(id!=null && editingArea==null) MacButton("Standardfarbe") {
                    runCatching { c.setObjectColor(id,null).whenComplete { _,failure -> onUi { if(failure==null) c.colorEditorOpen=false else problem=failure.message } } }.onFailure { problem=it.message }
                }
                MacButton("Schließen") { c.colorEditorOpen=false }
            }
            problem?.let { Text(it,color=Color(0xffc9342c),fontSize=12.sp) }
            if(id!=null) {
                val body=c.document.sceneBodies().firstOrNull { it.id==id }
                val stale=body?.let { c.document.colorInfo(it)["staleAreaIds"]?.jsonArray?.map { v -> v.jsonPrimitive.content } } ?: emptyList()
                Text("Farbbereiche",color=Ink,fontWeight=FontWeight.Medium)
                if(c.document.bodyColors[id]?.areas.isNullOrEmpty()) Text("Flächen am Modell wählen und ›Flächenfarbe‹ öffnen. Die KI kann auch räumliche Bereiche einfärben.",color=Muted,fontSize=12.sp)
                c.document.bodyColors[id]?.areas?.forEach { area ->
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(area.name+(if(area.id in stale) " · neu zuweisen" else ""),modifier=Modifier.weight(1f),color=if(area.id in stale) Color(0xffc9342c) else Ink,fontSize=12.sp)
                        if(area.id !in stale) MacButton("Ändern") { editingArea=area.id;name=area.name;hex=area.color }
                        MacButton("Entfernen") { runCatching { c.removeAreaColor(id,area.id) }.onFailure { problem=it.message } }
                    }
                }
            }
            Text("Anzeige-Farben werden im Projekt gespeichert. STL/OBJ-Exporte enthalten weiterhin nur Geometrie.",color=Muted,fontSize=11.sp)
        }
    }
}

@Composable private fun InstanceDialog(c:ViewerController) {
    var draft by remember { mutableStateOf(c.instanceDraft!!) }
    var fields by remember { mutableStateOf(mapOf("name" to draft.name,"x" to draft.x,"y" to draft.y,"z" to draft.z,"rotationX" to draft.rotationX,"rotationY" to draft.rotationY,"rotationZ" to draft.rotationZ)) }
    var problem by remember { mutableStateOf<String?>(null) }
    DialogWindow(onCloseRequest={c.instanceDraft=null},title="Verknüpfte Instanz",resizable=false,state=rememberDialogState(width=430.dp,height=550.dp)) {
        Column(Modifier.fillMaxSize().background(Chrome).padding(22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Gemeinsames Bauteil, eigene Platzierung",color=Ink,fontSize=16.sp,fontWeight=FontWeight.Medium)
            Text("Änderungen am Quellbauteil aktualisieren diese Instanz.",color=Muted,fontSize=12.sp)
            FormField("Name",fields.getValue("name"),changed={fields=fields+("name" to it)})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { FormField("X",fields.getValue("x"),changed={fields=fields+("x" to it)}) }
                Box(Modifier.weight(1f)) { FormField("Y",fields.getValue("y"),changed={fields=fields+("y" to it)}) }
                Box(Modifier.weight(1f)) { FormField("Z",fields.getValue("z"),changed={fields=fields+("z" to it)}) }
            }
            FormField("Drehung X",fields.getValue("rotationX"),changed={fields=fields+("rotationX" to it)})
            FormField("Drehung Y",fields.getValue("rotationY"),changed={fields=fields+("rotationY" to it)})
            FormField("Drehung Z",fields.getValue("rotationZ"),changed={fields=fields+("rotationZ" to it)})
            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(draft.inheritColors,{draft=draft.copy(inheritColors=it)});Text("Farben vom Quellbauteil übernehmen",color=Ink,fontSize=12.sp) }
            problem?.let { Text(it,color=Color(0xffc9342c),fontSize=12.sp) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MacButton("Abbrechen") { c.instanceDraft=null }
                MacButton("Übernehmen",primary=true) { runCatching { c.storeInstance(draft.copy(name=fields.getValue("name"),x=fields.getValue("x"),y=fields.getValue("y"),z=fields.getValue("z"),rotationX=fields.getValue("rotationX"),rotationY=fields.getValue("rotationY"),rotationZ=fields.getValue("rotationZ"))) }.onFailure { problem=it.message } }
            }
        }
    }
}
