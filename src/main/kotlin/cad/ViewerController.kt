package cad

import androidx.compose.runtime.*
import kotlinx.serialization.json.*
import java.awt.FileDialog
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.KeyEvent
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

class ViewerController {
    var document by mutableStateOf(DocumentState())
        private set
    private val history = DocumentHistory()
    private var revision by mutableStateOf(0)
    private var savedState by mutableStateOf(DocumentState())
    var sceneModel by mutableStateOf<MeshData?>(null)
        private set
    val sceneUnit get() = if (document.effectiveFeatures().isNotEmpty()) "mm" else unit
    var performanceVisible by mutableStateOf(false)
    internal var performance by mutableStateOf<PerformanceSnapshot?>(null)
    var sketchesExpanded by mutableStateOf(true)
    var objectsExpanded by mutableStateOf(true)
    val model get() = document.mesh
    val sourcePath get() = document.source?.let { runCatching { Path.of(it) }.getOrNull() }
    val wireframe get() = document.display.wireframe
    val grid get() = document.display.grid
    val visible get() = document.display.visible
    val unit get() = document.display.unit
    val parameters get() = document.parameters
    var parameterValues by mutableStateOf<Map<String, Quantity>>(emptyMap())
        private set
    var projectPath by mutableStateOf<Path?>(null)
        private set
    val dirty get() = document != savedState
    val title get() = projectPath?.fileName?.toString() ?: model?.name ?: "Neues Projekt"
    val canUndo get(): Boolean { revision; return history.canUndo && !loading && !saving && !exporting && !sketchEditor && !parameterEditor && renamingSketchId==null && loftDraft==null && !loftWorking && !assistantTyping }
    val canRedo get(): Boolean { revision; return history.canRedo && !loading && !saving && !exporting && !sketchEditor && !parameterEditor && renamingSketchId==null && loftDraft==null && !loftWorking && !assistantTyping }
    var bridgeAvailable by mutableStateOf(false)
        private set
    private var bridge: LocalBridge? = null
    var loading by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    val canExport get() = (document.mesh!=null || document.effectiveFeatures().isNotEmpty()) && !loading && !saving && !exporting && !sketchEditor
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf(false)
        private set
    var sidebar by mutableStateOf(true)
    var about by mutableStateOf(false)
    internal var chatVisible by mutableStateOf(false)
    internal var chatMarking by mutableStateOf(false)
        private set
    internal var chatMarkRadius by mutableStateOf("10 mm")
    internal var chatReferences by mutableStateOf<List<ChatReference>>(emptyList())
        private set
    internal val canMarkChat get() = !sketchEditor && !loading && !saving && !exporting && !assistant.busy && !assistantSubmitting && !parameterEditor && renamingSketchId==null && primitiveDraft==null && loftDraft==null && bodyPositionDraft==null && regionExtrusionDraft==null && !faceExtrusionDialog && !selectingSupport
    internal fun toggleChatMarking() {
        if(!chatMarking && !canMarkChat)return
        chatMarking=!chatMarking;viewport.setChatMarking(chatMarking)
        if(chatMarking) { assistantTyping=false;releaseInputFocus() }
    }
    internal fun stopChatMarking() { chatMarking=false;viewport.setChatMarking(false) }
    internal fun clearChatReferences() { chatReferences=emptyList();chatMarking=false;viewport.setChatMarking(false);viewport.showChatReferences(emptyList()) }
    private fun markChatPoint(triangle:Int,point:Vec3,add:Boolean) {
        if(!chatMarking || !canMarkChat)return
        runCatching {
            val radius=ParameterEvaluator.expression(chatMarkRadius,parameterValues)
            require(radius.dimension==Dimension(1)) { "Der Markierungsradius benötigt eine Länge, z. B. 10 mm." }
            val previous=if(add)chatReferences else emptyList()
            require(previous.size<8) { "Höchstens acht Bereiche pro Nachricht markieren." }
            val reference=document.chatReference(triangle,point,radius.value,"Bereich ${previous.size+1}")
            chatReferences=previous+reference;viewport.showChatReferences(chatReferences);error=null
        }.onFailure(::reportError)
    }
    internal var assistantTyping by mutableStateOf(false)
    internal var releaseInputFocus: ()->Unit = {}
    internal val assistant=AssistantController(::executeAssistant)
    var inspector by mutableStateOf(false)
    var help by mutableStateOf(false)
    var showParameters by mutableStateOf(false)
    var selectingSupport by mutableStateOf(false)
    var selectedFace by mutableStateOf<SelectedFace?>(null)
        private set
    var selectedFaces by mutableStateOf<List<SelectedFace>>(emptyList())
        private set
    var faceExtrusionDialog by mutableStateOf(false)
        private set
    var faceExtrusionDepth by mutableStateOf("10 mm")
    var faceExtrusionFeatureIds by mutableStateOf<List<String>>(emptyList())
        private set
    var faceExtrusionError by mutableStateOf<String?>(null)
    var primitiveDraft by mutableStateOf<PrimitiveFeature?>(null)
        private set
    var primitiveEditingExisting by mutableStateOf(false)
        private set
    var primitiveError by mutableStateOf<String?>(null)
    data class BodyPositionDraft(val id:String,val x:String,val y:String,val z:String)
    data class BodyPositionPreview(val environment:MeshData?,val body:MeshData?)
    var bodyPositionDraft by mutableStateOf<BodyPositionDraft?>(null)
    var bodyPositionError by mutableStateOf<String?>(null)
    var sketchPreviewError by mutableStateOf<String?>(null)
        private set
    var sketchPreviewBodies by mutableStateOf(0)
        private set
    fun previewStatus(error: String?, bodies: Int) { sketchPreviewError=error;sketchPreviewBodies=bodies }
    internal var selectedRegions by mutableStateOf<Set<RegionReference>>(emptySet())
        private set
    internal var regionExtrusionDraft by mutableStateOf<RegionExtrusionDraft?>(null)
        private set
    internal var regionExtrusionError by mutableStateOf<String?>(null)
    private var regionExtrusionRevision=0
    internal fun selectRegion(reference:RegionReference,shift:Boolean) {
        if(sketchEditor || loading || regionExtrusionDraft!=null || selectingSupport) return
        if(document.pickableRegions().none { it.reference==reference }) return
        selectedSketchId=null;selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList());select(false)
        selectedRegions=if(shift) if(reference in selectedRegions) selectedRegions-reference else selectedRegions+reference else setOf(reference)
        viewport.highlightRegions(selectedRegions)
    }
    internal fun clearRegionSelection() { selectedRegions=emptySet();viewport.highlightRegions(emptySet()) }
    internal fun startRegionExtrusion() {
        require(!sketchEditor && !loading && !saving && !exporting && selectedRegions.isNotEmpty()) { "Wähle fertige Skizzenregionen." }
        val sources=selectedRegions.sortedWith(compareBy({it.sketchId},{it.regionId}))
        regionExtrusionDraft=RegionExtrusionDraft(sources,sources.map { "extrusion-${java.util.UUID.randomUUID()}" })
        regionExtrusionRevision=revision;regionExtrusionError=null
    }
    internal fun updateRegionDepth(depth:String) { regionExtrusionDraft=regionExtrusionDraft?.copy(depth=depth) }
    private fun regionCandidate():DocumentState {
        require(regionExtrusionRevision==revision) { "Das Projekt wurde geändert. Bitte die Regionsauswahl erneut starten." }
        return checkNotNull(regionExtrusionDraft).applyTo(document)
    }
    internal fun previewRegionExtrusion():List<MeshData> {
        val draft=regionExtrusionDraft ?: return emptyList()
        return regionCandidate().sceneBodies().filter { it.id in draft.ids }.map { it.mesh }
    }
    internal fun finishRegionExtrusion():CompletableFuture<Unit> {
        val result=commit(regionCandidate())
        cancelRegionExtrusion();clearRegionSelection()
        return result
    }
    internal fun cancelRegionExtrusion() { regionExtrusionDraft=null;regionExtrusionError=null;viewport.setFeaturePreview(emptyList()) }
    var selectedSketchId by mutableStateOf<String?>(null)
    var extruding by mutableStateOf(false)
        private set
    var extrusionRegionId by mutableStateOf<String?>(null)
        private set
    var extrusionFeatureId by mutableStateOf<String?>(null)
        private set
    var extrusionRegionIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var extrusionFeatureIds by mutableStateOf<Map<String,String>>(emptyMap())
        private set
    var extrusionChooserSelection by mutableStateOf<Set<String>>(emptySet())
        private set
    var shiftHeld by mutableStateOf(false)
        private set
    var extrusionRegionChooserSketchId by mutableStateOf<String?>(null)
        private set
    var sketchCentered by mutableStateOf(false)
    var sketchTool by mutableStateOf(SketchDrawingTool.RECTANGLE)
    var selectedSketchElementId by mutableStateOf<String?>(null)
    var constraintSelection by mutableStateOf<Set<String>>(emptySet())
    var constraintStatus by mutableStateOf<String?>(null)
    var sketchSnap by mutableStateOf(true)
    var sketchHasProfile by mutableStateOf(false)
        private set
    var sketchDraft by mutableStateOf<Sketch?>(null)
    var sketchSupportLabel by mutableStateOf("")
        private set
    private var completingSketch = false
    var sketchEditor by mutableStateOf(false)
    var editingSketchId by mutableStateOf<String?>(null)
    var renamingSketchId by mutableStateOf<String?>(null)
    var parameterEditor by mutableStateOf(false)
    var editingParameterName by mutableStateOf<String?>(null)
    var currentView by mutableStateOf("Iso")
        private set
    val viewport = Viewport3D(onSelection = { selected = it }, onOrientation = { currentView = it }, onFacePicked = { face,shift -> pickSceneFace(face,shift) }, onPlanePicked = { chooseSketchPlane(it) }, onSketchDrawn = { updateSketchDraft(it) }, onDrawingError = { reportError(IllegalArgumentException(it)) },onViewportFocus={ assistantTyping=false;releaseInputFocus() },onRegionPicked=::selectRegion,onChatPointPicked=::markChatPoint)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "project-io").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var owner: Window? = null
    private var keyHandler: KeyEventDispatcher? = null

    fun attach(window: Window) {
        owner = window
        viewport.routeOverlayInput(window)
        if (keyHandler != null) return
        runCatching { bridge = LocalBridge { executeCommand(it) }; bridgeAvailable = true }
            .onFailure { System.err.println("MCP bridge unavailable: ${it.message}") }
        keyHandler = KeyEventDispatcher { e ->
            val fromWindow = e.component != null && SwingUtilities.getWindowAncestor(e.component) === window
            if(fromWindow && e.keyCode==KeyEvent.VK_SHIFT) {
                shiftHeld=e.id==KeyEvent.KEY_PRESSED
            }
            if (e.id != KeyEvent.KEY_PRESSED || !fromWindow || assistantTyping) false else when {
                (e.isMetaDown || e.isControlDown) && e.keyCode == KeyEvent.VK_N -> { newProject(); true }
                (e.isMetaDown || e.isControlDown) && e.keyCode == KeyEvent.VK_O -> { chooseFile(); true }
                (e.isMetaDown || e.isControlDown) && e.keyCode == KeyEvent.VK_S -> { saveFromDialog(e.isShiftDown); true }
                (e.isMetaDown || e.isControlDown) && e.keyCode == KeyEvent.VK_Z && !sketchEditor && !parameterEditor && renamingSketchId==null -> { if (e.isShiftDown) redo() else undo(); true }
                e.keyCode == KeyEvent.VK_F && !sketchEditor && !parameterEditor && renamingSketchId==null && !e.isMetaDown && !e.isControlDown && !e.isAltDown -> { fit(); true }
                e.keyCode == KeyEvent.VK_ESCAPE && sketchEditor -> { viewport.cancelPendingDrawing(); false }
                e.keyCode == KeyEvent.VK_ESCAPE && !sketchEditor -> { clearChatReferences();clearRegionSelection();if (selectingSupport) cancelSupportSelection() else { selectedFace=null;selectedFaces=emptyList(); viewport.highlightFaces(emptyList());select(false) }; true }
                else -> false
            }
        }.also { KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(it) }
        if (java.awt.Desktop.isDesktopSupported()) {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler { _, response ->
                onUi { requestClose({ close(); response.performQuit() }, { response.cancelQuit() }) }
            }
        }
        viewport.panel.dropTarget = DropTarget(viewport.panel, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun dragOver(event: DropTargetDragEvent) {
                if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) event.acceptDrag(DnDConstants.ACTION_COPY) else event.rejectDrag()
            }
            override fun drop(event: DropTargetDropEvent) {
                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
                event.acceptDrop(DnDConstants.ACTION_COPY)
                try {
                    val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
                    val path = files.filterIsInstance<File>().firstOrNull { supported(it.toPath()) }?.toPath()
                    event.dropComplete(path != null)
                    if (path != null) onUi { continueIfSaved(next = { open(path, true) }) }
                } catch (e: Exception) { event.dropComplete(false); onUi { reportError(e) } }
            }
        }, true)
    }
    fun newProject() {
        if (loading || saving || exporting) return
        continueIfSaved(next = {
            generation.incrementAndGet()
            val next=DocumentState()
            history.reset(next); publish(next); savedState=next; projectPath=null
            selected=false; cancelSketch(); selectedFace=null;selectedFaces=emptyList(); parameterEditor=false; renamingSketchId=null; error=null
            viewport.setContent(null,emptyList(),true).thenCompose { renderDisplay(next.display) }
            assistant.newConversation();assistant.projectChanged("new")
            currentView="Iso"
        })
    }
    fun chooseFile() {
        if (saving || exporting) return
        val dialog = FileDialog(owner as? java.awt.Frame, "Projekt, STL oder OBJ öffnen", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in setOf("stl", "obj", "kcad") }
        dialog.isVisible = true
        dialog.file?.let { file -> val path = Path.of(dialog.directory, file); continueIfSaved(next = { open(path, true) }) }
        dialog.dispose()
    }
    fun open(path: Path, discardChanges: Boolean = false): CompletableFuture<MeshData?> {
        if (saving || exporting || ((dirty || sketchEditor) && !discardChanges)) return CompletableFuture.failedFuture(IllegalStateException(
            if (saving || exporting) "Ein Dateivorgang läuft noch." else "Ungespeicherte Änderungen: zuerst speichern oder discardChanges=true setzen."))
        val request = generation.incrementAndGet()
        loading = true; error = null
        val project = path.fileName.toString().lowercase().endsWith(".kcad")
        val future = CompletableFuture.supplyAsync({
            if (project) ProjectIO.read(path)
            else ProjectArchive(DocumentState(MeshImporter.read(path), path.toAbsolutePath().normalize().toString()))
        }, executor).thenCompose { archive ->
            viewport.setContent(archive.document.renderMesh(), archive.document.sketchGeometry(), true,archive.document.framingBounds(),colors=archive.document.renderColors()) { request == generation.get() }
                .thenCompose { renderDisplay(archive.document.display) }.thenCompose { viewport.restoreCamera(archive.camera) }
                .thenApply { archive }
        }
        val completed = CompletableFuture<MeshData?>()
        future.whenComplete { archive, failure -> onUi {
            if (request != generation.get()) {
                completed.completeExceptionally(java.util.concurrent.CancellationException("Ladevorgang ersetzt"))
            } else {
                loading = false
                if (failure == null) {
                    history.reset(archive.document); publish(archive.document); savedState = archive.document
                    projectPath = if (project) path.toAbsolutePath().normalize() else null
                    selected = false; parameterEditor = false; renamingSketchId=null; cancelSketch(); selectedFace=null;selectedFaces=emptyList()
                    assistant.projectChanged(assistantProject())
                    completed.complete(archive.document.mesh)
                } else { reportError(failure.cause ?: failure); completed.completeExceptionally(failure) }
            }
        } }
        return completed
    }
    fun saveProject(path: Path, overwrite: Boolean = false): CompletableFuture<Unit> {
        if (loading || saving || exporting) return CompletableFuture.failedFuture(IllegalStateException("Ein Dateivorgang läuft noch."))
        if (sketchEditor) return CompletableFuture.failedFuture(IllegalStateException("Skizze zuerst fertigstellen oder abbrechen."))
        val state = document
        val request = generation.get()
        saving = true; error = null
        val completed = CompletableFuture<Unit>()
        viewport.cameraPose().thenCompose { camera ->
            CompletableFuture.runAsync({ ProjectIO.write(path, ProjectArchive(state, camera), overwrite) }, executor)
        }.whenComplete { _, failure -> onUi {
            saving = false
            if (failure == null) {
                if (request == generation.get()) { savedState = state; projectPath = path.toAbsolutePath().normalize();assistant.rebindProject(assistantProject()) }
                completed.complete(Unit)
            } else { reportError(failure.cause ?: failure); completed.completeExceptionally(failure) }
        } }
        return completed
    }
    fun exportMeshes(path: Path,overwrite: Boolean=false): CompletableFuture<MeshExportResult> {
        if(loading || saving || exporting || sketchEditor) return CompletableFuture.failedFuture(IllegalStateException(
            if(sketchEditor) "Skizze zuerst fertigstellen oder abbrechen." else "Ein Dateivorgang läuft noch."))
        val snapshot=document
        val request=generation.get()
        exporting=true;error=null;notice=null
        val result=CompletableFuture<MeshExportResult>()
        CompletableFuture.supplyAsync({ MeshExporter.write(path,snapshot,overwrite) },executor).whenComplete { exported,failure -> onUi {
            exporting=false
            if(failure==null) {
                if(request==generation.get()) notice="Exportiert · ${exported.path.fileName}"
                result.complete(exported)
            } else { reportError(failure.cause ?: failure);result.completeExceptionally(failure) }
        } }
        return result
    }
    fun exportFromDialog(format: MeshFormat) {
        if(!canExport) return
        val dialog=FileDialog(owner as? java.awt.Frame,"Als ${format.name} exportieren",FileDialog.SAVE)
        val name=projectPath?.fileName?.toString()?.substringBeforeLast('.') ?: model?.name?.substringBeforeLast('.') ?: "Modell"
        dialog.file="$name.${format.extension}"
        projectPath?.parent?.let { dialog.directory=it.toString() }
        dialog.isVisible=true
        val file=dialog.file
        val path=if(file==null) null else Path.of(dialog.directory,if(file.lowercase().endsWith(".${format.extension}")) file else "$file.${format.extension}")
        dialog.dispose()
        if(path==null) return
        val exists=java.nio.file.Files.exists(path)
        if(exists && JOptionPane.showConfirmDialog(owner,"Die vorhandene Exportdatei ersetzen?","Exportieren",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION) return
        exportMeshes(path,exists)
    }
    fun saveFromDialog(saveAs: Boolean = false): CompletableFuture<Boolean> {
        if (sketchEditor) { reportError(IllegalStateException("Skizze zuerst fertigstellen oder abbrechen."));return CompletableFuture.completedFuture(false) }
        if (loading || saving || exporting) return CompletableFuture.completedFuture(false)
        var path = if (saveAs) null else projectPath
        if (path == null) {
            val dialog = FileDialog(owner as? java.awt.Frame, "CAD-Projekt speichern", FileDialog.SAVE)
            dialog.file = projectPath?.fileName?.toString() ?: ((model?.name?.substringBeforeLast('.') ?: "Projekt") + ".kcad")
            dialog.isVisible = true
            val file = dialog.file
            if (file != null) path = Path.of(dialog.directory, if (file.lowercase().endsWith(".kcad")) file else "$file.kcad")
            dialog.dispose()
            if (path == null) return CompletableFuture.completedFuture(false)
            if (java.nio.file.Files.exists(path) && JOptionPane.showConfirmDialog(owner,
                "Die vorhandene Projektdatei ersetzen?", "Projekt speichern", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                return CompletableFuture.completedFuture(false)
        }
        return saveProject(path, true).handle { _, failure -> failure == null }
    }
    private fun continueIfSaved(next: () -> Unit, cancelled: () -> Unit = {}) {
        if (sketchEditor) {
            if (JOptionPane.showConfirmDialog(owner,"Den offenen Skizzenentwurf verwerfen?","Skizzenbearbeitung",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) { cancelled();return }
            cancelSketch()
        }
        if (!dirty) { next(); return }
        val choice = JOptionPane.showOptionDialog(owner, "Änderungen an diesem Projekt speichern?", "Ungespeicherte Änderungen",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, arrayOf("Speichern", "Verwerfen", "Abbrechen"), "Speichern")
        when (choice) {
            0 -> saveFromDialog().thenAccept { saved -> onUi { if (saved) next() else cancelled() } }
            1 -> next()
            else -> cancelled()
        }
    }
    fun requestClose(next: () -> Unit, cancelled: () -> Unit = {}) {
        if (!saving && !exporting) continueIfSaved(next, cancelled) else cancelled()
    }
    fun setParameter(inputName: String, expression: String) {
        val name=ParameterEvaluator.name(inputName)
        require(!loading) { "Datei wird geladen." }
        val next = parameters.toMutableList()
        val index = next.indexOfFirst { it.name == name }
        if (index < 0) next.add(Parameter(name, expression)) else next[index] = Parameter(name, expression)
        ParameterEvaluator.evaluate(next)
        commit(document.copy(parameters = next.toList()))
    }
    fun removeParameter(inputName: String) {
        val name=ParameterEvaluator.name(inputName)
        require(parameters.any { it.name == name }) { "Parameter existiert nicht." }
        val next = parameters.filterNot { it.name == name }
        ParameterEvaluator.evaluate(next)
        commit(document.copy(parameters = next))
    }
    fun editParameter(name: String? = null) { if(sketchEditor) return; editingParameterName = name; parameterEditor = true }
    fun editSketch(id: String? = null) {
        if (loading || saving || exporting || sketchEditor) return
        if (id != null) {
            val sketch=document.evaluatedSketches().first { it.id==id }
            beginSketch(sketch,if(sketch.frame!=null) "Körperfläche" else "Ebene ${sketch.plane}")
        } else selectedFace?.let { face -> beginSketch(Sketch(name="Skizze ${document.sketches.size+1}",frame=face.frame,support=face.reference),face.label) }
            ?: run { selectingSupport=true;viewport.showOriginPlanes(true) }
    }
    fun selectSketch(id: String) { clearRegionSelection();select(false);selectedSketchId=id;selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList()) }
    fun renameSketch(id: String,name: String) = commit(document.renameSketch(id,name))
    fun duplicateSketch(id: String): CompletableFuture<Unit> {
        val next=document.duplicateSketch(id)
        val result=commit(next)
        selectSketch(next.sketches.last().id)
        return result
    }
    fun isolateBody(id: String) = changeBodyDisplay(document.isolateBody(id))
    fun showAllBodies() = changeBodyDisplay(document.showAllBodies())
    private fun changeBodyDisplay(next: DocumentState): CompletableFuture<Unit> {
        val result=commit(next)
        selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList());select(false)
        return result
    }
    fun setBodyVisibility(id: String,visible: Boolean): CompletableFuture<Unit> {
        return changeBodyDisplay(document.setBodyVisibility(id,visible))
    }
    fun setSketchVisibility(id: String,visible: Boolean): CompletableFuture<Unit> {
        require(document.sketches.any { it.id==id }) { "Skizze existiert nicht." }
        return commit(document.copy(sketches=document.sketches.map { if(it.id==id) it.copy(visible=visible) else it }))
    }
    fun startExtrusion(id: String,regionId:String?=null) {
        if(loading || saving || exporting || sketchEditor) return
        val sketch=document.evaluatedSketches().first { it.id==id }
        val regions=sketch.regions(parameterValues)
        require(regions.isNotEmpty()) { "Diese Skizze enthält keine geschlossene Extrusionsregion." }
        if(regionId==null && regions.size>1) { selectedSketchId=id;extrusionRegionChooserSketchId=id;extrusionChooserSelection=emptySet();return }
        val region=if(regionId==null) regions.singleOrNull() else regions.firstOrNull { it.id==regionId }
        requireNotNull(region) { "Extrusionsregion fehlt." }
        startExtrusion(id,setOf(region.id))
    }
    fun toggleExtrusionRegion(regionId:String,selected:Boolean) {
        extrusionChooserSelection=if(selected) extrusionChooserSelection+regionId else extrusionChooserSelection-regionId
    }
    fun chooseExtrusionRegion(regionId:String) {
        val chosen=regionId in extrusionChooserSelection
        extrusionChooserSelection=if(shiftHeld) {
            if(chosen) extrusionChooserSelection-regionId else extrusionChooserSelection+regionId
        } else if(chosen && extrusionChooserSelection.size==1) emptySet() else setOf(regionId)
    }
    fun startExtrusion(id:String,regionIds:Set<String>) {
        if(loading || saving || exporting || sketchEditor) return
        val sketch=document.evaluatedSketches().first { it.id==id }
        val regions=sketch.regions(parameterValues)
        require(regionIds.isNotEmpty() && regionIds.all { target -> regions.any { it.id==target } }) { "Wähle mindestens eine gültige Extrusionsregion." }
        val current=document.effectiveFeatures().filterIsInstance<ExtrusionFeature>()
        val existing=regionIds.associateWith { region -> current.firstOrNull { it.region==RegionReference(id,region) } }
        extrusionRegionIds=regionIds
        extrusionFeatureIds=existing.mapValues { (_,feature) -> feature?.id ?: "extrusion-${java.util.UUID.randomUUID()}" }
        extrusionRegionId=regionIds.singleOrNull()
        extrusionFeatureId=extrusionRegionId?.let(extrusionFeatureIds::get)
        extrusionRegionChooserSketchId=null
        beginSketch(sketch.copy(depth=regionIds.firstNotNullOfOrNull { existing[it]?.depth } ?: "10 mm"),"Aus Skizze · ${sketch.name} · ${regionIds.joinToString()}")
        extruding=true
    }
    private fun beginSketch(sketch: Sketch,label: String) {
        extruding=false;selectedSketchId=sketch.id
        constraintSelection=emptySet();constraintStatus=null
        sketchHasProfile=document.sketches.any { it.id==sketch.id }
        sketchCentered=sketch.centered
        val element=sketch.editableElements().firstOrNull()
        selectedSketchElementId=sketch.elements.firstOrNull()?.id
        sketchTool=when {
            element==null -> SketchDrawingTool.RECTANGLE
            element.kind==SketchElementKind.LINE -> SketchDrawingTool.LINE
            element.kind==SketchElementKind.POLYLINE -> SketchDrawingTool.POLYLINE
            element.kind==SketchElementKind.ARC -> SketchDrawingTool.ARC
            element.centered -> SketchDrawingTool.CENTER_RECTANGLE
            element.shape==SketchShape.CIRCLE -> SketchDrawingTool.CIRCLE
            element.shape==SketchShape.TRIANGLE -> SketchDrawingTool.TRIANGLE
            else -> SketchDrawingTool.RECTANGLE
        }
        editingSketchId=sketch.id;sketchDraft=sketch;sketchSupportLabel=label
        sketchEditor=true;selectingSupport=false;viewport.showOriginPlanes(false)
    }
    fun chooseSketchPlane(plane: SketchPlane) {
        if (!selectingSupport || sketchEditor) return
        selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList())
        beginSketch(Sketch(name="Skizze ${document.sketches.size+1}",plane=plane),"Ebene $plane")
    }
    fun pickSceneFace(triangle: Int?) {
        pickSceneFace(triangle,false)
    }
    fun pickSceneFace(triangle: Int?,shift:Boolean) {
        if (sketchEditor || loading || regionExtrusionDraft!=null) return
        if(triangle!=null || !shift) clearRegionSelection()
        if (triangle!=null) selectedSketchId=null
        if (triangle==null) { if(!shift) { selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList()) };return }
        runCatching { document.selectFace(triangle) }.onSuccess { face ->
            val key=face.reference.featureId to (face.reference.surfaceId ?: face.reference.triangle.toString())
            val old=selectedFaces.toMutableList()
            selectedFaces=if(shift) {
                val found=old.indexOfFirst { (it.reference.featureId to (it.reference.surfaceId ?: it.reference.triangle.toString()))==key }
                if(found>=0) old.apply { removeAt(found) } else old.apply { add(face) }
            } else mutableListOf(face)
            selectedFace=selectedFaces.lastOrNull();viewport.highlightFaces(selectedFaces.map { it.patch });error=null
            if(selectingSupport) beginSketch(Sketch(name="Skizze ${document.sketches.size+1}",frame=face.frame,support=face.reference),face.label)
        }.onFailure { selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList());reportError(it) }
    }
    fun startFaceExtrusion() {
        require(!sketchEditor && selectedFaces.isNotEmpty()) { "Wähle eine oder mehrere Körperflächen." }
        selectedFaces.forEach { face ->
            val source=face.reference.featureId
            require(source!=null && source!="import" && document.effectiveFeatures().any { it.id==source }) {
                "Importierte Netzflächen lassen sich nicht zuverlässig rekonstruieren. Wähle eine Fläche eines parametrischen Körpers."
            }
            val body=document.sceneBodies().firstOrNull { it.id==source }
            require(body?.sketch!=null) {
                "Diese Körperfläche stammt aus einer rekonstruierten Netzoperation. Wähle eine Fläche eines ursprünglichen parametrischen Körpers."
            }
            val sourceSketch=document.evaluatedSketches().firstOrNull { it.id==body.sketch.id }
            val sourceRegion=sourceSketch?.regions(parameterValues)?.firstOrNull { it.id==body.regionId }
            require(sourceRegion?.holeElementIds.isNullOrEmpty()) {
                "Die Flächenreferenz dieser Mehrkontur-Geometrie ist nicht stabil genug zum Extrudieren."
            }
        }
        faceExtrusionDepth="10 mm";faceExtrusionError=null;faceExtrusionFeatureIds=selectedFaces.map { "face-extrusion-${java.util.UUID.randomUUID()}" };faceExtrusionDialog=true
    }
    fun previewFaceExtrusion():List<MeshData> {
        if(!faceExtrusionDialog) return emptyList()
        val additions=selectedFaces.zip(faceExtrusionFeatureIds).map { (face,id) -> FaceExtrusionFeature(id,face.reference,faceExtrusionDepth) }
        val candidate=document.copy(features=document.effectiveFeatures()+additions).canonicalFeatures()
        return candidate.sceneBodies().filter { it.id in faceExtrusionFeatureIds }.map { it.mesh }
    }
    fun finishFaceExtrusion():CompletableFuture<Unit> {
        require(faceExtrusionDialog && faceExtrusionFeatureIds.isNotEmpty())
        val additions=selectedFaces.zip(faceExtrusionFeatureIds).map { (face,id) -> FaceExtrusionFeature(id,face.reference,faceExtrusionDepth) }
        val result=commit(document.copy(features=document.effectiveFeatures()+additions))
        faceExtrusionDialog=false;faceExtrusionFeatureIds=emptyList();faceExtrusionError=null;viewport.setFeaturePreview(emptyList())
        return result
    }
    fun cancelFaceExtrusion() { faceExtrusionDialog=false;faceExtrusionFeatureIds=emptyList();faceExtrusionError=null;viewport.setFeaturePreview(emptyList()) }
    internal fun constructionCommand(arguments:JsonObject,action:String):CompletableFuture<JsonObject> {
        require(!loading && !saving && !exporting && !sketchEditor && !parameterEditor && loftDraft==null && primitiveDraft==null) { "Beende zuerst den aktuellen Vorgang." }
        val snapshot=document;val update=action=="cad_update_construction";val preview=action=="cad_preview_construction"
        if(preview) viewport.setFeaturePreview(emptyList())
        val old=if(update) snapshot.effectiveFeatures().filterIsInstance<NativeShapeFeature>().firstOrNull { it.id==arguments.requiredString("id") } ?: error("Konstruktionsfeature fehlt.") else null
        val feature=NativeShapeFeature(old?.id ?: "construction-${java.util.UUID.randomUUID()}",arguments["name"]?.jsonPrimitive?.content ?: old?.name ?: "Konstruktion",
            arguments.getValue("shape").jsonObject,old?.x ?: "0 mm",old?.y ?: "0 mm",old?.z ?: "0 mm",true)
        return CompletableFuture.supplyAsync {
            val next=snapshot.withConstruction(feature,constructionParameters(arguments),update)
            next to next.constructionInfo(feature.id)
        }.thenCompose { (next,info) -> uiFuture {
            require(document===snapshot) { "Projekt geändert; Konstruktion erneut prüfen." }
            if(preview) viewport.setFeaturePreview(listOf(next.sceneBodies().first { it.id==feature.id }.mesh)).thenApply { JsonObject(info+mapOf("modelChanged" to JsonPrimitive(false),"provisional" to JsonPrimitive(true))) }
            else commit(next).thenCompose { viewport.setFeaturePreview(emptyList()) }.thenApply { JsonObject(info+mapOf("modelChanged" to JsonPrimitive(true),"provisional" to JsonPrimitive(false))) }
        }.thenCompose { it } }
    }
    internal fun deleteGeneratedFeature(id:String):CompletableFuture<Unit> {
        require(!saving && !parameterEditor && loftDraft==null && primitiveDraft==null) { "Beende zuerst die Bearbeitung." }
        return commit(document.withoutFeature(id))
    }
    internal fun exportSelectedBodies(path:Path,ids:List<String>,orientation:JsonObject,overwrite:Boolean):CompletableFuture<JsonObject> {
        require(!loading && !saving && !exporting && !sketchEditor) { "Beende zuerst den aktuellen Vorgang." }
        val snapshot=document;exporting=true
        val future=CompletableFuture.supplyAsync { val parts=snapshot.exportParts(ids,orientation);exportedPartsInfo(MeshExporter.writeParts(path,parts,overwrite),ids,parts) }
        future.whenComplete { _,_ -> onUi { exporting=false } };return future
    }
    var loftDraft by mutableStateOf<LoftFeature?>(null)
    var loftError by mutableStateOf<String?>(null)
    var loftWorking by mutableStateOf(false)
    private var loftExisting=false
    fun startLoft(id:String?=null) {
        require(canMarkChat && loftDraft==null) { "Beende zuerst den aktuellen Vorgang." }
        loftDraft=if(id==null) LoftFeature("loft-${java.util.UUID.randomUUID()}",sections=listOf(LoftSection(),LoftSection(z="30 mm")))
            else document.effectiveFeatures().filterIsInstance<LoftFeature>().first { it.id==id }
        loftExisting=id!=null;loftError=null
    }
    fun cancelLoft() { if(loftWorking) return;loftDraft=null;loftError=null;viewport.setFeaturePreview(emptyList()) }
    fun previewLoft(feature:LoftFeature):CompletableFuture<JsonObject> {
        val snapshot=document
        return CompletableFuture.supplyAsync { feature.evaluateMesh(ParameterEvaluator.evaluate(snapshot.parameters)).translated(feature.position(ParameterEvaluator.evaluate(snapshot.parameters))) }.thenCompose { mesh -> uiFuture {
            require(document===snapshot) { "Projekt geändert; Vorschau erneut erstellen." }
            viewport.setFeaturePreview(listOf(mesh)).thenApply { feature.result(mesh) }
        }.thenCompose { it } }
    }
    fun storeLoft(feature:LoftFeature,update:Boolean):CompletableFuture<Unit> {
        require(!loading && !saving && !exporting && !sketchEditor && !parameterEditor) { "Beende zuerst den aktuellen Vorgang." }
        val snapshot=document
        return CompletableFuture.supplyAsync { snapshot.withLoft(feature,update) }.thenCompose { next -> uiFuture {
            require(document===snapshot) { "Projekt geändert; Loft erneut prüfen." }
            commit(next)
        }.thenCompose { it } }
    }
    fun runLoftDraft(create:Boolean) {
        val feature=loftDraft ?: return
        if(loftWorking) return
        loftWorking=true;loftError=null
        val operation=try { if(create) storeLoft(feature,loftExisting) else previewLoft(feature) } catch(e:Exception) { loftWorking=false;loftError=e.message;return }
        operation.whenComplete { _,failure -> onUi {
            loftWorking=false
            if(failure!=null) { loftError=(failure.cause ?: failure).message;viewport.setFeaturePreview(emptyList()) }
            else if(create) cancelLoft()
        } }
    }
    fun startPrimitive(kind:PrimitiveKind) {
        require(!sketchEditor && !loading && !saving && !exporting) { "Beende zuerst den aktuellen Vorgang." }
        primitiveDraft=PrimitiveFeature("primitive-${java.util.UUID.randomUUID()}",when(kind) { PrimitiveKind.BOX->"Quader";PrimitiveKind.CYLINDER->"Zylinder";PrimitiveKind.SPHERE->"Kugel" },kind)
        primitiveEditingExisting=false;primitiveError=null
    }
    fun editPrimitive(id:String) {
        require(!sketchEditor) { "Beende zuerst die Skizzenbearbeitung." }
        primitiveDraft=document.effectiveFeatures().filterIsInstance<PrimitiveFeature>().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Grundkörper existiert nicht.")
        primitiveEditingExisting=true;primitiveError=null
    }
    fun updatePrimitive(feature:PrimitiveFeature) { require(primitiveDraft?.id==feature.id);primitiveDraft=feature;primitiveError=null }
    fun previewPrimitive():MeshData?=primitiveDraft?.evaluateMesh(parameterValues)
    fun finishPrimitive():CompletableFuture<Unit> {
        val feature=checkNotNull(primitiveDraft) { "Kein Grundkörper wird bearbeitet." }
        val features=if(primitiveEditingExisting) document.effectiveFeatures().map { if(it.id==feature.id) feature else it } else document.effectiveFeatures()+feature
        val result=commit(document.copy(features=features))
        primitiveDraft=null;primitiveEditingExisting=false;primitiveError=null;viewport.setFeaturePreview(emptyList())
        return result
    }
    fun cancelPrimitive() { primitiveDraft=null;primitiveEditingExisting=false;primitiveError=null;viewport.setFeaturePreview(emptyList()) }
    fun createPrimitive(feature:PrimitiveFeature):CompletableFuture<Unit> {
        require(!sketchEditor) { "Beende zuerst die Skizzenbearbeitung." }
        return commit(document.copy(features=document.effectiveFeatures()+feature))
    }
    fun previewMeshReconstruction(proposalId:String):CompletableFuture<JsonObject> {
        require(!sketchEditor && !loading && !saving && !exporting) { "Beende zuerst den aktuellen Vorgang." }
        val snapshot=document;val completion=CompletableFuture<JsonObject>()
        MeshReconstructionJobs.previewAsync(snapshot,proposalId).whenComplete { result,failure -> onUi {
            when {
                failure!=null -> completion.completeExceptionally(failure.cause ?: failure)
                document!==snapshot -> completion.completeExceptionally(IllegalStateException("Das Projekt wurde während der Vorschau geändert. Erstelle die Vorschau erneut."))
                else -> {
                    val body=result.second
                    viewport.setFeaturePreview(listOf(body)).whenComplete { _,renderFailure ->
                        if(renderFailure!=null) completion.completeExceptionally(renderFailure.cause ?: renderFailure)
                        else runCatching {
                            val proposal=MeshReconstructionJobs.get(proposalId)
                            completion.complete(JsonObject(proposal.description(snapshot.display.unit)+mapOf("preview" to buildJsonObject {
                                put("dimensions",buildJsonObject { put("x",body.bounds.size.x);put("y",body.bounds.size.y);put("z",body.bounds.size.z) })
                                put("bounds",buildJsonObject { put("min",buildJsonObject { put("x",body.bounds.low.x);put("y",body.bounds.low.y);put("z",body.bounds.low.z) });put("max",buildJsonObject { put("x",body.bounds.high.x);put("y",body.bounds.high.y);put("z",body.bounds.high.z) }) })
                                put("valid",true)
                            })))
                        }.onFailure(completion::completeExceptionally)
                    }
                }
            }
        } }
        return completion
    }
    fun acceptMeshReconstruction(proposalId:String):CompletableFuture<Unit> {
        require(!sketchEditor && !loading && !saving && !exporting) { "Beende zuerst den aktuellen Vorgang." }
        val candidate=MeshReconstructionJobs.get(proposalId).apply(document)
        viewport.setFeaturePreview(emptyList())
        return commit(candidate).thenApply { MeshReconstructionJobs.remove(proposalId);Unit }
    }
    fun discardMeshReconstruction(proposalId:String) {
        viewport.setFeaturePreview(emptyList());MeshReconstructionJobs.remove(proposalId)
    }
    fun setBodyPosition(id:String,x:String,y:String,z:String):CompletableFuture<Unit> {
        require(!sketchEditor && !loading && !saving && !exporting) { "Beende zuerst den aktuellen Vorgang." }
        val features=document.effectiveFeatures()
        require(features.any { it.id==id }) { "Der Körper besitzt keine parametrische Featuredefinition." }
        val moved=features.map { if(it.id==id) it.withPosition(x,y,z) else it }
        return commit(document.copy(features=moved))
    }
    fun startBodyPosition(id:String) {
        require(!sketchEditor && !loading && !saving && !exporting) { "Beende zuerst den aktuellen Vorgang." }
        val feature=document.effectiveFeatures().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Der Körper besitzt keine parametrische Positionsdefinition.")
        val (x,y,z)=when(feature) { is PrimitiveFeature -> Triple(feature.x,feature.y,feature.z);is ExtrusionFeature -> Triple(feature.x,feature.y,feature.z);is FaceExtrusionFeature -> Triple(feature.x,feature.y,feature.z);is MirrorFeature -> Triple(feature.x,feature.y,feature.z);is LoftFeature -> Triple(feature.x,feature.y,feature.z);is NativeShapeFeature -> Triple(feature.x,feature.y,feature.z);is InstanceFeature -> Triple(feature.x,feature.y,feature.z) }
        bodyPositionDraft=BodyPositionDraft(id,x,y,z);bodyPositionError=null
    }
    fun updateBodyPositionDraft(draft:BodyPositionDraft) { require(bodyPositionDraft?.id==draft.id);bodyPositionDraft=draft;bodyPositionError=null }
    fun nudgeBodyPosition(axis:String,deltaMm:Double) {
        val draft=bodyPositionDraft ?: return
        require(axis in setOf("X","Y","Z") && deltaMm.isFinite())
        val old=when(axis) { "X"->draft.x;"Y"->draft.y;else->draft.z }
        val quantity=ParameterEvaluator.expression(old,parameterValues)
        require(quantity.dimension==Dimension(1)) { "Körperpositionen benötigen Längen." }
        val next="${java.lang.String.format(java.util.Locale.ROOT,"%.6f",quantity.value+deltaMm)} mm"
        updateBodyPositionDraft(when(axis) { "X"->draft.copy(x=next);"Y"->draft.copy(y=next);else->draft.copy(z=next) })
    }
    fun previewBodyPosition():BodyPositionPreview? {
        val draft=bodyPositionDraft ?: return null
        val candidate=document.copy(features=document.effectiveFeatures().map { if(it.id==draft.id) it.withPosition(draft.x,draft.y,draft.z) else it })
        val bodies=candidate.sceneBodies().filter { it.visible }
        val target=bodies.firstOrNull { it.id==draft.id }?.mesh
        val others=bodies.filterNot { it.id==draft.id }
        val environment=when(others.size) {
            0 -> null
            1 -> others.single().mesh
            else -> {
                val vertices=ArrayList<Vec3>();val indices=ArrayList<Int>()
                others.forEach { part -> val offset=vertices.size;vertices.addAll(part.mesh.vertices);part.mesh.indices.forEach { indices.add(it+offset) } }
                MeshData("Umgebung",vertices,indices.toIntArray())
            }
        }
        return BodyPositionPreview(environment,target)
    }
    fun finishBodyPosition():CompletableFuture<Unit> {
        val draft=checkNotNull(bodyPositionDraft)
        val result=setBodyPosition(draft.id,draft.x,draft.y,draft.z)
        bodyPositionDraft=null;bodyPositionError=null;viewport.setFeaturePreview(emptyList());return result
    }
    fun cancelBodyPosition() { bodyPositionDraft=null;bodyPositionError=null;viewport.setFeaturePreview(emptyList()) }
    fun alignToSketch(): CompletableFuture<Unit> {
        val frame = sketchDraft?.drawingFrame() ?: selectedFace?.frame
            ?: throw IllegalArgumentException("Zuerst eine Fläche oder Skizze wählen.")
        return viewport.alignToPlane(frame)
    }
    fun cancelSupportSelection() { selectingSupport=false;viewport.showOriginPlanes(false) }
    fun updateSketchDraft(sketch: Sketch) {
        val solved=if(sketch.constraints.isEmpty()) sketch else runCatching { sketch.solveConstraints(parameterValues) }
            .fold({ result -> constraintStatus=constraintSummary(result);if(result.status=="conflicting") return;result.sketch },
                { reportError(it);return })
        sketchDraft=solved;sketchHasProfile=true;selectedSketchElementId=sketch.elements.lastOrNull()?.id
    }
    fun selectSketchTool(tool: SketchDrawingTool) { if(sketchEditor && !extruding) sketchTool=tool }
    fun selectSketchElement(id: String) {
        require(sketchDraft?.elements?.any { it.id==id }==true) { "Skizzenelement existiert nicht." }
        selectedSketchElementId=id
    }
    fun toggleConstraintElement(id:String,selected:Boolean) {
        require(sketchDraft?.elements?.any { it.id==id }==true)
        constraintSelection=if(selected) constraintSelection+id else constraintSelection-id
        if(constraintSelection.size>2) constraintSelection=constraintSelection.drop(constraintSelection.size-2).toSet()
    }
    fun updateSketchElement(id: String,change: (SketchElement)->SketchElement) {
        val draft=sketchDraft ?: return
        require(draft.elements.any { it.id==id }) { "Skizzenelement existiert nicht." }
        val next=draft.withElements(draft.elements.map { if(it.id==id) change(it) else it })
        val solved=if(next.constraints.isEmpty()) next else next.solveConstraints(parameterValues).let { result ->
            constraintStatus=constraintSummary(result)
            if(result.status=="conflicting") return else result.sketch
        }
        sketchDraft=solved;sketchHasProfile=true
    }
    fun addSketchConstraint(type:SketchConstraintType,elementIds:List<String>,indices:List<Int> = emptyList(),value:String?=null,internal:Boolean=false) {
        val draft=sketchDraft ?: return
        val result=draft.addConstraint(SketchConstraint(type=type,elementIds=elementIds,indices=indices,value=value,internal=internal),parameterValues)
        constraintStatus=constraintSummary(result)
        if(result.status=="conflicting") return
        sketchDraft=result.sketch
    }

    fun addCommittedSketchConstraint(sketchId:String,constraint:SketchConstraint):CompletableFuture<SketchConstraintSolution> {
        require(!sketchEditor) { "Beende oder verwirf zuerst die Skizzenbearbeitung." }
        val sketch=document.sketches.firstOrNull { it.id==sketchId } ?: throw IllegalArgumentException("Skizze existiert nicht.")
        val result=sketch.addConstraint(constraint,parameterValues)
        if(result.status=="conflicting") return CompletableFuture.completedFuture(result)
        return commit(document.replaceSketch(result.sketch,legacyExtrusion=false)).thenApply { result }
    }

    fun removeCommittedSketchConstraint(sketchId:String,constraintId:String):CompletableFuture<SketchConstraintSolution> {
        require(!sketchEditor) { "Beende oder verwirf zuerst die Skizzenbearbeitung." }
        val sketch=document.sketches.firstOrNull { it.id==sketchId } ?: throw IllegalArgumentException("Skizze existiert nicht.")
        require(sketch.constraints.any { it.id==constraintId }) { "Constraint existiert nicht." }
        val next=sketch.copy(constraints=sketch.constraints.filterNot { it.id==constraintId })
        val result=next.solveConstraints(parameterValues)
        require(result.status!="conflicting") { "Das Entfernen des Constraints konnte nicht sicher gelöst werden." }
        return commit(document.replaceSketch(result.sketch,legacyExtrusion=false)).thenApply { result }
    }
    fun removeSketchConstraint(id:String) {
        val draft=sketchDraft ?: return
        val next=draft.copy(constraints=draft.constraints.filterNot { it.id==id })
        val result=if(next.constraints.isEmpty()) null else next.solveConstraints(parameterValues)
        sketchDraft=result?.sketch ?: next
        constraintStatus=result?.let(::constraintSummary)
    }
    fun removeSketchElement(id: String) {
        val draft=sketchDraft ?: return
        require(draft.elements.size>1) { "Das letzte Element kannst du über „Skizze löschen“ entfernen." }
        require(draft.elements.any { it.id==id }) { "Skizzenelement existiert nicht." }
        val next=draft.withElements(draft.elements.filterNot { it.id==id }).copy(constraints=draft.constraints.filterNot { id in it.elementIds })
        if(document.sketches.any { it.id==draft.id }) document.replaceSketch(next,legacyExtrusion=false).bodyMeshes()
        sketchDraft=next
        constraintSelection=constraintSelection-id
        if(selectedSketchElementId==id) selectedSketchElementId=next.elements.firstOrNull()?.id
    }
    fun cancelExtrusionRegionChooser() { extrusionRegionChooserSketchId=null;extrusionChooserSelection=emptySet() }
    fun cancelSketch() { clearRegionSelection();cancelRegionExtrusion();extruding=false;extrusionRegionId=null;extrusionFeatureId=null;extrusionRegionIds=emptySet();extrusionFeatureIds=emptyMap();extrusionRegionChooserSketchId=null;extrusionChooserSelection=emptySet();sketchTool=SketchDrawingTool.RECTANGLE;selectedSketchElementId=null;constraintSelection=emptySet();constraintStatus=null;previewStatus(null,0);viewport.setSketchEditing(null); sketchHasProfile=false;sketchDraft=null;sketchEditor=false;cancelSupportSelection();selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList()) }
    fun finishSketch(): CompletableFuture<Unit> {
        val original=sketchDraft ?: return CompletableFuture.completedFuture(Unit)
        require(sketchHasProfile) { "Zuerst ein Profil zeichnen oder seine Maße eingeben." }
        completingSketch=true
        return try {
            val result=if(extruding) {
                val regions=checkNotNull(extrusionRegionIds.takeIf { it.isNotEmpty() }?.sorted()) { "Extrusionsregion fehlt." }
                val depth=checkNotNull(original.depth) { "Extrusionstiefe fehlt." }
                val hasOtherExtrusions=document.effectiveFeatures().filterIsInstance<ExtrusionFeature>().any { it.region.sketchId==original.id && it.region.regionId !in regions }
                val sketch=if(hasOtherExtrusions) original else original.copy(visible=false)
                val features=regions.map { region -> ExtrusionFeature(checkNotNull(extrusionFeatureIds[region]) { "Extrusions-ID fehlt." },RegionReference(original.id,region),depth) }
                commit(document.replaceSketchExtrusions(sketch,features))
            } else commit(document.replaceSketch(original,legacyExtrusion=false))
            cancelSketch()
            selectedSketchId=original.id
            result
        } finally { completingSketch=false }
    }
    fun deleteEditingSketch() {
        val id=editingSketchId ?: return
        completingSketch=true
        try { removeSketch(id);cancelSketch() } finally { completingSketch=false }
    }
    fun setSketch(sketch: Sketch): CompletableFuture<Unit> = commit(document.replaceSketch(sketch,legacyExtrusion=sketch.depth!=null))
    fun removeSketch(id: String) = commit(document.withoutSketch(id))
    private fun publish(next: DocumentState) {
        clearChatReferences()
        clearRegionSelection();viewport.setRegions(next.pickableRegions())
        notice=null;selectedFace=null;selectedFaces=emptyList();viewport.highlightFaces(emptyList())
        if(next.sketches.none { it.id==selectedSketchId }) selectedSketchId=null
        document = next; sceneModel = next.renderMesh(); parameterValues = ParameterEvaluator.evaluate(next.parameters); revision++
    }
    private fun commit(next: DocumentState): CompletableFuture<Unit> {
        require(!loading && !exporting) { "Ein Dateivorgang läuft noch." }
        require(!sketchEditor || completingSketch) { "Skizze zuerst fertigstellen oder abbrechen." }
        val canonical=next.canonicalFeatures()
        canonical.bodyMeshes()
        if (!history.commit(canonical)) return CompletableFuture.completedFuture(Unit)
        publish(canonical); error = null
        return renderDocument(canonical)
    }
    fun undo(): CompletableFuture<Unit> {
        if (!canUndo) return CompletableFuture.completedFuture(Unit)
        val state = history.undo(); publish(state); return renderDocument(state)
    }
    fun redo(): CompletableFuture<Unit> {
        if (!canRedo) return CompletableFuture.completedFuture(Unit)
        val state = history.redo(); publish(state); return renderDocument(state)
    }
    internal var colorEditorBody by mutableStateOf<String?>(null)
    internal var colorEditorFaces by mutableStateOf<List<SelectedFace>>(emptyList())
    internal var colorEditorOpen by mutableStateOf(false)
    internal fun startColorEditor(id:String?=null) {
        require(!loading && !saving && !exporting && !sketchEditor) { "Beende zuerst den laufenden Vorgang." }
        colorEditorBody=id;colorEditorFaces=if(id==null) selectedFaces.ifEmpty { listOfNotNull(selectedFace) } else emptyList()
        require(id!=null || colorEditorFaces.isNotEmpty()) { "Wähle eine Fläche." }
        colorEditorOpen=true
    }
    internal fun colorSelectedFaces(color:String,name:String):CompletableFuture<Unit> {
        var next=document
        colorEditorFaces.groupBy { it.reference.featureId ?: it.reference.sketchId?.let { s -> bodyId(s,it.reference.mirrored) } ?: "import" }.forEach { (id,faces) ->
            val body=next.sceneBodies().first { it.id==id };val mesh=next.colorMesh(body);val patches=mesh.colorPatches()
            val triangles=faces.flatMap { face -> patches.first { face.reference.triangle in it } }.distinct()
            next=next.withColorArea(id,ColorArea("area-${java.util.UUID.randomUUID()}",name,checkedColor(color),triangles,colorRevision(mesh)))
        }
        return commit(next).thenCompose { viewport.highlightFaces(emptyList()) }
    }
    internal var instanceDraft by mutableStateOf<InstanceFeature?>(null)
    internal fun startInstance(sourceId:String,edit:Boolean=false) {
        require(canMarkChat && instanceDraft==null) { "Beende zuerst den aktuellen Vorgang." }
        val source=document.effectiveFeatures().firstOrNull { it.id==sourceId } ?: error("Quellkörper fehlt.")
        instanceDraft=if(edit) source as? InstanceFeature ?: error("Keine Instanz.") else InstanceFeature("instance-${java.util.UUID.randomUUID()}","Instanz von ${document.sceneBodies().first { it.id==sourceId }.mesh.name}",sourceId,x="30 mm")
    }
    internal fun storeInstance(feature:InstanceFeature) {
        startAssemblyTask("batch",buildJsonObject { put("operations",JsonArray(listOf(buildJsonObject { put("op","put_feature");put("feature",feature.json()) }))) });instanceDraft=null
    }
    private val assemblyTasks=linkedMapOf<String,AssemblyTask>()
    internal var assemblyTaskState by mutableStateOf<kotlinx.serialization.json.JsonObject?>(null)
    private fun publishAssemblyTask(task:AssemblyTask) {
        if(assemblyTaskState?.get("jobId")?.jsonPrimitive?.content==task.id) assemblyTaskState=task.json()
    }
    internal fun assemblyTask(id:String):JsonObject {
        val task=assemblyTasks[id] ?: error("Vorgang existiert nicht.")
        if(task.kind!="batch" && task.status=="complete" && document!==task.snapshot) return JsonObject(task.json()+mapOf("status" to JsonPrimitive("stale"),"message" to JsonPrimitive("Projekt seit der Prüfung geändert. Prüfung erneut ausführen.")))
        return task.json()
    }
    internal fun cancelAssemblyTask(id:String):kotlinx.serialization.json.JsonObject {
        val task=assemblyTasks[id] ?: error("Vorgang existiert nicht.")
        if(task.status=="running") { task.work.cancel();task.status="cancelled";task.message="Abgebrochen; Projekt unverändert.";publishAssemblyTask(task) }
        return task.json()
    }
    internal fun startAssemblyTask(kind:String,arguments:kotlinx.serialization.json.JsonObject):kotlinx.serialization.json.JsonObject {
        require(!loading && !saving && !exporting && !sketchEditor && !parameterEditor && loftDraft==null && primitiveDraft==null && assemblyTasks.values.none { it.status in setOf("running","committing") }) { "Beende zuerst den aktuellen Vorgang." }
        val snapshot=document;val id="assembly-${java.util.UUID.randomUUID()}"
        val operations=arguments["operations"]?.jsonArray
        val total=if(kind=="batch") (operations?.size ?: 0)+snapshot.effectiveFeatures().size+(operations?.count { it.jsonObject["op"]?.jsonPrimitive?.content=="put_feature" } ?: 0) else 100
        lateinit var task:AssemblyTask
        val work=AssemblyWork(total) { message,count -> task.message=message;task.completed=count;onUi { publishAssemblyTask(task) } }
        task=AssemblyTask(id,kind,work,snapshot);assemblyTasks[id]=task
        while(assemblyTasks.size>16) assemblyTasks.remove(assemblyTasks.keys.first())
        assemblyTaskState=task.json()
        val builds=GeometryCache.builds.get();val hits=GeometryCache.hits.get()
        CompletableFuture.supplyAsync {
            work.run {
                if(kind=="batch") snapshot.assemblyBatch(operations ?: error("operations fehlt."))
                else snapshot.assemblyAnalysis(kind,arguments)
            }
        }.whenComplete { result,failure -> onUi {
            try {
                if(task.status=="cancelled" || work.cancelled.get()) return@onUi
                if(failure!=null) { task.status="failed";task.message=(failure.cause ?: failure).message ?: "Vorgang fehlgeschlagen." }
                else if(document!==snapshot || loading || saving || exporting) { task.status="stale";task.message="Projekt zwischenzeitlich geändert. Ergebnis nicht übernommen; Vorgang neu starten." }
                else if(result is BatchCandidate) {
                    work.checkCancelled()
                    // The candidate has already been fully evaluated; the commit reuses cached geometry.
                    task.status="committing"
                    commit(result.document).whenComplete { _,commitFailure -> onUi {
                        task.status="complete"
                        task.message=if(commitFailure==null) "Transaktion übernommen." else "Transaktion übernommen; Ansicht bitte aktualisieren: ${commitFailure.message}"
                        task.result=buildJsonObject { put("changedIds",JsonArray(result.changedIds.map(::JsonPrimitive)));put("geometryBuilds",GeometryCache.builds.get()-builds);put("cacheHits",GeometryCache.hits.get()-hits);put("bodyCount",result.document.sceneBodies().size) }
                        task.completed=task.work.total;publishAssemblyTask(task)
                    } }
                } else { task.status="complete";task.message="Prüfung abgeschlossen.";task.result=result as kotlinx.serialization.json.JsonObject;task.completed=task.work.total }
            } catch(e:Exception) { task.status="failed";task.message=e.message ?: "Vorgang fehlgeschlagen." }
            finally { work.close();publishAssemblyTask(task) }
        } }
        return task.json()
    }
    internal fun setObjectColor(id:String,color:String?)=commit(document.withBodyColor(id,color))
    internal fun setAreaColor(id:String,area:ColorArea)=commit(document.withColorArea(id,area))
    internal fun removeAreaColor(id:String,areaId:String)=commit(document.withoutColorArea(id,areaId))
    private fun renderDocument(state: DocumentState): CompletableFuture<Unit> {
        return viewport.setContent(state.renderMesh(), state.sketchGeometry(),framingBounds=state.framingBounds(),colors=state.renderColors()).thenCompose { renderDisplay(state.display) }
    }
    private fun renderDisplay(settings: DisplaySettings): CompletableFuture<Unit> = CompletableFuture.allOf(
        viewport.setWireframe(settings.wireframe), viewport.setGrid(settings.grid), viewport.setVisible(settings.visible), viewport.select(selected)
    ).thenApply { Unit }
    fun configureDisplay(wire: Boolean?, showGrid: Boolean?, showModel: Boolean?, newUnit: String?): CompletableFuture<Unit> =
        commit(document.copy(display = document.display.copy(wireframe=false, grid=showGrid ?: grid, visible=showModel ?: visible, unit=newUnit ?: unit)))
    fun setUnit(value: String) { if (!loading && !sketchEditor) configureDisplay(null, null, null, value) }
    fun removeImportedBody(): CompletableFuture<Unit> = commit(document.removeImportedBody())
    fun fitBody(id: String): CompletableFuture<Unit> {
        require(!sketchEditor && !loading) { "Beende zuerst die Skizzenbearbeitung." }
        val body=document.sceneBodies().firstOrNull { it.id==id } ?: throw IllegalArgumentException("Körper existiert nicht.")
        require(body.visible && visible) { "Blende den Körper zuerst ein." }
        return viewport.fitBody(body.mesh.bounds)
    }
    fun fit() = viewport.fit()
    fun select(value: Boolean) { selected = value; viewport.select(value) }
    fun toggleVisible() { if (!loading && !sketchEditor) configureDisplay(null, null, !visible, null) }
    fun displayWireframe(value: Boolean) { if (!loading && !sketchEditor) configureDisplay(value, null, null, null) }
    fun toggleGrid() { if (!loading && !sketchEditor) configureDisplay(null, !grid, null, null) }
    fun view(name: String): CompletableFuture<Unit> {
        currentView = name
        return when (name) {
            "Vorne" -> viewport.setView(0.0, 0.0)
            "Oben" -> viewport.setView(90.0, 0.0)
            "Rechts" -> viewport.setView(0.0, 90.0)
            "Hinten" -> viewport.setView(0.0, 180.0)
            "Links" -> viewport.setView(0.0, -90.0)
            "Unten" -> viewport.setView(-90.0, 0.0)
            else -> viewport.setView(25.0, -35.0)
        }
    }
    fun reportError(error: Throwable) { this.error = error.message ?: "Vorgang fehlgeschlagen." }
    fun dismissError() { error = null }
    private fun assistantProject()=projectPath?.toString() ?: sourcePath?.toString() ?: "new"
    internal fun assistantContext(value: JsonObject): JsonObject {
        val selection=buildJsonObject {
            put("markedAreas",JsonArray(chatReferences.map { it.json() }))
            put("sketchId",selectedSketchId?.let(::JsonPrimitive) ?: JsonNull)
            selectedFace?.let { face ->
                put("face",face.reference.json());put("frame",face.frame.json())
            document.sceneTriangle(face.reference)?.let { put("sceneTriangle",it) }
            }
        }
        return assistantModelInfo(JsonObject(value.toMutableMap().apply { put("selection",selection) }))
    }
    private fun executeAssistant(epoch: Long,name: String,args: JsonObject): CompletableFuture<JsonObject> {
        val result=CompletableFuture<JsonObject>()
        onUi {
            try {
                check(epoch==generation.get()) { "Das Projekt wurde gewechselt. Bitte stelle den Auftrag im neuen Projekt erneut." }
                check(!loading && !saving && !exporting && !sketchEditor && !parameterEditor && renamingSketchId==null && loftDraft==null && !loftWorking) { "Beende zuerst die aktuelle Bearbeitung oder den Dateivorgang." }
                check(assistantTools.any { it.name==name }) { "Dieser Befehl steht im CAD-Chat nicht zur Verfügung." }
                executeCommand(buildJsonObject { put("action",name);put("arguments",args) }).whenComplete { value,error -> onUi {
                    if(error!=null) result.completeExceptionally(error)
                    else result.complete(if(value["mimeType"]!=null) value else assistantContext(value))
                } }
            } catch(e: Exception) { result.completeExceptionally(e) }
        }
        return result
    }
    internal var assistantSubmitting by mutableStateOf(false)
        private set
    internal fun sendAssistant(text: String,accepted:()->Unit = {}) {
        if(assistantSubmitting || assistant.busy) return
        if(text.isBlank() || !assistant.connected || assistant.model.isBlank()) {
            reportError(IllegalStateException("Verbinde den Assistenten und wähle ein Modell."));return
        }
        if(loading || saving || exporting || sketchEditor || parameterEditor || loftDraft!=null || loftWorking || renamingSketchId!=null) { reportError(IllegalStateException("Beende zuerst die aktuelle Bearbeitung."));return }
        val epoch=generation.get()
        assistantSubmitting=true;error=null
        executeAssistant(epoch,"cad_get_model",buildJsonObject {}).whenComplete { context,failure -> onUi {
            assistantSubmitting=false
            when {
                failure!=null -> reportError(failure)
                epoch!=generation.get() -> reportError(IllegalStateException("Das Projekt wurde inzwischen geändert. Bitte erneut senden."))
                loading || saving || exporting || sketchEditor || parameterEditor || loftDraft!=null || loftWorking || renamingSketchId!=null -> reportError(IllegalStateException("Beende zuerst die aktuelle Bearbeitung."))
                !assistant.connected || assistant.model.isBlank() || assistant.busy -> reportError(IllegalStateException("Der Assistent ist derzeit nicht bereit. Bitte erneut senden."))
                else -> { chatMarking=false;viewport.setChatMarking(false);assistant.send(text,assistantProject(),epoch,context);accepted() }
            }
        } }
    }
    fun close() {
        assemblyTasks.values.filter { it.status=="running" }.forEach { it.work.cancel() };assistant.close();bridge?.close(); generation.incrementAndGet(); executor.shutdownNow()
        keyHandler?.let { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) }
        viewport.dispose()
    }
    private fun constraintSummary(result:SketchConstraintSolution):String = when(result.status) {
        "conflicting" -> "Widersprüchlich · ${result.conflicts.distinct().joinToString()}"
        "fullyconstrained" -> "Vollständig bestimmt · 0 Freiheitsgrade"
        else -> "Unterbestimmt · ${result.degreesOfFreedom} Freiheitsgrade"
    }
    private fun supported(path: Path) = path.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("stl", "obj", "kcad")
}
