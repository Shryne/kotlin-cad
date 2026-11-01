package cad

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

class SketchConstraintTest {
    private val kernel=Path.of("build/native-runtime/cad-kernel").toAbsolutePath()
    private fun ready() {
        assumeTrue(Files.isExecutable(kernel))
        System.setProperty("kotlin.cad.kernel",kernel.toString())
    }
    private fun line(id:String,a:Pair<String,String>,b:Pair<String,String>)=SketchElement(id=id,kind=SketchElementKind.LINE,
        vertices=listOf(SketchCoordinate(a.first,a.second),SketchCoordinate(b.first,b.second)))
    private fun point(element:SketchElement,index:Int=0):Pair<Double,Double> {
        val p=element.vertices[index];return ParameterEvaluator.expression(p.u,emptyMap()).value to ParameterEvaluator.expression(p.v,emptyMap()).value
    }

    @Test fun `horizontal constraint survives moving a point and reports degrees of freedom`() {
        ready()
        val s=Sketch(elements=listOf(line("l", "0 mm" to "0 mm","10 mm" to "4 mm")))
        val result=s.addConstraint(SketchConstraint(type=SketchConstraintType.HORIZONTAL,elementIds=listOf("l"),indices=listOf(0)))
        assertEquals("underconstrained",result.status)
        assertTrue(result.degreesOfFreedom>0)
        val moved=result.sketch.moveConstrainedPoint("l",1,10.0,9.0)
        val updated=moved.sketch.elements.single()
        assertEquals(point(updated,0).second,point(updated,1).second,1e-6)
        assertEquals(10.0,point(updated,1).first,1e-5)
        assertEquals(result.sketch.constraints,moved.sketch.constraints)
    }

    @Test fun `perpendicular and equal length constraints solve line relations`() {
        ready()
        val first=line("a","0 mm" to "0 mm","20 mm" to "0 mm")
        val second=line("b","5 mm" to "4 mm","15 mm" to "14 mm")
        var s=Sketch(elements=listOf(first,second))
        s=s.addConstraint(SketchConstraint(type=SketchConstraintType.PERPENDICULAR,elementIds=listOf("a","b"),indices=listOf(0,0))).sketch
        s=s.addConstraint(SketchConstraint(type=SketchConstraintType.EQUAL_LENGTH,elementIds=listOf("a","b"),indices=listOf(0,0))).sketch
        val a=s.elements.first { it.id=="a" };val b=s.elements.first { it.id=="b" }
        val ap=point(a,0);val aq=point(a,1);val bp=point(b,0);val bq=point(b,1)
        val ax=aq.first-ap.first;val ay=aq.second-ap.second;val bx=bq.first-bp.first;val by=bq.second-bp.second
        assertTrue(abs(ax*bx+ay*by)<1e-4)
        assertEquals(kotlin.math.hypot(ax,ay),kotlin.math.hypot(bx,by),1e-4)
    }

    @Test fun `coincident endpoints and concentric circles persist`(@TempDir folder:Path) {
        ready()
        val a=line("a","0 mm" to "0 mm","10 mm" to "0 mm")
        val b=line("b","20 mm" to "10 mm","25 mm" to "10 mm")
        val circles=listOf(SketchElement(id="c1",shape=SketchShape.CIRCLE,u="0 mm",v="0 mm",width="5 mm"),SketchElement(id="c2",shape=SketchShape.CIRCLE,u="20 mm",v="10 mm",width="8 mm"))
        var s=Sketch(elements=listOf(a,b)+circles)
        s=s.addConstraint(SketchConstraint(type=SketchConstraintType.COINCIDENT,elementIds=listOf("a","b"),indices=listOf(1,0))).sketch
        assertEquals(point(s.elements.first { it.id=="a" },1),point(s.elements.first { it.id=="b" },0))
        s=s.addConstraint(SketchConstraint(type=SketchConstraintType.CONCENTRIC,elementIds=listOf("c1","c2"))).sketch
        val c1=s.elements.first { it.id=="c1" };val c2=s.elements.first { it.id=="c2" }
        assertEquals(ParameterEvaluator.expression(c1.u,emptyMap()).value,ParameterEvaluator.expression(c2.u,emptyMap()).value,1e-6)
        assertEquals(ParameterEvaluator.expression(c1.v,emptyMap()).value,ParameterEvaluator.expression(c2.v,emptyMap()).value,1e-6)
        val file=folder.resolve("constraints.kcad")
        ProjectIO.write(file,ProjectArchive(DocumentState(sketches=listOf(s))))
        val restored=ProjectIO.read(file).document.evaluatedSketches().single()
        assertEquals(s.constraints,restored.constraints)
        val center1=ParameterEvaluator.expression(restored.elements.first { it.id=="c1" }.u,emptyMap()).value
        val center2=ParameterEvaluator.expression(restored.elements.first { it.id=="c2" }.u,emptyMap()).value
        assertEquals(center1,center2,1e-6)
    }

    @Test fun `fixed length and diameter resolve parameter expressions again`() {
        ready()
        val values=mapOf("L" to Quantity(20.0,Dimension(1)),"D" to Quantity(8.0,Dimension(1)))
        val s=Sketch(elements=listOf(line("l","0 mm" to "0 mm","6 mm" to "2 mm"),SketchElement(id="c",shape=SketchShape.CIRCLE,u="25 mm",v="0 mm",width="4 mm")))
        val measured=s.addConstraint(SketchConstraint(type=SketchConstraintType.FIXED_LENGTH,elementIds=listOf("l"),indices=listOf(0),value="L"),values).sketch
        val dimensioned=measured.addConstraint(SketchConstraint(type=SketchConstraintType.DIAMETER,elementIds=listOf("c"),value="D"),values).sketch
        val changed=dimensioned.solveConstraints(values+mapOf("L" to Quantity(30.0,Dimension(1)),"D" to Quantity(12.0,Dimension(1)))).sketch
        val line=changed.elements.first { it.id=="l" };val a=point(line,0);val b=point(line,1)
        assertEquals(30.0,kotlin.math.hypot(b.first-a.first,b.second-a.second),1e-5)
        assertEquals(6.0,ParameterEvaluator.expression(changed.elements.first { it.id=="c" }.width,emptyMap()).value,1e-5)
        assertEquals("L",changed.constraints.first { it.type==SketchConstraintType.FIXED_LENGTH }.value)
    }

    @Test fun `line joins arc endpoint tangentially and rejects degenerate line`() {
        ready()
        val l=line("l","0 mm" to "0 mm","10 mm" to "0 mm")
        val arc=SketchElement(id="a",kind=SketchElementKind.ARC,u="10 mm",v="10 mm",width="10 mm",startAngle="-90 deg",sweepAngle="90 deg")
        val s=Sketch(elements=listOf(l,arc)).addConstraint(SketchConstraint(type=SketchConstraintType.TANGENT_ARC,elementIds=listOf("l","a"),indices=listOf(0,1,0))).sketch
        val updated=s.elements.first { it.id=="l" };val end=point(updated,1)
        assertEquals(10.0,end.first,1e-4);assertEquals(0.0,end.second,1e-4)
        val start=point(updated,0)
        assertEquals(0.0,(end.first-start.first)*(end.first-10.0)+(end.second-start.second)*(end.second-10.0),1e-4)
        val degenerate=Sketch(elements=listOf(line("z","1 mm" to "1 mm","1 mm" to "1 mm"),arc))
        assertFails { degenerate.addConstraint(SketchConstraint(type=SketchConstraintType.TANGENT_LINE_CIRCLE,elementIds=listOf("z","a"),indices=listOf(0))) }
    }

    @Test fun `conflicting fixed dimensions leave previous geometry intact`() {
        ready()
        val base=Sketch(elements=listOf(line("l","0 mm" to "0 mm","10 mm" to "0 mm")))
        val first=base.addConstraint(SketchConstraint(type=SketchConstraintType.FIXED_LENGTH,elementIds=listOf("l"),indices=listOf(0),value="20 mm")).sketch
        val conflict=first.addConstraint(SketchConstraint(type=SketchConstraintType.FIXED_LENGTH,elementIds=listOf("l"),indices=listOf(0),value="30 mm"))
        assertEquals("conflicting",conflict.status)
        assertEquals(first.elements,conflict.sketch.elements)
        assertEquals(1,first.constraints.size)
    }

    @Test fun `angle dimensions and external circle tangency solve`() {
        ready()
        val diagonal=line("d","0 mm" to "0 mm","8 mm" to "3 mm")
        val angled=Sketch(elements=listOf(diagonal)).addConstraint(SketchConstraint(type=SketchConstraintType.ANGLE,elementIds=listOf("d"),indices=listOf(0),value="45 deg")).sketch
        val p=point(angled.elements.single(),0);val q=point(angled.elements.single(),1)
        assertEquals(Math.PI/4,kotlin.math.atan2(q.second-p.second,q.first-p.first),1e-5)

        val initial=Sketch(elements=listOf(
            SketchElement(id="c1",shape=SketchShape.CIRCLE,u="0 mm",v="0 mm",width="5 mm"),
            SketchElement(id="c2",shape=SketchShape.CIRCLE,u="20 mm",v="0 mm",width="5 mm")
        ))
        val circles=initial.copy(constraints=listOf(
            SketchConstraint(type=SketchConstraintType.TANGENT_CIRCLES,elementIds=listOf("c1","c2")),
            SketchConstraint(type=SketchConstraintType.RADIUS,elementIds=listOf("c1"),value="5 mm"),
            SketchConstraint(type=SketchConstraintType.RADIUS,elementIds=listOf("c2"),value="5 mm")
        )).solveConstraints().sketch
        val a=circles.elements.first { it.id=="c1" };val b=circles.elements.first { it.id=="c2" }
        val ax=ParameterEvaluator.expression(a.u,emptyMap()).value;val ay=ParameterEvaluator.expression(a.v,emptyMap()).value
        val bx=ParameterEvaluator.expression(b.u,emptyMap()).value;val by=ParameterEvaluator.expression(b.v,emptyMap()).value
        assertEquals(10.0,kotlin.math.hypot(bx-ax,by-ay),1e-4)
    }

    @Test fun `constraint history and project round trip preserve expressions`(@TempDir folder:Path) {
        ready()
        val sketch=Sketch(id="roundtrip",name="Rundlauf",shape=SketchShape.CIRCLE,width="5 mm")
        val initial=DocumentState(parameters=listOf(Parameter("Radiuswert","8 mm")),sketches=listOf(sketch))
        val solution=sketch.addConstraint(SketchConstraint(type=SketchConstraintType.RADIUS,elementIds=listOf("profile"),value="Radiuswert"),ParameterEvaluator.evaluate(initial.parameters))
        val changed=initial.replaceSketch(solution.sketch,legacyExtrusion=false)
        val history=DocumentHistory(initial)
        assertTrue(history.commit(changed))
        assertEquals(initial,history.undo())
        assertEquals(changed,history.redo())

        val path=folder.resolve("constraint-roundtrip.kcad")
        ProjectIO.write(path,ProjectArchive(changed))
        val reopened=ProjectIO.read(path).document
        val restored=reopened.sketches.single()
        assertEquals("Radiuswert",restored.constraints.single().value)
        assertEquals(changed.sketches.single().constraints,restored.constraints)
        val reSolved=restored.solveConstraints(ParameterEvaluator.evaluate(reopened.parameters)).sketch
        assertEquals(8.0,ParameterEvaluator.expression(reSolved.elements.single().width,emptyMap()).value,1e-5)
    }
}
