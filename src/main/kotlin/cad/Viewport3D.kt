package cad

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.image.WritableImage
import javafx.embed.swing.SwingFXUtils
import javafx.scene.transform.Scale
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.*
import javafx.scene.input.MouseButton
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.PhongMaterial
import javafx.scene.shape.*
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.transform.Rotate
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities
import kotlin.math.*

/** All scene-graph state is owned by the FX thread; public commands may come from Compose/EDT. */
class Viewport3D(private val onSelection: (Boolean) -> Unit, private val onOrientation: (String) -> Unit, private val onFacePicked: (Int?,Boolean) -> Unit = { _,_-> }, private val onPlanePicked: (SketchPlane) -> Unit = {}, private val onSketchDrawn: (Sketch) -> Unit = {}, private val onDrawingError: (String) -> Unit = {}, private val onViewportFocus: () -> Unit = {}, private val onRegionPicked:(RegionReference,Boolean)->Unit = { _,_-> }, private val onChatPointPicked:(Int,Vec3,Boolean)->Unit = { _,_,_-> }) {
    private val ready = CompletableFuture<Unit>()
    val panel: JFXPanel = ViewportPanel()
    private val state = CameraState()
    private val contextMenus=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun captureContextMenu(id: String,open: Boolean) { if(open) contextMenus.add(id) else contextMenus.remove(id) }
    private val glassRegions=java.util.concurrent.ConcurrentHashMap<String,DoubleArray>()
    private lateinit var backdrop: SceneBackdrop
    private var cameraPulse: javafx.animation.AnimationTimer?=null
    private var diagnostics: javafx.animation.AnimationTimer?=null
    internal fun setDiagnostics(enabled: Boolean,bodies: Int,update: (PerformanceSnapshot)->Unit)=command {
        diagnostics?.stop();diagnostics=null
        if(enabled) {
            val metrics=PulseMetrics();var published=0L
            diagnostics=object: javafx.animation.AnimationTimer() {
                override fun handle(now: Long) {
                    metrics.pulse(now)
                    if(now-published>=250_000_000L) {
                        published=now
                        val runtime=Runtime.getRuntime()
                        val sample=metrics.snapshot(if(meshGroup.isVisible) persistedGeometry?.triangleCount ?: 0 else 0,
                            if(meshGroup.isVisible) bodies else 0,(runtime.totalMemory()-runtime.freeMemory())/(1024*1024))
                        onUi { update(sample) }
                    }
                }
            }.also { it.start() }
        }
    }
    private var cameraUpdateQueued=false
    private var darkMode=false
    private var gridFloor=70.0
    fun routeOverlayInput(window: java.awt.Window) {
        fun findCanvas(component: java.awt.Component): java.awt.Component? {
            if(component is org.jetbrains.skiko.SkiaLayer) return component.canvas
            return (component as? java.awt.Container)?.components?.firstNotNullOfOrNull { findCanvas(it) }
        }
        val routing=OverlayPointerRouting()
        var delivering=false
        fun deliver(canvas: java.awt.Component,event: java.awt.event.MouseEvent) {
            delivering=true
            try { canvas.dispatchEvent(SwingUtilities.convertMouseEvent(panel,event,canvas)) }
            finally { delivering=false }
        }
        (panel as ViewportPanel).routeOverlayEvent=route@{ event ->
            // Unhandled overlay events must not circle back through Swing into the model.
            if(delivering) { event.consume();return@route true }
            val point=SwingUtilities.convertPoint(panel,event.point,window)
            val inside=contextMenus.isNotEmpty() || glassRegions.values.any { r -> point.x>=r[0] && point.y>=r[1] && point.x<r[0]+r[2] && point.y<r[1]+r[3] }
            val delivery=routing.delivery(event.id,inside)
            if(delivery.exitOverlay) findCanvas(window)?.let { canvas ->
                val exit=java.awt.event.MouseEvent(panel,java.awt.event.MouseEvent.MOUSE_EXITED,event.`when`,event.modifiersEx,event.x,event.y,event.xOnScreen,event.yOnScreen,0,false,java.awt.event.MouseEvent.NOBUTTON)
                deliver(canvas,exit)
            }
            val forward=delivery.overlay
            val canvas=if(forward) findCanvas(window) else null
            if(canvas!=null) {
                if(event.id==java.awt.event.MouseEvent.MOUSE_PRESSED) canvas.requestFocusInWindow()
                deliver(canvas,event)
                event.consume();true
            } else false
        }
    }
    internal fun assertGlassRegionAbsent(key: String)=command { check(!glassRegions.containsKey(key)) { "Invisible panel still captures input: $key" } }
    fun setGlassRegion(key: String,bounds: DoubleArray?) {
        val changed=if(bounds==null) glassRegions.remove(key)!=null else {
            val different=glassRegions[key]?.contentEquals(bounds)!=true
            if(different) glassRegions[key]=bounds
            different
        }
        if(changed) ready.thenRun { Platform.runLater {
            backdrop.regions(glassRegions.values.toList())
            val margin=glassRegions["right"]?.let { view.width-it[0]+16.0 } ?: 20.0
            StackPane.setMargin(cubeOverlay,Insets(104.0,margin,20.0,20.0))
        } }
    }
    fun setDarkMode(dark: Boolean)=command {
        darkMode=dark
        view.fill=Color.web(if(dark) "#1b2028" else "#f8fafc")
        scene.fill=view.fill

        overlay.darkMode=dark
        makeGrid(gridFloor);refreshOverlay()
    }
    private lateinit var cubeOverlay: StackPane
    private lateinit var view: SubScene
    private lateinit var scene: Scene
    private val sketchGroup = Group()
    private val regionHighlights = Group().apply { isMouseTransparent=true }
    private var pickableRegions=emptyList<PickableRegion>()
    private var selectedRegions=emptySet<RegionReference>()
    internal fun setRegions(regions:List<PickableRegion>)=command { pickableRegions=regions;drawRegionHighlights() }
    internal fun highlightRegions(regions:Set<RegionReference>)=command { selectedRegions=regions;drawRegionHighlights() }
    private fun drawRegionHighlights() {
        regionHighlights.children.clear()
        pickableRegions.filter { it.reference in selectedRegions }.forEach { region ->
            (listOf(region.outer)+region.holes).forEach { loop ->
                loop.indices.forEach { i ->
                    val a=worldFromCad(region.frame.point(loop[i].u,loop[i].v))
                    val b=worldFromCad(region.frame.point(loop[(i+1)%loop.size].u,loop[(i+1)%loop.size].v))
                    val delta=b.subtract(a);val length=delta.magnitude()
                    if(length>1e-9) regionHighlights.children.add(Cylinder(1.8,length,8).apply {
                        material=PhongMaterial(Color.web("#ff9500"));translateX=(a.x+b.x)/2;translateY=(a.y+b.y)/2;translateZ=(a.z+b.z)/2
                        val axis=javafx.geometry.Point3D(0.0,1.0,0.0).crossProduct(delta)
                        if(axis.magnitude()>1e-9) transforms.add(Rotate(Math.toDegrees(acos((delta.y/length).coerceIn(-1.0,1.0))),axis))
                    })
                }
            }
        }
    }

    private val faceGroup = Group()
    private val analysisGroup=Group().apply { isMouseTransparent=true }
    private var assemblyPreviewPose:CameraPose?=null
    private val chatReferenceGroup=Group()
    private var chatMarking=false
    private val originPlanes = Group()
    private val planeGridLines=mutableListOf<Pair<SketchPoint,SketchPoint>>()
    private val overlay=SketchOverlay { candidate ->
        editingDraft=candidate;editingHasProfile=true;drawDraftNow(candidate)
        onUi { onSketchDrawn(candidate) }
    }
    private var clickAnchor: SketchPoint? = null
    private var dragHandle: Int? = null
    private var dragPointIndex: Int? = null
    private var selectedElementId: String? = null
    private var dragOriginal: Sketch? = null
    private var dragHadProfile=false
    private var snapGuides: List<SketchGuide> = emptyList()
    private var planeBoundary: List<Pair<SketchPoint,SketchPoint>> = emptyList()
    private var snapHint: Pair<SketchPoint,String>? = null
    private var planeCenter: SketchPoint? = null
    private val drawingGroup = Group().apply { isMouseTransparent=true }
    private val bodyPreviewGroup = Group().apply { isMouseTransparent=true }
    private val positionGizmo=Pane().apply { isPickOnBounds=false }
    private var positionGizmoOrigin:Vec3?=null
    private var positionGizmoDrag:((String,Double)->Unit)?=null
    private var positionGizmoDragging=false
    private var positionPreviewActive=false
    private var persistedGeometry: MeshData? = null
    private var previewContextGeometry: MeshData? = null
    private var contextOverridden = false
    private var previewBodyCount = 0
    private var previewGeometry: List<MeshData> = emptyList()
    private var editingDraft: Sketch? = null
    private var drawingValues: Map<String,Quantity> = emptyMap()
    private var editingHasProfile = false
    private var drawingSnap = true
    private var drawingCentered = false
    private var drawingTool = SketchDrawingTool.RECTANGLE
    private val pendingPoints=mutableListOf<SketchPoint>()
    private var extrusionOnly = false
    private var extrusionDrag: ExtrusionDrag? = null
    private var drawingStart: SketchPoint? = null
    private var drawingCandidate: Sketch? = null
    private var pressX = 0.0
    private var pressY = 0.0
    private var modelCenter = Vec3(0.0,0.0,0.0)
    private var modelScale = 2.4
    private var hasModel = false
    private lateinit var meshGroup: Group
    private lateinit var grid: Group
    private lateinit var world: Group
    private lateinit var pan: Group
    private lateinit var camera: PerspectiveCamera
    private lateinit var pitch: Rotate
    private lateinit var yaw: Rotate
    private lateinit var cubePitch: Rotate
    private lateinit var cubeYaw: Rotate
    private lateinit var emptyText: Text
    private var meshView: MeshView? = null
    private var wireframe = false
    private var gridVisible = true
    private var downX = 0.0
    private var downY = 0.0
    private var travel = 0.0
    private var pinching = false
    private var macGestureListener: Any? = null
    var nativePinchInstalled = false
        private set
    private var persistedColors:List<String> = emptyList()
    private var coloredMaterial:PhongMaterial? = null
    private val surface = PhongMaterial(Color.web("#a1afbe")).apply { specularColor = Color.web("#3b4755") }
    private val selectionSurface = PhongMaterial(Color.web("#6ca7ed"))

    init {
        // Swing's wheel events retain precise deltas; FX's embedded path can lose horizontal scroll.
        panel.addMouseWheelListener { e ->
            val amount = -e.preciseWheelRotation * 14.0
            command {
                if (!pinching) {
                    when {
                        e.isAltDown -> {
                            state.orbit(if (e.isShiftDown) amount else 0.0, if (e.isShiftDown) 0.0 else -amount)
                            onUi { onOrientation("") }
                        }
                        e.isMetaDown || e.isControlDown -> state.scrollZoom(amount)
                        e.isShiftDown -> panBy(amount, 0.0)
                        else -> panBy(0.0, amount)
                    }
                    queueCameraUpdate()
                }
            }
            e.consume()
        }
        installMacPinch()
        Platform.setImplicitExit(false)
        Platform.runLater {
            try { initialize(); ready.complete(Unit) }
            catch (error: Throwable) { ready.completeExceptionally(error) }
        }
    }

    private fun initialize() {
        meshGroup = Group()
        grid = Group()
        pitch = Rotate(state.pitch, Rotate.X_AXIS)
        yaw = Rotate(state.yaw, Rotate.Y_AXIS)
        world = Group(grid, meshGroup, sketchGroup, regionHighlights, faceGroup, analysisGroup, chatReferenceGroup, originPlanes, bodyPreviewGroup, drawingGroup).apply { transforms.addAll(pitch, yaw) }
        pan = Group(world)
        camera = PerspectiveCamera(true).apply { nearClip = 0.1; farClip = 100000.0; fieldOfView = 35.0 }
        val light = PointLight(Color.web("#c2cbd1")).apply {
            translateX = -350.0; translateY = -500.0; translateZ = -600.0
        }
        view = SubScene(Group(pan, AmbientLight(Color.web("#626d76")), light), 800.0, 650.0, true, SceneAntialiasing.BALANCED).apply {
            fill = Color.web("#f8fafc"); camera = this@Viewport3D.camera
        }
        backdrop=SceneBackdrop(view,camera)
        val root = StackPane(view,backdrop.pane,overlay.pane,positionGizmo)
        StackPane.setAlignment(positionGizmo,Pos.TOP_LEFT)
        positionGizmo.prefWidthProperty().bind(root.widthProperty())
        positionGizmo.prefHeightProperty().bind(root.heightProperty())
        view.widthProperty().bind(root.widthProperty())
        view.heightProperty().bind(root.heightProperty())
        view.widthProperty().addListener { _, _, _ -> updateCamera() }
        view.heightProperty().addListener { _, _, _ -> updateCamera() }
        emptyText = Text("Modell öffnen\nSTL, OBJ oder CAD-Projekt hierher ziehen").apply {
            fill = Color.web("#8e8e93"); font = Font.font("System", 14.0); isMouseTransparent = true
        }
        root.children.add(emptyText)
        val cube = StackPane(createViewCube()).apply {
            minWidth=104.0;prefWidth=104.0;maxWidth=104.0;minHeight=104.0;prefHeight=104.0;maxHeight=104.0

        }
        cubeOverlay=cube
        root.children.add(cube)
        root.widthProperty().addListener { _,_,_ -> drawPositionGizmo() }
        root.heightProperty().addListener { _,_,_ -> drawPositionGizmo() }
        StackPane.setAlignment(cube, Pos.TOP_RIGHT)
        StackPane.setMargin(cube, Insets(104.0,20.0,20.0,20.0))
        scene = Scene(root).apply { fill=Color.web("#eaf0f6") }
        panel.scene = scene
        cameraPulse=object: javafx.animation.AnimationTimer() {
            override fun handle(now: Long) {
                stop()
                if(cameraUpdateQueued) { cameraUpdateQueued=false;updateCamera() }
            }
        }
        setupInput()
        makeGrid(70.0)
        updateCamera()
    }

    private fun createViewCube(): SubScene {
        cubePitch = Rotate(state.pitch, Rotate.X_AXIS)
        cubeYaw = Rotate(state.yaw, Rotate.Y_AXIS)
        val cube = Group().apply { transforms.addAll(cubePitch, cubeYaw) }
        fun face(width: Double, height: Double, depth: Double, x: Double, y: Double, z: Double,
                 color: String, p: Double, a: Double) {
            cube.children.add(Box(width, height, depth).apply {
                translateX = x; translateY = y; translateZ = z
                material = PhongMaterial(Color.web(color))
                setOnMouseClicked { if(it.clickCount==2) setView(25.0,-35.0) else setView(p,a);it.consume() }
            })
        }
        face(44.0, 44.0, 0.5, 0.0, 0.0, -22.0, "#d6dce4", 0.0, 0.0)
        face(44.0, 44.0, 0.5, 0.0, 0.0, 22.0, "#b5c8cb", 0.0, 180.0)
        face(0.5, 44.0, 44.0, 22.0, 0.0, 0.0, "#8ba9af", 0.0, 90.0)
        face(0.5, 44.0, 44.0, -22.0, 0.0, 0.0, "#8ba9af", 0.0, -90.0)
        face(44.0, 0.5, 44.0, 0.0, -22.0, 0.0, "#e0ecee", 90.0, 0.0)
        face(44.0, 0.5, 44.0, 0.0, 22.0, 0.0, "#e0ecee", -90.0, 0.0)
        fun label(title: String,x: Double,y: Double,z: Double,axis: javafx.geometry.Point3D,angle: Double) {
            val text=Text(title).apply { font=Font.font("System",8.0);fill=Color.web("#3d5961");this.x=-layoutBounds.width/2;this.y=3.0 }
            cube.children.add(Group(text).apply { translateX=x;translateY=y;translateZ=z;rotationAxis=axis;rotate=angle;isMouseTransparent=true })
        }
        label("VORN",0.0,0.0,-22.6,Rotate.Y_AXIS,0.0)
        label("HINTEN",0.0,0.0,22.6,Rotate.Y_AXIS,180.0)
        label("RECHTS",22.6,0.0,0.0,Rotate.Y_AXIS,-90.0)
        label("LINKS",-22.6,0.0,0.0,Rotate.Y_AXIS,90.0)
        label("OBEN",0.0,-22.6,0.0,Rotate.X_AXIS,-90.0)
        label("UNTEN",0.0,22.6,0.0,Rotate.X_AXIS,90.0)
        val cubeCamera = PerspectiveCamera(true).apply {
            translateZ = -175.0; nearClip = 0.1; farClip = 1000.0
        }
        return SubScene(Group(cube, AmbientLight(Color.WHITE)), 94.0, 94.0, true, SceneAntialiasing.BALANCED).apply {
            camera = cubeCamera; fill = Color.TRANSPARENT
            val menu=javafx.scene.control.ContextMenu()
            listOf(Triple("Vorne",0.0,0.0),Triple("Hinten",0.0,180.0),Triple("Links",0.0,-90.0),Triple("Rechts",0.0,90.0),Triple("Oben",90.0,0.0),Triple("Unten",-90.0,0.0),Triple("Isometrisch",25.0,-35.0)).forEach { (name,p,y) ->
                menu.items.add(javafx.scene.control.MenuItem(name).apply { setOnAction { setView(p,y) } })
            }
            setOnContextMenuRequested { menu.show(this,it.screenX,it.screenY);it.consume() }
            javafx.scene.control.Tooltip.install(this,javafx.scene.control.Tooltip("Ansicht wählen · Rechtsklick für alle Ansichten · Doppelklick für Isometrie"))
            setOnMouseClicked { if (it.clickCount == 2) setView(25.0, -35.0) }
        }
    }

    private fun makeGrid(floor: Double) {
        gridFloor=floor
        grid.children.clear()
        grid.opacity = 1.0
        fun unlit(hex: String): PhongMaterial {
            val pixel = WritableImage(1, 1)
            pixel.pixelWriter.setColor(0, 0, Color.web(hex))
            return PhongMaterial(Color.BLACK).apply { selfIlluminationMap = pixel; specularColor = Color.BLACK }
        }
        val minor = unlit(if(darkMode) "#48576b" else "#9aa9b8")
        val major = unlit(if(darkMode) "#6f839b" else "#718598")
        for (i in -15..15) {
            if (i == 0) continue
            val material = if (i % 5 == 0) major else minor
            grid.children.add(Box(600.0, 0.18, 0.5).apply {
                translateY = floor; translateZ = i * 20.0; this.material = material
            })
            grid.children.add(Box(0.5, 0.18, 600.0).apply {
                translateY = floor; translateX = i * 20.0; this.material = material
            })
        }
        grid.children.add(Box(600.0, 0.25, 0.4).apply {
            translateY = floor; material = unlit("#b98077")
        })
        grid.children.add(Box(0.4, 0.25, 600.0).apply {
            translateY = floor; material = unlit("#789d86")
        })
        val batches=grid.children.filterIsInstance<Box>().groupBy { it.material }
        grid.children.setAll(batches.map { (material,boxes) ->
            val geometry=gridGeometry(boxes.map { GridBox(it.translateX,it.translateY,it.translateZ,it.width,it.height,it.depth) })
            MeshView(TriangleMesh().apply {
                points.setAll(*geometry.points);texCoords.setAll(0f,0f);faces.setAll(*geometry.faces)
                faceSmoothingGroups.setAll(*IntArray(geometry.faces.size/6))
            }).apply { this.material=material;isMouseTransparent=true }
        })
        grid.isVisible = gridVisible
    }

    private fun setupInput() {
        // AWT handles scrolling in the embedded viewport. Do not process it twice in FX.
        view.setOnScroll { it.consume() }
        view.setOnZoomStarted { pinching = true; it.consume() }
        view.setOnZoom { state.magnify(it.zoomFactor); queueCameraUpdate(); it.consume() }
        view.setOnZoomFinished { pinching = false; it.consume() }
        view.setOnMousePressed {
            onUi { onViewportFocus() }
            pressX=it.sceneX;pressY=it.sceneY
            downX=pressX;downY=pressY;travel=0.0
            drawingCandidate=null;drawingStart=null;dragHandle=null;dragPointIndex=null;dragOriginal=editingDraft;extrusionDrag=null
            dragHadProfile=editingHasProfile
            if(extrusionOnly && it.button==MouseButton.PRIMARY && !it.isAltDown && !it.isShiftDown) {
                val handle=extrusionHandlePoints()
                if(handle!=null && hypot(handle.second.u-pressX,handle.second.v-pressY)<14) {
                    val draft=editingDraft!!;val ray=cameraRay(pressX,pressY)
                    val origin=profileCenter(draft);val axis=draft.extrusionAxis()
                    val distance=axisDistance(origin,axis,ray.first,ray.second)
                    if(distance!=null) extrusionDrag=ExtrusionDrag(origin,axis,ParameterEvaluator.expression(draft.depth!!,drawingValues).value,distance)
                }
            }
            val activeDraft=editingDraft
            val activeProfile=activeDraft?.editableElements()?.singleOrNull()?.takeIf { toolMatches(it,drawingTool) }
            if(editingHasProfile && activeDraft!=null && activeProfile!=null && !extrusionOnly && drawingTool in profileTools) {
                dragHandle=runCatching { activeProfile.on(activeDraft).handles(drawingValues).mapNotNull { handle ->
                    projectSketch(handle.point)?.let { p -> handle.id to hypot(p.u-pressX,p.v-pressY) }
                }.filter { it.second<12 }.minByOrNull { it.second }?.first }.getOrNull()
            }
            if(activeDraft!=null && !extrusionOnly) dragPointIndex=nearSelectedPoint(activeDraft,pressX,pressY)
            if(editingDraft!=null && !extrusionOnly && drawingTool in profileTools && it.button==MouseButton.PRIMARY && !it.isAltDown && !it.isShiftDown)
                if(dragPointIndex==null) drawingStart=clickAnchor ?: screenOnSketch(pressX,pressY)?.let(::snapPoint)
            view.requestFocus()
        }
        view.setOnMouseDragged {
            travel=max(travel,hypot(it.sceneX-pressX,it.sceneY-pressY))
            // Trackpad clicks include tiny pointer movements. Keep those from moving the camera.
            if(travel<4.0) { it.consume();return@setOnMouseDragged }
            if(editingDraft!=null && dragPointIndex!=null && it.isPrimaryButtonDown && !it.isAltDown && !it.isShiftDown) {
                val screen=screenOnSketch(it.sceneX,it.sceneY)?.let(::snapPoint)
                val id=selectedElementId
                if(screen!=null && id!=null) runCatching {
                    val angle=editingDraft!!.rotation(drawingValues);val c=cos(angle);val s=sin(angle)
                    val u=screen.u*c+screen.v*s;val v=-screen.u*s+screen.v*c
                    (dragOriginal ?: editingDraft!!).moveConstrainedPoint(id,dragPointIndex!!,u,v,drawingValues)
                }.onSuccess { solution ->
                    if(solution.status!="conflicting") { drawingCandidate=solution.sketch;editingDraft=solution.sketch;drawDraftNow(solution.sketch);onUi { onSketchDrawn(solution.sketch) } }
                    else onUi { onDrawingError("Widersprüchliche Constraints: ${solution.conflicts.joinToString()}") }
                }.onFailure { onUi { onDrawingError(it.message ?: "Der Skizzenpunkt konnte nicht verschoben werden.") } }
                it.consume();return@setOnMouseDragged
            }
            if(extrusionOnly && it.isPrimaryButtonDown && !it.isAltDown && !it.isShiftDown) {
                extrusionDrag?.let { drag ->
                val ray=cameraRay(it.sceneX,it.sceneY)
                    drag.depth(ray.first,ray.second,it.isMetaDown)?.let { depth ->
                        val candidate=(dragOriginal ?: editingDraft!!).copy(depth=sketchMm(depth))
                        editingDraft=candidate;drawDraftNow(candidate);onUi { onSketchDrawn(candidate) }
                    }
                }
                it.consume();return@setOnMouseDragged
            }
            val dx=it.sceneX-downX;val dy=it.sceneY-downY
            if(editingDraft!=null && it.isPrimaryButtonDown && !it.isAltDown && !it.isShiftDown) {
                val start=drawingStart;val end=screenOnSketch(it.sceneX,it.sceneY)?.let(::snapPoint)
                if(start!=null && end!=null) {
                    drawingCandidate=runCatching {
                        val original=dragOriginal ?: editingDraft!!
                        if(dragHandle!=null) original.resizeHandle(dragHandle!!,end,drawingValues)
                        else profileCandidate(original,start,end,dragHadProfile)
                    }.getOrNull()
                    drawingCandidate?.let { candidate -> drawDraftNow(candidate);onUi { onSketchDrawn(candidate) } }
                }
                if(start==null && drawingTool !in profileTools) { it.consume();return@setOnMouseDragged }
            } else if(it.isSecondaryButtonDown || it.isAltDown) {
                if(it.isShiftDown) panBy(dx,dy) else { state.orbit(dx,-dy);onUi { onOrientation("") } }
            } else if(it.isPrimaryButtonDown && !it.isShiftDown) { state.orbit(dx,-dy);onUi { onOrientation("") } }
            downX=it.sceneX;downY=it.sceneY;queueCameraUpdate();it.consume()
        }
        view.setOnMouseReleased {
            if(extrusionDrag!=null) { extrusionDrag=null;dragOriginal=null;it.consume();return@setOnMouseReleased }
            if(editingDraft!=null && !extrusionOnly && it.button==MouseButton.PRIMARY && !it.isAltDown && !it.isShiftDown && travel>=4.0) {
                val candidate=drawingCandidate
                if(candidate!=null) {
                    editingDraft=candidate;editingHasProfile=true
                    onUi { onSketchDrawn(candidate) }
                } else if(drawingStart==null) onUi { onDrawingError("Die Fläche liegt zu seitlich zur Ansicht. Drehe das Objekt etwas, um darauf zu zeichnen.") }
                else if(editingHasProfile) drawDraftNow(editingDraft!!) else drawingGroup.children.clear()
                clickAnchor=null;drawingStart=null;drawingCandidate=null;dragHandle=null;dragPointIndex=null;dragOriginal=null;snapHint=null;snapGuides=emptyList();refreshOverlay();it.consume()
            }
        }
        view.setOnMouseMoved { event ->
            if(editingDraft!=null && !extrusionOnly) {
                val point=screenOnSketch(event.sceneX,event.sceneY)?.let(::snapPoint)
                if(clickAnchor!=null && point!=null) {
                    drawingCandidate=runCatching { profileCandidate(editingDraft!!,clickAnchor!!,point) }.getOrNull()
                    drawingCandidate?.let(::drawDraftNow)
                } else if(pendingPoints.isNotEmpty() && point!=null) drawPending(point)
                refreshOverlay()
                val selectedProfile=editingDraft!!.editableElements().singleOrNull()?.takeIf { toolMatches(it,drawingTool) }
                val near=runCatching { nearSelectedPoint(editingDraft!!,event.sceneX,event.sceneY)!=null || drawingTool in profileTools && editingHasProfile && selectedProfile!=null && selectedProfile.on(editingDraft!!).handles(drawingValues).any { h -> projectSketch(h.point)?.let { hypot(it.u-event.sceneX,it.v-event.sceneY)<12 } ?: false } }.getOrDefault(false)
                view.cursor=if(near) javafx.scene.Cursor.HAND else javafx.scene.Cursor.CROSSHAIR
            } else if(extrusionOnly) {
                val handle=extrusionHandlePoints()
                view.cursor=if(handle!=null && hypot(handle.second.u-event.sceneX,handle.second.v-event.sceneY)<14) javafx.scene.Cursor.HAND else javafx.scene.Cursor.DEFAULT
            } else view.cursor=javafx.scene.Cursor.DEFAULT
        }
        view.setOnMouseClicked {
            if(editingDraft!=null) {
                if(!extrusionOnly && travel<4 && it.button==MouseButton.PRIMARY && !it.isShiftDown && !it.isAltDown && dragHandle==null && dragPointIndex==null) {
                    val event=it
                    screenOnSketch(event.sceneX,event.sceneY)?.let(::snapPoint)?.let { point -> acceptSketchPoint(point,event.clickCount) }
                }
                it.consume();return@setOnMouseClicked
            }
            if(travel<4.0 && it.button==MouseButton.PRIMARY) {
                if(chatMarking) {
                    val hit=it.pickResult
                    if(hit.intersectedNode===meshView && hit.intersectedFace>=0) {
                        val p=hit.intersectedPoint
                        val point=Vec3(p.x/modelScale+modelCenter.x,p.z/modelScale+modelCenter.y,-p.y/modelScale+modelCenter.z)
                        val triangle=hit.intersectedFace;val shift=it.isShiftDown
                        onUi { onChatPointPicked(triangle,point,shift) }
                    }
                    it.consume();return@setOnMouseClicked
                }
                val ray=cameraRay(it.sceneX,it.sceneY)
                val region=pickRegion(pickableRegions,ray.first,ray.second,if(meshGroup.isVisible) persistedGeometry else null)
                if(region!=null) { val shift=it.isShiftDown;onUi { onRegionPicked(region,shift) };it.consume();return@setOnMouseClicked }

                val selected=it.pickResult.intersectedNode===meshView && meshView!=null
                val face=if(selected) it.pickResult.intersectedFace.takeIf { index -> index>=0 } else null
                onUi { onSelection(selected);onFacePicked(face,it.isShiftDown) }
                // Fast successive clicks on faces must not reset the view.
                if(it.clickCount==2 && !selected) fitNow()
            }
        }
        view.setOnKeyPressed { event ->
            when(event.code) {
                KeyCode.ENTER -> if(!overlay.editingDimension && drawingTool==SketchDrawingTool.POLYLINE && pendingPoints.size>=2) { commitOpenElement(openSketchPath(SketchElementKind.POLYLINE,pendingPoints.toList()));event.consume() }
                KeyCode.ESCAPE -> if(!overlay.editingDimension && (pendingPoints.isNotEmpty() || clickAnchor!=null)) { pendingPoints.clear();clickAnchor=null;drawingStart=null;drawingCandidate=null;drawDraftNow(editingDraft!!);event.consume() }
                else -> Unit
            }
        }
    }

    private val profileTools=setOf(SketchDrawingTool.RECTANGLE,SketchDrawingTool.CENTER_RECTANGLE,SketchDrawingTool.CIRCLE,SketchDrawingTool.TRIANGLE)
    private fun toolMatches(element:SketchElement,tool:SketchDrawingTool)=element.kind==SketchElementKind.PROFILE && when(tool) {
        SketchDrawingTool.RECTANGLE,SketchDrawingTool.CENTER_RECTANGLE -> element.shape==SketchShape.RECTANGLE
        SketchDrawingTool.CIRCLE -> element.shape==SketchShape.CIRCLE
        SketchDrawingTool.TRIANGLE -> element.shape==SketchShape.TRIANGLE
        else -> false
    }
    private fun profileCandidate(original: Sketch,start: SketchPoint,end: SketchPoint,hadProfile: Boolean=editingHasProfile): Sketch? {
        val shape=when(drawingTool) {
            SketchDrawingTool.CIRCLE -> SketchShape.CIRCLE
            SketchDrawingTool.TRIANGLE -> SketchShape.TRIANGLE
            else -> SketchShape.RECTANGLE
        }
        val centered=drawingTool==SketchDrawingTool.CENTER_RECTANGLE
        val shaped=original.copy(shape=shape,centered=centered).drawn(start,end,false,drawingValues,centered) ?: return null
        val element=SketchElement.from(shaped,if(original.elements.isEmpty() && !hadProfile) "profile" else java.util.UUID.randomUUID().toString())
        return if(original.elements.isEmpty() && !hadProfile) shaped.copy(elements=listOf(element)) else original.addElement(element)
    }

    private fun acceptSketchPoint(point: SketchPoint,clickCount: Int) {
        if(drawingTool in profileTools) {
            if(clickAnchor==null) clickAnchor=point else {
                val candidate=runCatching { profileCandidate(editingDraft!!,clickAnchor!!,point) }.getOrNull()
                if(candidate!=null) commitSketchDraft(candidate)
            }
            return
        }
        when(drawingTool) {
            SketchDrawingTool.LINE -> {
                if(pendingPoints.isEmpty()) pendingPoints.add(point) else {
                    commitOpenElement(openSketchPath(SketchElementKind.LINE,listOf(pendingPoints.first(),point)))
                }
            }
            SketchDrawingTool.POLYLINE -> {
                val closesLoop=clickCount>1 && pendingPoints.size>=3 && pendingPoints.first()==point
                if(!closesLoop && pendingPoints.lastOrNull()!=point) pendingPoints.add(point)
                if(clickCount>1 && pendingPoints.size>=(if(closesLoop) 3 else 2)) commitOpenElement(openSketchPath(SketchElementKind.POLYLINE,pendingPoints.toList(),closed=closesLoop))
                else refreshOverlay()
            }
            SketchDrawingTool.ARC -> {
                pendingPoints.add(point)
                if(pendingPoints.size==3) {
                    runCatching { sketchArc(pendingPoints[0],pendingPoints[1],pendingPoints[2]) }
                        .onSuccess(::commitOpenElement).onFailure { onUi { onDrawingError(it.message ?: "Ungültiger Bogen.") };pendingPoints.clear() }
                } else refreshOverlay()
            }
            else -> Unit
        }
    }

    private fun commitOpenElement(element: SketchElement) {
        val draft=editingDraft ?: return
        commitSketchDraft(draft.addElement(element))
    }

    private fun commitSketchDraft(candidate: Sketch) {
        editingDraft=candidate;editingHasProfile=true;clickAnchor=null;drawingStart=null;drawingCandidate=null;pendingPoints.clear()
        drawDraftNow(candidate);onUi { onSketchDrawn(candidate) }
    }

    private fun drawPending(cursor: SketchPoint) {
        val draft=editingDraft ?: return
        drawDraftNow(draft)
        val points=when(drawingTool) {
            SketchDrawingTool.POLYLINE -> pendingPoints+cursor
            SketchDrawingTool.LINE -> pendingPoints.takeLast(1)+cursor
            SketchDrawingTool.ARC -> when(pendingPoints.size) {
                1 -> listOf(pendingPoints[0],cursor)
                2 -> {
                    val center=pendingPoints[0];val start=pendingPoints[1];val radius=hypot(start.u-center.u,start.v-center.v)
                    val dx=cursor.u-center.u;val dy=cursor.v-center.v;val d=hypot(dx,dy).coerceAtLeast(1e-8)
                    listOf(center,start,SketchPoint(center.u+dx*radius/d,center.v+dy*radius/d))
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
        val wires=if(drawingTool==SketchDrawingTool.ARC && points.size==3) runCatching { listOf(sketchArc(points[0],points[1],points[2]).geometry(draft,drawingValues)) }.getOrDefault(emptyList())
            else if(points.size>=2) listOf(openSketchPath(if(drawingTool==SketchDrawingTool.LINE) SketchElementKind.LINE else SketchElementKind.POLYLINE,points).geometry(draft,drawingValues)) else emptyList()
        drawWires(wires,draft)
    }

    private fun cadFromWorld(point: javafx.geometry.Point3D)=Vec3(point.x/modelScale+modelCenter.x,
        point.z/modelScale+modelCenter.y,-point.y/modelScale+modelCenter.z)
    private fun worldFromCad(point: Vec3)=javafx.geometry.Point3D((point.x-modelCenter.x)*modelScale,
        -(point.z-modelCenter.z)*modelScale,(point.y-modelCenter.y)*modelScale)
    private fun screenOnSketch(x: Double,y: Double): SketchPoint? {
        val draft=editingDraft ?: return null
        val focal=view.height/2/tan(Math.toRadians(camera.fieldOfView/2))
        val origin=world.sceneToLocal(camera.localToScene(javafx.geometry.Point3D.ZERO))
        val far=world.sceneToLocal(camera.localToScene(javafx.geometry.Point3D((x-view.width/2)/focal,(y-view.height/2)/focal,1.0)))
        return draft.drawingFrame().intersectRay(cadFromWorld(origin),cadFromWorld(far)-cadFromWorld(origin))
    }
    private fun cameraRay(x: Double,y: Double): Pair<Vec3,Vec3> {
        val focal=view.height/2/tan(Math.toRadians(camera.fieldOfView/2))
        val origin=world.sceneToLocal(camera.localToScene(javafx.geometry.Point3D.ZERO))
        val far=world.sceneToLocal(camera.localToScene(javafx.geometry.Point3D((x-view.width/2)/focal,(y-view.height/2)/focal,1.0)))
        val start=cadFromWorld(origin)
        return start to (cadFromWorld(far)-start)
    }
    private fun projectCad(point: Vec3): SketchPoint? {
        val q=camera.sceneToLocal(world.localToScene(worldFromCad(point)))
        if(q.z<=camera.nearClip) return null
        val focal=view.height/2/tan(Math.toRadians(camera.fieldOfView/2))
        return SketchPoint(view.width/2+focal*q.x/q.z,view.height/2+focal*q.y/q.z)
    }
    /** Draws pickable screen-space axis handles over the model without intercepting other viewport input. */
    fun setPositionGizmo(position:Vec3?,onDelta:((String,Double)->Unit)?=null)=command {
        positionGizmoOrigin=position;positionGizmoDrag=onDelta;drawPositionGizmo()
    }
    fun positionGizmoDragTestPoints(axisName:String,deltaMm:Double)=command {
        require(axisName in setOf("X","Y","Z") && deltaMm.isFinite())
        val origin=checkNotNull(positionGizmoOrigin);val axis=when(axisName) { "X"->Vec3(1.0,0.0,0.0);"Y"->Vec3(0.0,1.0,0.0);else->Vec3(0.0,0.0,1.0) }
        val base=checkNotNull(projectCad(origin));val projected=checkNotNull(projectCad(origin+axis*25.0))
        val dx=projected.u-base.u;val dy=projected.v-base.v;val norm=hypot(dx,dy)
        require(norm>.1) { "Der Griff ist in dieser Ansicht nicht sichtbar." }
        val ux=dx/norm;val uy=dy/norm;val start=SketchPoint(base.u+ux*32,base.v+uy*32);val pixelsPerMm=norm/25.0
        listOf(start,SketchPoint(start.u+ux*deltaMm*pixelsPerMm,start.v+uy*deltaMm*pixelsPerMm))
    }
    private fun drawPositionGizmo() {
        if(!::view.isInitialized || !::camera.isInitialized) return
        if(positionGizmoDragging) return
        positionGizmo.children.clear()
        val origin=positionGizmoOrigin ?: return
        val callback=positionGizmoDrag ?: return
        val base=projectCad(origin) ?: return
        val axes=listOf(Triple("X",Vec3(1.0,0.0,0.0),Color.web("#f05d5e")),Triple("Y",Vec3(0.0,1.0,0.0),Color.web("#42b883")),Triple("Z",Vec3(0.0,0.0,1.0),Color.web("#4388ff")))
        axes.forEach { (name,axis,color) ->
            val projected=projectCad(origin+axis*25.0) ?: return@forEach
            val vx=projected.u-base.u;val vy=projected.v-base.v;val norm=hypot(vx,vy)
            if(norm<.1) return@forEach
            val ux=vx/norm;val uy=vy/norm;val pixelsPerMm=norm/25.0
            val endX=base.u+ux*64;val endY=base.v+uy*64
            val visible=Line(base.u,base.v,endX,endY).apply { stroke=color;strokeWidth=3.5;isMouseTransparent=true }
            val hit=Line(base.u,base.v,endX,endY).apply { stroke=Color.color(1.0,1.0,1.0,.01);strokeWidth=18.0;isPickOnBounds=true }
            var lastX=0.0;var lastY=0.0
            hit.setOnMousePressed { event -> positionGizmoDragging=true;lastX=event.sceneX;lastY=event.sceneY;event.consume() }
            hit.setOnMouseDragged { event ->
                val dx=event.sceneX-lastX;val dy=event.sceneY-lastY
                lastX=event.sceneX;lastY=event.sceneY
                callback(name,(dx*ux+dy*uy)/pixelsPerMm)
                event.consume()
            }
            hit.setOnMouseReleased { positionGizmoDragging=false;drawPositionGizmo();it.consume() }
            val tip=Circle(endX,endY,5.5,color).apply { isMouseTransparent=true }
            val label=Text(name).apply { fill=color;font=Font.font("System",12.0);translateX=endX+7;translateY=endY+4;isMouseTransparent=true }
            positionGizmo.children.addAll(visible,hit,tip,label)
        }
    }
    private fun projectSketch(point: SketchPoint): SketchPoint? = editingDraft?.drawingFrame()?.point(point.u,point.v)?.let(::projectCad)
    private fun profileCenter(draft: Sketch): Vec3 {
        val points=draft.points(drawingValues)
        return points.reduce { a,b -> a+b }*(1.0/points.size)
    }
    private fun extrusionHandlePoints(): Pair<SketchPoint,SketchPoint>? = runCatching {
        val draft=editingDraft ?: return@runCatching null
        val origin=profileCenter(draft);val axis=draft.extrusionAxis()
        val depth=ParameterEvaluator.expression(draft.depth ?: return@runCatching null,drawingValues).value
        val base=projectCad(origin) ?: return@runCatching null
        val cap=projectCad(origin+axis*depth) ?: return@runCatching null
        val probe=projectCad(origin+axis*10.0) ?: return@runCatching null
        val dx=probe.u-base.u;val dy=probe.v-base.v;val length=hypot(dx,dy)
        val ray=cameraRay(cap.u,cap.v)
        if(length<2.0 || axisDistance(origin,axis,ray.first,ray.second)==null) return@runCatching null
        val sign=if(depth<0) -1 else 1
        base to SketchPoint(cap.u+sign*18*dx/length,cap.v+sign*18*dy/length)
    }.getOrNull()
    private fun showDepthHandle() {
        if(extrusionOnly) {
            val handle=extrusionHandlePoints()
            overlay.extrusionHandle(handle?.first,handle?.second)
        }
    }
    fun cancelPendingDrawing()=command {
        if((clickAnchor==null && pendingPoints.isEmpty()) || overlay.editingDimension) false else {
            clickAnchor=null;pendingPoints.clear();drawingStart=null;drawingCandidate=null;dragHandle=null;dragOriginal=null
            snapHint=null;snapGuides=emptyList();editingDraft?.let(::drawDraftNow);refreshOverlay()
            true
        }
    }
    internal fun pendingDrawing()=command { clickAnchor!=null || pendingPoints.isNotEmpty() }

    private fun snapPoint(point: SketchPoint): SketchPoint {
        if(!drawingSnap) { snapHint=null;snapGuides=emptyList();return point }
        val p=projectSketch(point);val next=projectSketch(SketchPoint(point.u+1,point.v))
        val pixel=if(p!=null && next!=null) hypot(next.u-p.u,next.v-p.v) else 5.0
        val result=snapSketchPoint(point,planeCenter,(10/pixel.coerceAtLeast(.1)).coerceAtMost(10.0),true,planeBoundary)
        snapGuides=result.guides
        snapHint=result.label?.let { result.point to it }
        return result.point
    }
    private fun refreshOverlay() {
        val draft=editingDraft
        val active=drawingCandidate ?: draft
        val profile=active?.editableElements()?.singleOrNull()?.takeIf { it.kind==SketchElementKind.PROFILE }
        if(active!=null && editingHasProfile && profile!=null) overlay.show(profile.on(active),drawingValues,::projectSketch,snapHint,profileControls=!extrusionOnly,depthControls=extrusionOnly)
        else overlay.clear()
        showDepthHandle()
        if(draft!=null && !extrusionOnly) { overlay.references(planeCenter,::projectSketch,snapHint);overlay.grid(planeGridLines,::projectSketch);overlay.guides(snapGuides,::projectSketch) }
    }
    private fun makeSketchGrid(draft: Sketch,patch: List<Vec3>) {
        planeGridLines.clear()
        val frame=draft.drawingFrame()
        planeBoundary=sketchBoundary(patch,frame)
        val points=patch.filter { abs((it-frame.origin).dot(frame.normal))<1e-5 }.map(frame::project)
        planeCenter=if(points.size>=3) SketchPoint((points.minOf { it.u }+points.maxOf { it.u })/2,(points.minOf { it.v }+points.maxOf { it.v })/2) else null
        val span=(persistedGeometry?.bounds?.span ?: 100.0).coerceAtLeast(10.0)
        val raw=span/15;val power=10.0.pow(floor(log10(raw)))
        val step=(power*when { raw/power>=5 -> 5.0;raw/power>=2 -> 2.0;else -> 1.0 }).coerceAtLeast(.1)
        val center=planeCenter ?: SketchPoint(0.0,0.0)
        val baseU=round(center.u/step)*step;val baseV=round(center.v/step)*step
        fun line(a: SketchPoint,b: SketchPoint,axis: Boolean) { planeGridLines.add(a to b) }
        val countU=if(points.size>=3) ceil((points.maxOf { it.u }-points.minOf { it.u })/2/step).toInt()+2 else 15
        val countV=if(points.size>=3) ceil((points.maxOf { it.v }-points.minOf { it.v })/2/step).toInt()+2 else 15
        val nu=countU.coerceIn(4,40);val nv=countV.coerceIn(4,40)
        for(i in -nu..nu) {
            val u=baseU+i*step
            line(SketchPoint(u,baseV-nv*step),SketchPoint(u,baseV+nv*step),abs(u)<1e-9)
        }
        for(i in -nv..nv) {
            val v=baseV+i*step
            line(SketchPoint(baseU-nu*step,v),SketchPoint(baseU+nu*step,v),abs(v)<1e-9)
        }
    }
    fun setSketchEditing(draft: Sketch?,values: Map<String,Quantity> = emptyMap(),hasProfile: Boolean = false,snap: Boolean = true, context: MeshData? = persistedGeometry, bodies: List<MeshData> = emptyList(), supportPatch: List<Vec3> = emptyList(), centered: Boolean = false, extrusion: Boolean = false, tool: SketchDrawingTool = SketchDrawingTool.RECTANGLE,selectedElementId:String?=null) = command {
        val changedPlane=editingDraft?.id!=draft?.id || editingDraft?.drawingFrame()!=draft?.drawingFrame()
        if(changedPlane || drawingTool!=tool || draft==null) { pendingPoints.clear();clickAnchor=null }
        editingDraft=draft;drawingValues=values;editingHasProfile=hasProfile;drawingSnap=snap;drawingCentered=centered;extrusionOnly=extrusion;drawingTool=tool;this.selectedElementId=selectedElementId
        if(draft==null) extrusionDrag=null
        if(!snap) { snapHint=null;snapGuides=emptyList() }
        drawingGroup.children.clear();sketchGroup.isVisible=draft==null
        bodyPreviewGroup.children.clear();previewBodyCount=0;previewGeometry=emptyList()
        if(draft!=null) {
            if(changedPlane) { clickAnchor=null;makeSketchGrid(draft,supportPatch) }
            if(!contextOverridden || previewContextGeometry!==context) {
                replaceSurfaceNow(context);previewContextGeometry=context;contextOverridden=true
            }
            faceGroup.children.clear();emptyText.isVisible=false
            if(hasProfile) { drawDraftNow(draft);showPreviewBodies(bodies) } else { overlay.clear();overlay.references(planeCenter,::projectSketch,snapHint);overlay.grid(planeGridLines,::projectSketch);overlay.guides(snapGuides,::projectSketch) }
        } else {
            planeGridLines.clear();overlay.clear();clickAnchor=null;snapHint=null;snapGuides=emptyList();planeBoundary=emptyList();planeCenter=null
            if(contextOverridden) replaceSurfaceNow(persistedGeometry)
            contextOverridden=false;previewContextGeometry=null
            emptyText.isVisible=meshView==null && sketchGroup.children.isEmpty()
        }
    }
    private fun drawDraftNow(draft: Sketch) {
        val wires=runCatching {
            if(draft.elements.isEmpty()) draft.profileCopies(drawingValues).mapIndexed { i,points -> SketchElementGeometry("profile-$i",SketchElementKind.PROFILE,points,true) }
            else draft.elementGeometries(drawingValues).flatMap { element ->
                if(element.kind==SketchElementKind.PROFILE && draft.mirror!=null) {
                    val distance=draft.mirrorDistance(drawingValues)
                    listOf(element,element.copy(id="mirror:${element.id}",points=element.points.map { draft.mirror.reflect(it,distance) }))
                } else listOf(element)
            }
        }.getOrNull() ?: return
        drawingGroup.children.clear()
        drawWires(wires,draft)
        selectedElementId?.let { id -> draft.editableElements().firstOrNull { it.id==id }?.takeIf { it.kind==SketchElementKind.LINE || it.kind==SketchElementKind.POLYLINE }?.let { element ->
            val normal=draft.drawingFrame().normal*(.8/modelScale)
            element.geometry(draft,drawingValues).points.forEach { point ->
                val p=worldFromCad(point+normal)
                drawingGroup.children.add(javafx.scene.shape.Sphere(1.4).apply { translateX=p.x;translateY=p.y;translateZ=p.z;material=PhongMaterial(Color.web("#1685ff"));isMouseTransparent=true })
            }
        } }
        val editableProfile=draft.editableElements().singleOrNull()?.takeIf { it.kind==SketchElementKind.PROFILE }
        if((editingHasProfile || drawingCandidate!=null) && editableProfile!=null) overlay.show(editableProfile.on(draft),drawingValues,::projectSketch,snapHint,profileControls=!extrusionOnly,depthControls=extrusionOnly)
        else overlay.clear()
        showDepthHandle()
        if(!extrusionOnly) { overlay.references(planeCenter,::projectSketch,snapHint);overlay.grid(planeGridLines,::projectSketch);overlay.guides(snapGuides,::projectSketch) }
    }
    private fun drawWires(wires: List<SketchElementGeometry>,draft: Sketch) {
        val normal=draft.drawingFrame().normal*(.3/modelScale)
        val pixel=WritableImage(1,1).apply { pixelWriter.setColor(0,0,Color.web("#007aff")) }
        val ink=PhongMaterial(Color.BLACK).apply { selfIlluminationMap=pixel }
        wires.forEach { wire ->
          val points=wire.points
          val count=if(wire.closed) points.size else max(0,points.size-1)
          repeat(count) { i ->
            val a=worldFromCad(points[i]+normal);val b=worldFromCad(points[(i+1)%points.size]+normal)
            val delta=b.subtract(a);val length=delta.magnitude()
            if(length>0) {
                val axis=javafx.geometry.Point3D(0.0,1.0,0.0).crossProduct(delta)
                drawingGroup.children.add(Cylinder(.9,length,10).apply {
                    material=ink;translateX=(a.x+b.x)/2;translateY=(a.y+b.y)/2;translateZ=(a.z+b.z)/2
                    if(axis.magnitude()>1e-9) transforms.add(Rotate(Math.toDegrees(acos((delta.y/length).coerceIn(-1.0,1.0))),axis))
                })
            }
          }
        }
    }

    private fun nearSelectedPoint(draft:Sketch,x:Double,y:Double):Int? {
        val id=selectedElementId ?: return null
        val element=draft.editableElements().firstOrNull { it.id==id } ?: return null
        if(element.kind!=SketchElementKind.LINE && element.kind!=SketchElementKind.POLYLINE) return null
        return element.geometry(draft,drawingValues).points.mapIndexedNotNull { index,point ->
            projectSketch(draft.drawingFrame().project(point))?.let { index to hypot(it.u-x,it.v-y) }
        }.filter { it.second<12 }.minByOrNull { it.second }?.first
    }
    private fun showPreviewBodies(bodies: List<MeshData>) {
        previewBodyCount=bodies.size;previewGeometry=bodies
        val fill=PhongMaterial(Color.color(.12,.53,1.0,.42)).apply { specularColor=Color.web("#7bbaff") }
        val outline=PhongMaterial(Color.web("#1682f4"))
        bodies.forEach { body ->
            bodyPreviewGroup.children.add(createSurface(body,fill).apply { cullFace=CullFace.BACK })
            body.contextEdges().forEach { (first,second) ->
                val a=worldFromCad(first);val b=worldFromCad(second);val delta=b.subtract(a);val length=delta.magnitude()
                if(length>0) {
                    val axis=javafx.geometry.Point3D(0.0,1.0,0.0).crossProduct(delta)
                    bodyPreviewGroup.children.add(Cylinder(.6,length,8).apply {
                        material=outline;translateX=(a.x+b.x)/2;translateY=(a.y+b.y)/2;translateZ=(a.z+b.z)/2
                        if(axis.magnitude()>1e-9) transforms.add(Rotate(Math.toDegrees(acos((delta.y/length).coerceIn(-1.0,1.0))),axis))
                    })
                }
            }
        }
    }
    fun extrusionDragTestPoints(delta: Double) = command {
        val draft=checkNotNull(editingDraft);val handle=checkNotNull(extrusionHandlePoints())
        val origin=profileCenter(draft);val axis=draft.extrusionAxis()
        val ray=cameraRay(handle.second.u,handle.second.v)
        val grabbed=checkNotNull(axisDistance(origin,axis,ray.first,ray.second))
        listOf(handle.second,checkNotNull(projectCad(origin+axis*(grabbed+delta))))
    }
    fun extrusionControlsCheck() = command {
        check(extrusionOnly)
        check(overlay.pane.lookup("#dimension-width")==null && overlay.pane.lookup("#dimension-height")==null)
        val button=overlay.pane.lookup("#dimension-depth") as javafx.scene.control.Button
        button.fire()
        val input=overlay.pane.lookup("#dimension-input") as javafx.scene.control.TextField
        input.text="18"
        input.fireEvent(javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.ENTER,false,false,false,false))
        check(editingDraft!!.depth=="18 mm")
    }
    fun interactionResizePoints() = command {
        val draft=checkNotNull(editingDraft)
        val h=draft.handles(drawingValues)
        val a=h.first { it.id==0 }.point;val b=h.first { it.id==2 }.point
        listOf(checkNotNull(projectSketch(b)),checkNotNull(projectSketch(SketchPoint(b.u+(b.u-a.u)*.35,b.v+(b.v-a.v)*.35))))
    }
    fun interactionSketchPointHandles() = command {
        val draft=checkNotNull(editingDraft);val id=checkNotNull(selectedElementId)
        val element=draft.editableElements().first { it.id==id }
        require(element.kind==SketchElementKind.LINE || element.kind==SketchElementKind.POLYLINE)
        element.geometry(draft,drawingValues).points.map { checkNotNull(projectSketch(draft.drawingFrame().project(it))) }
    }
    fun interactionDimensionCheck() = command {
        val original=checkNotNull(editingDraft)
        val button=overlay.pane.lookup("#dimension-width") as javafx.scene.control.Button
        check(button.width>0 && button.height>0)
        button.fire()
        var input=overlay.pane.lookup("#dimension-input") as javafx.scene.control.TextField
        fun key(code: javafx.scene.input.KeyCode) {
            input.fireEvent(javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",code,false,false,false,false))
        }
        input.text="5 deg";key(javafx.scene.input.KeyCode.ENTER)
        check(editingDraft==original) { "Invalid dimension changed geometry" }
        key(javafx.scene.input.KeyCode.ESCAPE)
        (overlay.pane.lookup("#dimension-width") as javafx.scene.control.Button).fire()
        input=overlay.pane.lookup("#dimension-input") as javafx.scene.control.TextField
        input.text="32.5";key(javafx.scene.input.KeyCode.ENTER)
        check(editingDraft!!.width=="32.5 mm") { "Inline numeric dimension was not applied" }
        check(editingDraft!!.id==original.id && editingDraft!!.angle==original.angle)
        val parameter=drawingValues.entries.firstOrNull { it.value.dimension==Dimension(1) && it.value.value>0 }
        if(parameter!=null) {
            (overlay.pane.lookup("#dimension-width") as javafx.scene.control.Button).fire()
            input=overlay.pane.lookup("#dimension-input") as javafx.scene.control.TextField
            val expression=parameter.key+" / 2"
            input.text=expression;key(javafx.scene.input.KeyCode.ENTER)
            check(editingDraft!!.width==expression) { "Parameter expression was lost" }
            (overlay.pane.lookup("#dimension-width") as javafx.scene.control.Button).fire()
            input=overlay.pane.lookup("#dimension-input") as javafx.scene.control.TextField
            input.text="32.5";key(javafx.scene.input.KeyCode.ENTER)
        }
        check(planeGridLines.isNotEmpty()) { "Active-plane grid missing" }
        if(planeBoundary.isNotEmpty()) {
            val edge=planeBoundary.maxBy { hypot(it.second.u-it.first.u,it.second.v-it.first.v) }
            val midpoint=SketchPoint((edge.first.u+edge.second.u)/2,(edge.first.v+edge.second.v)/2)
            val before=editingDraft
            snapPoint(midpoint);refreshOverlay()
            check(snapHint?.second=="Kante" && overlay.guideCount>0) { "Boundary inference guide missing" }
            check(editingDraft==before) { "Hover inference modified the draft" }
            drawingSnap=false;snapPoint(midpoint);refreshOverlay()
            check(snapGuides.isEmpty() && overlay.guideCount==0) { "Disabled snapping retained guides" }
            drawingSnap=true
        }
    }
    fun previewExtents() = command { previewGeometry.map { it.bounds.size } }
    fun extrusionPreviewState() = command { previewBodyCount to contextOverridden }
    /** Screen points on the real support face for the native input regression test. */
    fun drawingTestPoints(patch: List<Vec3>) = command {
        val draft=checkNotNull(editingDraft)
        val points=if(patch.isNotEmpty()) patch.map(draft::localProject) else listOf(SketchPoint(0.0,0.0),SketchPoint(40.0,30.0))
        val minU=points.minOf { it.u };val maxU=points.maxOf { it.u };val minV=points.minOf { it.v };val maxV=points.maxOf { it.v }
        fun screen(u: Double,v: Double): SketchPoint {
            val q=camera.sceneToLocal(world.localToScene(worldFromCad(draft.drawingFrame().point(u,v))))
            val focal=view.height/2/tan(Math.toRadians(camera.fieldOfView/2))
            return SketchPoint(view.width/2+focal*q.x/q.z,view.height/2+focal*q.y/q.z)
        }
        listOf(screen(minU+(maxU-minU)*.2,minV+(maxV-minV)*.25),screen(minU+(maxU-minU)*.55,minV+(maxV-minV)*.7))
    }
    fun drawingVisibility() = command { meshGroup.isVisible && (meshView!=null || previewBodyCount>0) && drawingGroup.children.isNotEmpty() }
    fun framingSignature() = command { listOf(view.width,view.height,camera.translateZ,state.yaw,state.pitch,state.panX,state.panY,state.zoom,modelScale) }

    private fun installMacPinch() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        // This native AWT API needs the documented JVM export in build.gradle.kts/package-macos.sh.
        val listenerType = Class.forName("com.apple.eawt.event.MagnificationListener")
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "magnify" -> {
                    val event = args!![0]
                    val delta = (event.javaClass.getMethod("getMagnification").invoke(event) as Number).toDouble()
                    magnify(1.0 + delta)
                    event.javaClass.getMethod("consume").invoke(event)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "KotlinCadMagnificationListener"
                else -> null
            }
        }
        Class.forName("com.apple.eawt.event.GestureUtilities")
            .getMethod("addGestureListenerTo", javax.swing.JComponent::class.java,
                Class.forName("com.apple.eawt.event.GestureListener"))
            .invoke(null, panel, listener)
        macGestureListener = listener
        nativePinchInstalled = true
    }

    fun showOriginPlanes(show: Boolean) = command {
        originPlanes.children.clear()
        if(show) SketchPlane.entries.forEach { plane ->
            val points=listOf(plane.point(-55.0,-55.0),plane.point(55.0,-55.0),plane.point(55.0,55.0),plane.point(-55.0,55.0))
            val data=TriangleMesh().apply {
                this.points.setAll(*points.flatMap { listOf(((it.x-modelCenter.x)*modelScale).toFloat(),(-(it.z-modelCenter.z)*modelScale).toFloat(),((it.y-modelCenter.y)*modelScale).toFloat()) }.toFloatArray())
                texCoords.setAll(0f,0f);faces.setAll(0,0,1,0,2,0,0,0,2,0,3,0)
            }
            originPlanes.children.add(MeshView(data).apply {
                cullFace=CullFace.NONE
                material=PhongMaterial(when(plane) { SketchPlane.XY -> Color.color(.25,.6,.95,.18);SketchPlane.XZ -> Color.color(.3,.75,.5,.18);SketchPlane.YZ -> Color.color(.95,.6,.3,.18) })
                setOnMouseClicked { onUi { onPlanePicked(plane) };it.consume() }
            })
        }
    }
    internal fun setChatMarking(enabled:Boolean)=command { chatMarking=enabled }
    internal fun showChatReferences(references:List<ChatReference>)=command {
        chatReferenceGroup.children.clear()
        val material=PhongMaterial(Color.web("#ffb454"))
        fun point(p:Vec3)=javafx.geometry.Point3D((p.x-modelCenter.x)*modelScale,-(p.z-modelCenter.z)*modelScale,(p.y-modelCenter.y)*modelScale)
        for(reference in references) {
            val n=reference.normal;val u=n.cross(if(abs(n.x)<.8)Vec3(1.0,0.0,0.0) else Vec3(0.0,1.0,0.0)).normalized();val v=n.cross(u)
            val center=reference.point+n*(.6/modelScale)
            val ring=(0..64).map { i -> val angle=2*PI*i/64;point(center+(u*cos(angle)+v*sin(angle))*reference.radius) }
            for((a,b) in ring.zipWithNext()) {
                val d=b.subtract(a);val length=d.magnitude();val middle=a.midpoint(b);val axis=javafx.geometry.Point3D(0.0,1.0,0.0).crossProduct(d)
                chatReferenceGroup.children.add(Cylinder(1.5,length,8).apply {
                    this.material=material;translateX=middle.x;translateY=middle.y;translateZ=middle.z;isMouseTransparent=true
                    if(axis.magnitude()>1e-9)transforms.add(Rotate(Math.toDegrees(acos((d.y/length).coerceIn(-1.0,1.0))),axis))
                })
            }
            val p=point(center)
            chatReferenceGroup.children.add(Sphere(3.5).apply { this.material=material;translateX=p.x;translateY=p.y;translateZ=p.z;isMouseTransparent=true })
        }
    }
    fun highlightFace(points: List<Vec3>) = command {
        faceGroup.children.clear()
        if(points.isNotEmpty()) addFaceHighlight(points)
    }
    fun highlightFaces(patches:List<List<Vec3>>) = command {
        faceGroup.children.clear();patches.forEach(::addFaceHighlight)
    }
    private fun addFaceHighlight(points:List<Vec3>) {
        if(points.size>=3) {
            val triangle=TriangleMesh()
            val normal=(points[1]-points[0]).cross(points[2]-points[0]).normalized()*(.05/modelScale)
            triangle.points.setAll(*points.map { it+normal }.flatMap { listOf(((it.x-modelCenter.x)*modelScale).toFloat(),(-(it.z-modelCenter.z)*modelScale).toFloat(),((it.y-modelCenter.y)*modelScale).toFloat()) }.toFloatArray())
            triangle.texCoords.setAll(0f,0f);triangle.faces.setAll(*IntArray(points.size*2) { if(it%2==0) it/2 else 0 })
            faceGroup.children.add(MeshView(triangle).apply { cullFace=CullFace.NONE;material=PhongMaterial(Color.web("#549aeb"));isMouseTransparent=true;viewOrder=-1.0 })
        }
    }
    internal fun showAssemblyPreview(data:MeshData,colors:List<String>)=command {
        if(assemblyPreviewPose==null) assemblyPreviewPose=CameraPose(state.yaw,state.pitch,state.panX,state.panY,state.zoom)
        setModelNow(data)
        meshView=createSurface(data,surface,colors).apply { isMouseTransparent=true }
        meshGroup.children.setAll(meshView);updateCamera()
    }
    internal fun clearAssemblyPreview()=command {
        assemblyPreviewPose?.let { pose ->
            if(persistedGeometry!=null) setModelNow(persistedGeometry)
            state.yaw=pose.yaw;state.pitch=pose.pitch;state.panX=pose.panX;state.panY=pose.panY;state.zoom=pose.zoom
            assemblyPreviewPose=null;updateCamera()
        }
        analysisGroup.children.clear()
    }
    internal fun highlightAnalysis(points:List<Vec3>)=command {
        analysisGroup.children.clear()
        val material=PhongMaterial(Color.web("#FF5533"))
        points.take(4096).forEach { point -> val p=Vec3((point.x-modelCenter.x)*modelScale,-(point.z-modelCenter.z)*modelScale,(point.y-modelCenter.y)*modelScale)
            analysisGroup.children.add(Sphere(2.5).apply { this.material=material;translateX=p.x;translateY=p.y;translateZ=p.z })
        }
    }
    fun setFeaturePreview(bodies:List<MeshData>)=command { bodyPreviewGroup.children.clear();showPreviewBodies(bodies) }
    fun setBodyPositionPreview(environment:MeshData?,body:MeshData?)=command {
        if(!positionPreviewActive) { positionPreviewActive=true;replaceSurfaceNow(environment) }
        bodyPreviewGroup.children.clear()
        if(body!=null) showPreviewBodies(listOf(body))
    }
    fun clearBodyPositionPreview()=command {
        if(positionPreviewActive) replaceSurfaceNow(persistedGeometry)
        positionPreviewActive=false;bodyPreviewGroup.children.clear();previewBodyCount=0;previewGeometry=emptyList()
    }
    fun setModel(data: MeshData, accept: () -> Boolean = { true }): CompletableFuture<Unit> = command {
        if (!accept()) throw java.util.concurrent.CancellationException("Import replaced by a newer request")
        setModelNow(data)
    }
    private fun setModelNow(data: MeshData?, bounds: Bounds = requireNotNull(data).bounds) {
        hasModel = true
        val center = bounds.center
        modelCenter = center
        modelScale = 240.0 / bounds.span
        meshView=data?.let { createSurface(it,surface) }
        faceGroup.children.clear()
        meshGroup.children.setAll(listOfNotNull(meshView))
        meshGroup.isVisible = true
        emptyText.isVisible = false
        makeGrid((center.z - bounds.low.z) * modelScale + 0.7)
        state.reset(); updateCamera()
    }
    private fun createSurface(data: MeshData, material: PhongMaterial, colors:List<String> = if(data===persistedGeometry) persistedColors else emptyList()): MeshView {
        val points = FloatArray(data.vertices.size * 3)
        data.vertices.forEachIndexed { i, p ->
            points[i*3] = ((p.x-modelCenter.x)*modelScale).toFloat()
            points[i*3+1] = (-(p.z-modelCenter.z)*modelScale).toFloat()
            points[i*3+2] = ((p.y-modelCenter.y)*modelScale).toFloat()
        }
        val mesh = TriangleMesh()
        mesh.points.setAll(*points); mesh.texCoords.setAll(0f, 0f)
        val faces = IntArray(data.indices.size * 2)
        data.indices.forEachIndexed { i, index -> faces[i*2] = index }
        var actualMaterial=material
        if(colors.size==data.triangleCount) {
            val palette=colors.distinct();val lookup=palette.withIndex().associate { it.value to it.index }
            val image=javafx.scene.image.WritableImage(maxOf(1,palette.size),1)
            palette.forEachIndexed { i,hex -> image.pixelWriter.setColor(i,0,Color.web(hex)) }
            mesh.texCoords.setAll(*FloatArray(palette.size*2) { i -> if(i%2==0) (i/2+.5f)/palette.size else .5f })
            for(i in data.indices.indices) faces[i*2+1]=lookup.getValue(colors[i/3])
            actualMaterial=PhongMaterial(Color.WHITE).apply { diffuseMap=image;specularColor=Color.web("#3b4755") }
            coloredMaterial=actualMaterial
        }
        mesh.faces.setAll(*faces)
        mesh.faceSmoothingGroups.setAll(*IntArray(data.triangleCount))
        return MeshView(mesh).apply { cullFace=CullFace.NONE;this.material=actualMaterial;drawMode=if(wireframe) DrawMode.LINE else DrawMode.FILL }
    }
    private fun replaceSurfaceNow(data: MeshData?) {
        meshGroup.children.clear()
        meshView=data?.let { createSurface(it,surface) }
        meshView?.let { meshGroup.children.add(it) }
    }
    fun setSketches(profiles: List<List<Vec3>>) = command { setSketchesNow(profiles.mapIndexed { i, points -> SketchElementGeometry("legacy-$i",SketchElementKind.PROFILE,points,true) }) }
    private fun setSketchesNow(profiles: List<SketchElementGeometry>) {
        if (!hasModel && profiles.isNotEmpty()) {
            val points = profiles.flatMap { it.points }
            val low = Vec3(points.minOf { it.x },points.minOf { it.y },points.minOf { it.z })
            val high = Vec3(points.maxOf { it.x },points.maxOf { it.y },points.maxOf { it.z })
            modelCenter = Vec3((low.x+high.x)/2,(low.y+high.y)/2,(low.z+high.z)/2)
            modelScale = 240.0 / max(1.0,max(high.x-low.x,max(high.y-low.y,high.z-low.z)))
        }
        fun position(p: Vec3) = javafx.geometry.Point3D((p.x-modelCenter.x)*modelScale,
            -(p.z-modelCenter.z)*modelScale,(p.y-modelCenter.y)*modelScale)
        val material = PhongMaterial(Color.web("#007aff"))
        sketchGroup.children.clear()
        profiles.forEach { wire -> val points=wire.points
          val edgeCount=if(wire.closed) points.size else max(0,points.size-1)
          repeat(edgeCount) { i ->
            val a = position(points[i]); val b = position(points[(i+1)%points.size]); val delta = b.subtract(a)
            val length = delta.magnitude()
            if (length > 0) {
                val axis = javafx.geometry.Point3D(0.0,1.0,0.0).crossProduct(delta)
                val edge = Cylinder(0.65,length,8).apply {
                    this.material = material
                    translateX = (a.x+b.x)/2; translateY = (a.y+b.y)/2; translateZ = (a.z+b.z)/2
                    if (axis.magnitude() > 1e-9) transforms.add(Rotate(Math.toDegrees(acos((delta.y/length).coerceIn(-1.0,1.0))),axis))
                }
                sketchGroup.children.add(edge)
            }
          }
        }
        emptyText.isVisible = !hasModel && profiles.isEmpty()
    }
    fun setContent(data: MeshData?, profiles: List<SketchElementGeometry>, resetCamera: Boolean = false, framingBounds: Bounds? = null,
        colors: List<String> = emptyList(), accept: () -> Boolean = { true }) = command {
        if (!accept()) throw java.util.concurrent.CancellationException("Document load replaced")
        analysisGroup.children.clear();assemblyPreviewPose=null
        persistedGeometry=data;persistedColors=colors;coloredMaterial=null;contextOverridden=false;previewContextGeometry=null
        val pose=CameraPose(state.yaw,state.pitch,state.panX,state.panY,state.zoom)
        if (data == null && framingBounds==null) clearModelNow() else {
            val points=listOfNotNull(data?.bounds?.low,data?.bounds?.high)+profiles.flatMap { it.points }
            val bounds=framingBounds ?: Bounds(Vec3(points.minOf { it.x },points.minOf { it.y },points.minOf { it.z }),
                Vec3(points.maxOf { it.x },points.maxOf { it.y },points.maxOf { it.z }))
            setModelNow(data,bounds)
        }
        setSketchesNow(profiles)
        drawRegionHighlights()
        if (!resetCamera) {
            state.yaw=pose.yaw;state.pitch=pose.pitch;state.panX=pose.panX;state.panY=pose.panY;state.zoom=pose.zoom
            updateCamera()
        }
    }
    fun setWireframe(value: Boolean) = command {
        wireframe = false
        meshView?.drawMode = DrawMode.FILL
    }
    fun setGrid(value: Boolean) = command { gridVisible = value; grid.isVisible = value }
    fun setVisible(value: Boolean) = command { meshGroup.isVisible = value }
    fun select(value: Boolean) = command { highlight(value) }
    private fun highlight(value: Boolean) {
        val colored=coloredMaterial
        if(colored!=null) { colored.diffuseColor=if(value) Color.web("#DCEAFF") else Color.WHITE;meshView?.material=colored }
        else meshView?.material=if(value) selectionSurface else surface
    }
    private fun bodyFitArea(): ViewArea {
        val left=glassRegions["left"]?.let { it[0]+it[2]+16 } ?: 20.0
        val right=glassRegions["right"]?.let { it[0]-16 } ?: (view.width-20)
        val top=90.0
        return if(right-left>=100 && view.height-top>=100) ViewArea(left,top,right-left,view.height-top-20)
            else ViewArea(20.0,20.0,(view.width-40).coerceAtLeast(1.0),(view.height-40).coerceAtLeast(1.0))
    }
    fun fitBody(bounds: Bounds) = command {
        val pose=CameraPose(state.yaw,state.pitch,state.panX,state.panY,state.zoom)
            .fittedTo(bounds,modelCenter,modelScale,view.width,view.height,bodyFitArea(),camera.fieldOfView)
        state.panX=pose.panX;state.panY=pose.panY;state.zoom=pose.zoom;updateCamera()
    }
    internal fun assertCubeUnobscured() = command {
        val right=glassRegions["right"] ?: return@command
        check(cubeOverlay.localToScene(cubeOverlay.boundsInLocal).maxX<=right[0]) { "View cube overlaps the inspector" }
    }
    internal fun assertBodyFramed(bounds: Bounds) = command {
        val area=bodyFitArea()
        bounds.corners().forEach { corner ->
            val p=checkNotNull(projectCad(corner)) { "Body clipped behind camera" }
            check(p.u in area.left..area.left+area.width && p.v in area.top..area.top+area.height) { "Body outside usable area: $p, $area" }
        }
    }
    fun fit() = command { fitNow() }
    private fun fitNow() { state.panX = 0.0; state.panY = 0.0; state.zoom = 1.0; updateCamera() }
    fun setView(p: Double, y: Double) = command {
        state.pitch = p; state.yaw = y; updateCamera()
        val name = when {
            p == 25.0 && y == -35.0 -> "Iso"
            p == 0.0 && y == 0.0 -> "Vorne"
            p == 90.0 && y == 0.0 -> "Oben"
            p == 0.0 && y == 90.0 -> "Rechts"
            else -> ""
        }
        onUi { onOrientation(name) }
    }
    fun magnify(factor: Double) = command { state.magnify(factor); queueCameraUpdate() }
    fun clearModel() = command { clearModelNow() }
    private fun clearModelNow() {
        hasModel = false; modelCenter = Vec3(0.0,0.0,0.0); modelScale = 2.4
        faceGroup.children.clear();originPlanes.children.clear()
        sketchGroup.children.clear()
        meshGroup.children.clear(); meshView = null; emptyText.isVisible = true
        makeGrid(70.0); state.reset(); updateCamera()
    }
    fun alignToPlane(frame: SketchFrame) = command {
        val pose = CameraPose(state.yaw,state.pitch,state.panX,state.panY,state.zoom).normalTo(frame)
        state.yaw=pose.yaw;state.pitch=pose.pitch
        updateCamera()
        onUi { onOrientation("Normal auf Fläche") }
    }
    fun cameraPose() = command { CameraPose(state.yaw, state.pitch, state.panX, state.panY, state.zoom) }
    fun restoreCamera(pose: CameraPose) = command {
        state.yaw = pose.yaw; state.pitch = pose.pitch; state.panX = pose.panX; state.panY = pose.panY; state.zoom = pose.zoom
        updateCamera()
        val name = when {
            pose.yaw == -35.0 && pose.pitch == 25.0 -> "Iso"
            pose.yaw == 0.0 && pose.pitch == 90.0 -> "Oben"
            pose.yaw == 0.0 && pose.pitch == 0.0 -> "Vorne"
            pose.yaw == 90.0 && pose.pitch == 0.0 -> "Rechts"
            else -> ""
        }
        onUi { onOrientation(name) }
    }
    fun navigate(yawDelta: Double, pitchDelta: Double, dx: Double, dy: Double, zoom: Double) = command {
        state.yaw = (state.yaw + yawDelta) % 360
        state.pitch = (state.pitch + pitchDelta).coerceIn(-89.0, 89.0)
        panBy(dx, dy)
        state.magnify(zoom)
        updateCamera()
        if (yawDelta != 0.0 || pitchDelta != 0.0) onUi { onOrientation("") }
    }
    fun preview(maxSize: Int): CompletableFuture<ViewportImage> = command {
        require(scene.width > 0 && scene.height > 0) { "Die 3D-Ansicht ist noch nicht bereit." }
        val factor = min(1.0, maxSize / max(scene.width, scene.height))
        val width = (scene.width * factor).toInt().coerceAtLeast(1)
        val height = (scene.height * factor).toInt().coerceAtLeast(1)
        val parameters = SnapshotParameters().apply {
            fill = view.fill; transform = Scale(factor, factor)
        }
        val image = scene.root.snapshot(parameters, WritableImage(width, height))
        val output = ByteArrayOutputStream()
        check(ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output))
        ViewportImage(output.toByteArray(), width, height)
    }

    private fun panBy(dx: Double, dy: Double) {
        val ratio = 2 * abs(camera.translateZ) * tan(Math.toRadians(camera.fieldOfView / 2)) / view.height.coerceAtLeast(1.0)
        state.panX += dx * ratio; state.panY += dy * ratio
    }
    private fun queueCameraUpdate() {
        cameraUpdateQueued=true
        cameraPulse?.start()
    }
    private fun updateCamera() {
        pitch.angle = state.pitch; yaw.angle = state.yaw
        cubePitch.angle = state.pitch; cubeYaw.angle = state.yaw
        pan.translateX = state.panX; pan.translateY = state.panY
        camera.translateZ = -cameraBaseDistance(view.width,view.height,camera.fieldOfView) / state.zoom
        refreshOverlay()
    }
    fun navigationPerformance(): CompletableFuture<String> {
        val result=CompletableFuture<String>()
        ready.thenRun { Platform.runLater {
            val original=CameraPose(state.yaw,state.pitch,state.panX,state.panY,state.zoom)
            val gaps=mutableListOf<Double>();var previous=0L;var frames=0
            object: javafx.animation.AnimationTimer() {
                override fun handle(now: Long) {
                    if(previous>0 && frames>30) gaps.add((now-previous)/1_000_000.0)
                    previous=now;frames++
                    state.yaw=original.yaw+sin(frames*.04)*35
                    state.panX=original.panX+sin(frames*.03)*80
                    updateCamera()
                    if(frames>=180) {
                        stop();state.yaw=original.yaw;state.panX=original.panX;updateCamera()
                        val sorted=gaps.sorted();val p95=sorted[(sorted.lastIndex*.95).toInt()]
                        result.complete("frames=${gaps.size} meanMs=${gaps.average()} p95Ms=$p95 maxMs=${sorted.last()} pixelReadbacks=0")
                    }
                }
            }.start()
        } }
        return result
    }
    fun controlGlassSmokeCheck()=command {
        val expected=setOf("left","sidebar-toggle","main-header","view-controls-false","actions")
        check(glassRegions.keys.containsAll(expected)) { "Missing control backdrops: ${expected-glassRegions.keys}" }
        for(key in expected) {
            val r=glassRegions.getValue(key)
            check(backdrop.pane.clip.contains(r[0]+r[2]/2,r[1]+r[3]/2)) { "Unblurred control: $key" }
        }
        val raw=view.snapshot(SnapshotParameters(),null);val result=scene.root.snapshot(SnapshotParameters(),null)
        val x=view.width.toInt()-120;val y=108
        check(raw.pixelReader.getColor(x,y)==result.pixelReader.getColor(x,y)) { "View cube still has a background card" }
        println("CONTROL_GLASS_OK fileTools=true viewTools=true sketchActions=true titlebar=true cubeUnframed=true")
    }
    fun glassSmokeCheck(output: java.io.File): CompletableFuture<Unit> = command {
        check(view.width==panel.width.toDouble() && view.height==panel.height.toDouble()) { "Viewport does not cover the complete window" }
        check(glassRegions.containsKey("left") && backdrop.pane.isVisible) { "Missing sidebar backdrop" }
        val region=glassRegions.getValue("left")
        val raw=view.snapshot(SnapshotParameters(),null)
        val composited=scene.root.snapshot(SnapshotParameters(),null)
        val startX=region[0].toInt()+30;val endX=(region[0]+region[2]).toInt()-30
        val startY=region[1].toInt()+30;val endY=(region[1]+region[3]).toInt()-30
        fun variation(image: WritableImage): Double {
            val r=image.pixelReader;var sum=0.0
            for(y in startY until endY) for(x in startX until endX) {
                val a=r.getColor(x,y);val b=r.getColor(x+1,y)
                sum+=abs(a.red-b.red)+abs(a.green-b.green)+abs(a.blue-b.blue)
            }
            return sum
        }
        val sharpness=variation(raw);val blurred=variation(composited)
        check(sharpness>5.0 && blurred<sharpness*.75) { "Backdrop did not soften model edges: raw=$sharpness blurred=$blurred" }
        output.parentFile.mkdirs()
        ImageIO.write(SwingFXUtils.fromFXImage(raw,null),"png",java.io.File(output.parentFile,"glass-source.png"))
        ImageIO.write(SwingFXUtils.fromFXImage(composited,null),"png",java.io.File(output.parentFile,"glass-backdrop.png"))
        println("GLASS_OK fullCanvas=true liveBackdrop=true rawEdges=$sharpness blurredEdges=$blurred")
    }
    fun smokeCheck(): CompletableFuture<Unit> = command {
        check(Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D)) { "3D acceleration unavailable" }
        check(meshView != null && meshGroup.children.size == 1)
        meshView!!.drawMode = DrawMode.LINE
        check(meshView!!.drawMode == DrawMode.LINE)
        meshView!!.drawMode = DrawMode.FILL
        meshGroup.isVisible = false
        check(!meshGroup.isVisible)
        meshGroup.isVisible = true
        check(!System.getProperty("os.name").startsWith("Mac") || nativePinchInstalled) { "Native pinch bridge missing" }
        state.reset(); updateCamera()
    }.thenCompose {
        val result = CompletableFuture<Unit>()
        onUi {
            try {
                // Exercise actual AWT -> FX wheel routing, not just camera arithmetic.
                panel.dispatchEvent(MouseWheelEvent(panel, MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), InputEvent.META_DOWN_MASK, 50, 50, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1))
                command {
                    check(state.zoom > 1.0) { "AWT wheel input did not reach the FX camera" }
                    state.reset(); updateCamera()
                }.whenComplete { _, failure ->
                    if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
                }
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        result
    }
    private fun <T> command(action: () -> T): CompletableFuture<T> = ready.thenCompose {
        val result = CompletableFuture<T>()
        Platform.runLater {
            try { result.complete(action()) } catch (e: Throwable) { result.completeExceptionally(e) }
        }
        result
    }
    fun dispose() {
        macGestureListener?.let { listener ->
            Class.forName("com.apple.eawt.event.GestureUtilities")
                .getMethod("removeGestureListenerFrom", javax.swing.JComponent::class.java,
                    Class.forName("com.apple.eawt.event.GestureListener"))
                .invoke(null, panel, listener)
        }
        Platform.runLater { diagnostics?.stop();diagnostics=null;cameraPulse?.stop();backdrop.pane.children.clear();panel.scene = null }
    }
}

data class ViewportImage(val png: ByteArray, val width: Int, val height: Int)

internal fun onUi(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
}
