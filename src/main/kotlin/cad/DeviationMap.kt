package cad

import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.*

internal object DeviationMap {
    fun render(report:JsonObject,range:Double):JsonObject {
        require(report["measurementKind"]?.jsonPrimitive?.content=="surface-comparison") { "Dieser Auftrag enthält keinen Flächenvergleich." }
        require(range.isFinite() && range>0)
        val image=BufferedImage(1200,760,BufferedImage.TYPE_INT_RGB);val g=image.createGraphics()
        try {
            g.color=Color(23,29,39);g.fillRect(0,0,1200,760);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            fun text(value:String,x:Int,y:Int,size:Int=16){g.font=Font(Font.SANS_SERIF,Font.PLAIN,size);g.color=Color.WHITE;g.drawString(value,x,y)}
            text("Oberflächenabweichung · mm",30,35,23)
            val lower=report.getValue("maximumLowerBoundMm").jsonPrimitive.double;val upper=report.getValue("maximumUpperBoundMm").jsonPrimitive.double
            text("Maximaler Abstand: %.4f bis %.4f mm".format(java.util.Locale.US,lower,upper),30,65)
            text(if(report.getValue("converged").jsonPrimitive.boolean) "Gewünschte Genauigkeit des Maximums erreicht" else "Budget erreicht: Genauigkeit des Maximums noch nicht erreicht",30,90)
            fun color(d:Double):Color {val t=(d/range).coerceIn(0.0,1.0);return Color.getHSBColor(((1-t)*.65).toFloat(),.85f,.95f)}
            for((panel,key) in listOf("sourceToCopy","copyToSource").withIndex()) {
                val map=report.getValue(key).jsonObject.getValue("deviationMap").jsonArray.map { it.jsonObject }
                val triangles=map.map { row -> row.getValue("vertices").jsonArray.map { it.measureVector() } to row.getValue("maximumSampleMm").jsonPrimitive.double }
                fun project(p:Vec3)=Pair((p.x-p.y)*.70710678,(p.x+p.y)*.40824829-p.z*.81649658)
                val ps=triangles.flatMap { it.first }.map(::project);val minX=ps.minOf { it.first };val maxX=ps.maxOf { it.first };val minY=ps.minOf { it.second };val maxY=ps.maxOf { it.second }
                val scale=min(520/max(maxX-minX,1e-9),480/max(maxY-minY,1e-9));val x0=panel*600+40+(520-(maxX-minX)*scale)/2;val y0=160+(480-(maxY-minY)*scale)/2
                text(if(panel==0)"Original → Kopie" else "Kopie → Original",panel*600+40,135,19)
                for((vertices,d) in triangles.sortedBy { (p,_)->p.sumOf { it.x+it.y+it.z } }) {
                    val pts=vertices.map(::project);g.color=color(d);g.fillPolygon(pts.map { (x0+(it.first-minX)*scale).roundToInt() }.toIntArray(),pts.map { (y0+(it.second-minY)*scale).roundToInt() }.toIntArray(),3)
                }
            }
            for(x in 0..400){g.color=color(range*x/400);g.drawLine(400+x,670,400+x,690)}
            text("0 mm",335,687);text("≥ %.3f mm".format(java.util.Locale.US,range),815,687)
            text("Farbe: größter gemessener Abstand je Dreieck; lokale Obergrenzen im Messbericht.",30,725,14)
        } finally {g.dispose()}
        val bytes=ByteArrayOutputStream();ImageIO.write(image,"png",bytes)
        return buildJsonObject {put("mimeType","image/png");put("width",1200);put("height",760);put("data",Base64.getEncoder().encodeToString(bytes.toByteArray()))}
    }
}
