package cad

import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import kotlin.math.*

/** Screen-space controls anchored to real points on the active 3D plane. */
class SketchOverlay(private val changed: (Sketch)->Unit) {
    var darkMode=false
    val pane=Pane().apply { isPickOnBounds=false }
    private var editing=false
    val editingDimension get()=editing
    private val planeGrid=javafx.scene.Group().apply { isMouseTransparent=true }
    fun grid(lines: List<Pair<SketchPoint,SketchPoint>>,project: (SketchPoint)->SketchPoint?) {
        if(!pane.children.contains(planeGrid)) pane.children.add(0,planeGrid)
        var used=0
        lines.forEach { (start,end) ->
            val a=project(start);val b=project(end)
            if(a!=null && b!=null) {
                val line=if(used<planeGrid.children.size) planeGrid.children[used] as Line else Line().apply {
                    stroke=Color.web("#73a8cd",.42);strokeWidth=.7
                    planeGrid.children.add(this)
                }
                line.startX=a.u;line.startY=a.v;line.endX=b.u;line.endY=b.v
                used++
            }
        }
        if(used<planeGrid.children.size) planeGrid.children.subList(used,planeGrid.children.size).clear()
    }

    val guideCount get()=inference.children.size
    private val depthHandle=javafx.scene.Group().apply { isMouseTransparent=true }
    fun extrusionHandle(base: SketchPoint?,tip: SketchPoint?) {
        depthHandle.children.clear()
        if(!pane.children.contains(depthHandle)) pane.children.add(depthHandle)
        if(base==null || tip==null) return
        depthHandle.children.add(Line(base.u,base.v,tip.u,tip.v).apply { stroke=Color.web("#007aff");strokeWidth=2.0 })
        depthHandle.children.add(Circle(tip.u,tip.v,7.0).apply { fill=Color.WHITE;stroke=Color.web("#007aff");strokeWidth=2.0 })
        val length=hypot(tip.u-base.u,tip.v-base.v).coerceAtLeast(1.0)
        val x=(tip.u-base.u)/length;val y=(tip.v-base.v)/length
        depthHandle.children.addAll(Line(tip.u-4*x-3*y,tip.v-4*y+3*x,tip.u+4*x,tip.v+4*y).apply { stroke=Color.web("#007aff") },
            Line(tip.u-4*x+3*y,tip.v-4*y-3*x,tip.u+4*x,tip.v+4*y).apply { stroke=Color.web("#007aff") })
    }
    private val inference=javafx.scene.Group().apply { isMouseTransparent=true }
    fun guides(lines: List<SketchGuide>,project: (SketchPoint)->SketchPoint?) {
        inference.children.clear()
        if(!pane.children.contains(inference)) pane.children.add(inference)
        lines.forEach { guide ->
            val a=project(guide.from);val b=project(guide.to)
            if(a!=null && b!=null) inference.children.add(Line(a.u,a.v,b.u,b.v).apply {
                stroke=Color.web("#d39121");strokeWidth=1.4;strokeDashArray.setAll(5.0,4.0)
            })
        }
    }
    private val references=javafx.scene.Group().apply { isMouseTransparent=true }
    fun references(center: SketchPoint?,project: (SketchPoint)->SketchPoint?,hint: Pair<SketchPoint,String>?) {
        references.children.clear()
        if(!pane.children.contains(references)) pane.children.add(0,references)
        (listOf(SketchPoint(0.0,0.0) to "Ursprung")+listOfNotNull(center?.let { it to "Flächenmitte" })).forEach { (point,label) ->
            project(point)?.let { p ->
                val color=if(hint?.second==label) Color.web("#d28b12") else Color.web("#859fb8")
                references.children.addAll(Line(p.u-6,p.v,p.u+6,p.v).apply { stroke=color },Line(p.u,p.v-6,p.u,p.v+6).apply { stroke=color },Circle(p.u,p.v,3.0).apply { fill=Color.WHITE;stroke=color })
                references.children.add(javafx.scene.text.Text(p.u+9,p.v+14,label).apply { fill=color;style="-fx-font-size: 10;" })
            }
        }
    }
    fun clear() { editing=false;pane.children.clear() }
    fun show(draft: Sketch,values: Map<String,Quantity>,project: (SketchPoint)->SketchPoint?,snap: Pair<SketchPoint,String>?=null,profileControls: Boolean=true,depthControls: Boolean=true) {
        if(editing) return
        pane.children.clear()
        val handles=runCatching { draft.handles(values) }.getOrNull() ?: return
        val positions=handles.associate { it.id to project(it.point) }
        if(profileControls) handles.forEach { handle -> positions[handle.id]?.let { p ->
            pane.children.add(Circle(p.u,p.v,if(handle.id==4) 4.0 else 5.0).apply {
                fill=if(handle.id==4) Color.web("#007aff") else Color.WHITE;stroke=Color.web("#007aff");strokeWidth=1.6;isMouseTransparent=true
            })
        } }
        fun dimension(key: String,expression: String,caption: String,a: SketchPoint,b: SketchPoint,ox: Double,oy: Double) {
            val x=(a.u+b.u)/2+ox;val y=(a.v+b.v)/2+oy
            pane.children.add(Line(a.u+ox,a.v+oy,b.u+ox,b.v+oy).apply { stroke=Color.web("#5598da");isMouseTransparent=true })
            val value=runCatching { ParameterEvaluator.expression(expression,values).label() }.getOrDefault(expression)
            val button=Button("$caption $value").apply {
                id="dimension-$key"
                style=if(darkMode) "-fx-background-color: #293848; -fx-background-radius: 12; -fx-border-color: #4c7198; -fx-border-radius: 12; -fx-text-fill: #b4d8ff; -fx-font-size: 12; -fx-padding: 4 7;" else "-fx-background-color: linear-gradient(to bottom, rgba(255,255,255,.96), rgba(235,245,255,.87)); -fx-background-radius: 12; -fx-border-color: rgba(174,207,240,.8); -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(50,100,160,.12), 8, 0, 0, 2); -fx-text-fill: #075eae; -fx-font-size: 12; -fx-padding: 4 7;"
                isManaged=false;relocate((x-40).coerceIn(2.0,max(2.0,pane.width-110)),y-12)
                tooltip=Tooltip("$caption bearbeiten · Zahl mit Einheit oder Parameterformel · $expression")
                setOnAction {
                    editing=true
                    val inputStyle=if(darkMode) "-fx-background-color: #263341; -fx-background-radius: 12; -fx-border-color: #6fa9e3; -fx-border-radius: 12; -fx-text-fill: #edf2fa; -fx-font-size: 12; -fx-padding: 4 8;" else "-fx-background-color: rgba(255,255,255,.96); -fx-background-radius: 12; -fx-border-color: #8ab9ea; -fx-border-radius: 12; -fx-text-fill: #243247; -fx-font-size: 12; -fx-padding: 4 8;"
                    val input=TextField(expression).apply { id="dimension-input";isManaged=false;prefWidth=150.0;resize(150.0,30.0);style=inputStyle }
                    input.relocate(this.layoutX,this.layoutY)
                    pane.children.remove(this);pane.children.add(input)
                    var done=false
                    fun apply(): Boolean {
                        if(done) return true
                        val raw=input.text.trim()
                        val expression=if(raw.matches(Regex("[+-]?[0-9]+([.,][0-9]+)?"))) raw.replace(',', '.')+" mm" else raw
                        val candidate=when(key) { "width" -> draft.copy(width=expression); "height" -> draft.copy(height=expression); else -> draft.copy(depth=expression) }
                        return runCatching { candidate.points(values);candidate.extrudedMesh(values) }.fold({
                            done=true;editing=false;changed(candidate);show(candidate,values,project,snap,profileControls,depthControls);true
                        },{ error -> input.style=inputStyle+" -fx-border-color: #d8342d;";input.tooltip=Tooltip(error.message);false })
                    }
                    input.setOnKeyPressed { event -> when(event.code) {
                        KeyCode.ENTER -> { apply();event.consume() }
                        KeyCode.ESCAPE -> { done=true;editing=false;show(draft,values,project,snap,profileControls,depthControls);event.consume() }
                        else -> Unit
                    } }
                    input.focusedProperty().addListener { _,_,focused -> if(!focused && !done) { if(!apply()) { done=true;editing=false;show(draft,values,project,snap,profileControls,depthControls) } } }
                    input.requestFocus();input.selectAll()
                }
            }
            pane.children.add(button)
            pane.applyCss();button.autosize()
        }
        val a=positions[0];val b=positions[1]
        if(profileControls && draft.shape==SketchShape.CIRCLE) {
            val center=positions[4]
            if(a!=null && center!=null) dimension("width",draft.width,"R",center,a,0.0,-25.0)
        } else if(profileControls && a!=null && b!=null) {
            dimension("width",draft.width,"↔",a,b,0.0,28.0)
            val apex=positions[2]
            if(apex!=null) dimension("height",draft.height,"↕",b,apex,48.0,0.0)
        }
        if(depthControls) draft.depth?.let { expression -> positions[4]?.let { center -> dimension("depth",expression,"Tiefe",center,center,95.0,-55.0) } }
        if(!profileControls && depthHandle.children.isNotEmpty() && !pane.children.contains(depthHandle)) pane.children.add(depthHandle)
        snap?.let { (point,label) -> project(point)?.let { p ->
            pane.children.add(Circle(p.u,p.v,8.0).apply { fill=Color.TRANSPARENT;stroke=Color.web("#e09821");strokeWidth=2.0;isMouseTransparent=true })
            pane.children.add(javafx.scene.text.Text(p.u+12,p.v-12,label).apply { fill=Color.web("#ad7010");isMouseTransparent=true })
        } }
    }
}
