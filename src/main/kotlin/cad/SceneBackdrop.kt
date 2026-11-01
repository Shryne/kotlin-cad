package cad

import javafx.collections.ListChangeListener
import javafx.scene.*
import javafx.scene.effect.GaussianBlur
import javafx.scene.layout.Pane
import javafx.scene.shape.*
import javafx.scene.transform.Scale
import javafx.scene.transform.Transform

/** A live GPU view sharing mesh buffers and materials. No pixel readbacks during navigation. */
internal class SceneBackdrop(source: SubScene, sourceCamera: PerspectiveCamera) {
    val pane=Pane().apply { isMouseTransparent=true;isPickOnBounds=false }
    private val camera=PerspectiveCamera(true).apply {
        nearClipProperty().bind(sourceCamera.nearClipProperty())
        farClipProperty().bind(sourceCamera.farClipProperty())
        fieldOfViewProperty().bind(sourceCamera.fieldOfViewProperty())
        translateZProperty().bind(sourceCamera.translateZProperty())
    }
    private val view=SubScene(mirror(source.root) as Parent,1.0,1.0,true,SceneAntialiasing.DISABLED).apply {
        widthProperty().bind(source.widthProperty().divide(8.0))
        heightProperty().bind(source.heightProperty().divide(8.0))
        fillProperty().bind(source.fillProperty())
        camera=this@SceneBackdrop.camera
        effect=GaussianBlur(2.0)
    }
    init {
        pane.children.add(Group(view).apply { transforms.add(Scale(8.0,8.0)) })
        regions(emptyList())
    }
    fun regions(regions: Collection<DoubleArray>) {
        pane.isVisible=regions.isNotEmpty()
        pane.clip=regions.map { r -> Rectangle(r[0],r[1],r[2],r[3]).apply { arcWidth=2*r.getOrElse(4) { 22.0 };arcHeight=arcWidth } }
            .reduceOrNull<Shape,Shape> { a,b -> Shape.union(a,b) }
    }
    private fun mirror(source: Node): Node {
        val target: Node=when(source) {
            is Group -> Group().also { group ->
                fun sync() { group.children.setAll(source.children.map(::mirror)) }
                sync();source.children.addListener(ListChangeListener { change ->
                    while(change.next()) {
                        if(change.wasPermutated() || change.wasUpdated()) { sync();break }
                        if(change.wasRemoved()) group.children.subList(change.from,change.from+change.removedSize).clear()
                        if(change.wasAdded()) group.children.addAll(change.from,change.addedSubList.map(::mirror))
                    }
                })
            }
            is MeshView -> MeshView().apply { meshProperty().bind(source.meshProperty()) }
            is Box -> Box().apply { widthProperty().bind(source.widthProperty());heightProperty().bind(source.heightProperty());depthProperty().bind(source.depthProperty()) }
            is Cylinder -> Cylinder(source.radius,source.height,source.divisions).apply { radiusProperty().bind(source.radiusProperty());heightProperty().bind(source.heightProperty()) }
            is Sphere -> Sphere(source.radius,source.divisions).apply { radiusProperty().bind(source.radiusProperty()) }
            is AmbientLight -> AmbientLight(source.color)
            is PointLight -> PointLight(source.color)
            else -> Group()
        }
        target.translateXProperty().bind(source.translateXProperty());target.translateYProperty().bind(source.translateYProperty());target.translateZProperty().bind(source.translateZProperty())
        target.rotateProperty().bind(source.rotateProperty());target.rotationAxisProperty().bind(source.rotationAxisProperty())
        target.scaleXProperty().bind(source.scaleXProperty());target.scaleYProperty().bind(source.scaleYProperty());target.scaleZProperty().bind(source.scaleZProperty())
        target.visibleProperty().bind(source.visibleProperty());target.opacityProperty().bind(source.opacityProperty())
        target.transforms.setAll(source.transforms)
        source.transforms.addListener(ListChangeListener<Transform> { target.transforms.setAll(source.transforms) })
        if(source is Shape3D && target is Shape3D) {
            target.materialProperty().bind(source.materialProperty());target.drawModeProperty().bind(source.drawModeProperty());target.cullFaceProperty().bind(source.cullFaceProperty())
        }
        return target
    }
}
