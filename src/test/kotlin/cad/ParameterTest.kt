package cad

import org.junit.jupiter.api.Test
import kotlin.test.*

class ParameterTest {
    @Test fun unicodeNamesNormalizeAndRoundTrip() {
        val parameters=listOf(Parameter("Öffnung","20 mm"),Parameter("ho\u0308he","O\u0308ffnung + 5 mm"),Parameter("größer","höhe * 2"))
        val values=ParameterEvaluator.evaluate(parameters)
        assertEquals(25.0,values.getValue("höhe").value)
        assertEquals(50.0,values.getValue("größer").value)
        assertFailsWith<IllegalArgumentException> { ParameterEvaluator.evaluate(listOf(Parameter("höhe","1 mm"),Parameter("ho\u0308he","2 mm"))) }
        val folder=java.nio.file.Files.createTempDirectory("unicode-parameters")
        try {
            val path=folder.resolve("unicode.kcad")
            ProjectIO.write(path,ProjectArchive(DocumentState(parameters=parameters)))
            val state=ProjectIO.read(path).document
            assertEquals(listOf("Öffnung","höhe","größer"),state.parameters.map { it.name })
            assertEquals(values,ParameterEvaluator.evaluate(state.parameters))
            val history=DocumentHistory(state);history.commit(state.copy(parameters=state.parameters+Parameter("maß","größer")))
            assertEquals(state,history.undo());assertEquals(4,history.redo().parameters.size)
        } finally { java.nio.file.Files.walk(folder).use { files -> files.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) } }
    }
    @Test fun formulasResolveDependenciesRegardlessOfOrder() {
        val definitions = listOf(Parameter("half", "(width + margin) / 2"), Parameter("width", "2 in"), Parameter("margin", "1,2 cm"))
        val values = ParameterEvaluator.evaluate(definitions)
        assertEquals(31.4, values.getValue("half").value, 1e-10)
        assertEquals(Dimension(1), values.getValue("half").dimension)
        val changed = ParameterEvaluator.evaluate(definitions.map { if (it.name == "width") it.copy(expression = "60 mm") else it })
        assertEquals(36.0, changed.getValue("half").value)
    }
    @Test fun scalarLengthAreaAndAnglesRemainDistinct() {
        val values = ParameterEvaluator.evaluate(listOf(
            Parameter("ratio", "4 mm / 2 mm"), Parameter("area", "3 mm * 5 mm"),
            Parameter("turn", "90 deg"), Parameter("negative", "-(2 mm + 3 mm)"), Parameter("small", "1e-3 m")
        ))
        assertEquals(Quantity(2.0), values.getValue("ratio"))
        assertEquals(Quantity(15.0, Dimension(2)), values.getValue("area"))
        assertEquals(Math.PI / 2, values.getValue("turn").value, 1e-10)
        assertEquals(Dimension(angle = 1), values.getValue("turn").dimension)
        assertEquals(-5.0, values.getValue("negative").value)
        assertEquals(1.0, values.getValue("small").value)
        assertEquals("60 mm", Quantity(60.0, Dimension(1)).label())
    }
    @Test fun incompatibleUnitsMissingReferencesAndCyclesAreRejected() {
        for (formula in listOf("1 mm + 2", "1 mm + 2 deg", "missing / 2", "1 / 0", "1e309", "Runtime.exec(1)", "2 ** 3")) {
            assertFailsWith<IllegalArgumentException>(formula) { ParameterEvaluator.evaluate(listOf(Parameter("width", formula))) }
        }
        assertFailsWith<IllegalArgumentException> {
            ParameterEvaluator.evaluate(listOf(Parameter("a", "b + 1 mm"), Parameter("b", "a + 1 mm")))
        }
    }
    @Test fun namesDuplicatesAndRecursionAreBounded() {
        for (name in listOf("mm", "2width", "", "with space")) {
            assertFailsWith<IllegalArgumentException> { ParameterEvaluator.evaluate(listOf(Parameter(name, "1 mm"))) }
        }
        assertFailsWith<IllegalArgumentException> {
            ParameterEvaluator.evaluate(listOf(Parameter("x", "1"), Parameter("x", "2")))
        }
        assertFailsWith<IllegalArgumentException> {
            ParameterEvaluator.evaluate(listOf(Parameter("x", "(".repeat(70) + "1" + ")".repeat(70))))
        }
    }
}
