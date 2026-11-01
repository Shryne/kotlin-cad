package cad

import kotlin.math.abs

data class Parameter(val name: String, val expression: String)
data class Dimension(val length: Int = 0, val angle: Int = 0)
data class Quantity(val value: Double, val dimension: Dimension = Dimension()) {
    init {
        require(value.isFinite()) { "Das Ergebnis ist nicht endlich." }
        require(abs(dimension.length) <= 8 && abs(dimension.angle) <= 8) { "Zu komplexe Einheit." }
    }
    fun label(): String {
        val number = if (value != 0.0 && (abs(value) < 1e-6 || abs(value) >= 1e9))
            "%.6g".format(java.util.Locale.GERMANY, value)
        else java.text.DecimalFormat("0.######", java.text.DecimalFormatSymbols(java.util.Locale.GERMANY)).format(value)
        val units = listOf("mm" to dimension.length, "rad" to dimension.angle)
            .filter { it.second != 0 }.joinToString(" · ") { (name, power) -> if (power == 1) name else "$name^$power" }
        return if (units.isEmpty()) number else "$number $units"
    }
}

/** Small arithmetic language, with dimensional analysis. Never evaluates JVM/script code. */
object ParameterEvaluator {
    fun name(text: String): String = java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFC)
    fun normalized(parameters: List<Parameter>)=parameters.map { Parameter(name(it.name),name(it.expression)) }

    private val units = mapOf(
        "mm" to Quantity(1.0, Dimension(1)), "cm" to Quantity(10.0, Dimension(1)),
        "m" to Quantity(1000.0, Dimension(1)), "in" to Quantity(25.4, Dimension(1)),
        "rad" to Quantity(1.0, Dimension(angle = 1)), "deg" to Quantity(Math.PI / 180, Dimension(angle = 1)),
        "pi" to Quantity(Math.PI)
    )
    fun evaluate(input: List<Parameter>): Map<String, Quantity> {
        val parameters=normalized(input)
        require(parameters.size <= 128) { "Maximal 128 Parameter." }
        parameters.forEach {
            require(it.name.matches(Regex("[\\p{L}_][\\p{L}\\p{N}_]{0,63}")) && it.name !in units) {
                "Name: Buchstaben, Zahlen und Unterstrich; keine Einheit als Name."
            }
            require(it.expression.isNotBlank() && it.expression.length <= 1024) { "Formel fehlt oder ist zu lang." }
        }
        val definitions = parameters.associateBy { it.name }
        require(definitions.size == parameters.size) { "Parameternamen müssen eindeutig sein." }
        val values = linkedMapOf<String, Quantity>()
        val visiting = linkedSetOf<String>()
        fun resolve(name: String): Quantity {
            units[name]?.let { return it }
            values[name]?.let { return it }
            val definition = definitions[name] ?: throw IllegalArgumentException("Unbekannter Parameter: $name")
            require(visiting.add(name)) { "Zyklische Abhängigkeit: ${(visiting + name).joinToString(" → ")}" }
            require(visiting.size <= 64) { "Zu viele verschachtelte Abhängigkeiten." }
            try {
                val result = Parser(definition.expression, ::resolve).parse()
                values[name] = result
                return result
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$name: ${e.message}", e)
            } finally { visiting.remove(name) }
        }
        parameters.forEach { resolve(it.name) }
        return parameters.associate { it.name to values.getValue(it.name) }
    }
    fun expression(text: String, values: Map<String, Quantity>): Quantity {
        require(text.isNotBlank() && text.length <= 1024) { "Formel fehlt oder ist zu lang." }
        return Parser(text) { units[it] ?: values[it] ?: error("Unbekannter Parameter: $it") }.parse()
    }
    private class Parser(expression: String, val resolve: (String) -> Quantity) {
        val text = name(expression).replace(',', '.')
        var offset = 0
        var depth = 0
        fun parse(): Quantity {
            val value = sum()
            space()
            require(offset == text.length) { "Unerwartetes Zeichen bei Position ${offset + 1}." }
            return value
        }
        private fun sum(): Quantity {
            var left = product()
            while (true) {
                space()
                val operation = text.getOrNull(offset)
                if (operation != '+' && operation != '-') return left
                offset++
                val right = product()
                require(left.dimension == right.dimension) { "Addition und Subtraktion benötigen gleiche Einheiten." }
                left = Quantity(if (operation == '+') left.value + right.value else left.value - right.value, left.dimension)
            }
        }
        private fun product(): Quantity {
            var left = primary()
            while (true) {
                space()
                val operation = text.getOrNull(offset)
                if (operation != '*' && operation != '/') return left
                offset++
                val right = primary()
                if (operation == '/') require(right.value != 0.0) { "Division durch null." }
                val sign = if (operation == '*') 1 else -1
                left = Quantity(if (sign == 1) left.value * right.value else left.value / right.value,
                    Dimension(left.dimension.length + sign * right.dimension.length, left.dimension.angle + sign * right.dimension.angle))
            }
        }
        private fun primary(): Quantity {
            require(++depth <= 64) { "Zu viele verschachtelte Klammern." }
            try {
                space()
                val next = text.getOrNull(offset) ?: throw IllegalArgumentException("Unvollständige Formel.")
                if (next == '+' || next == '-') {
                    offset++
                    val value = primary()
                    return if (next == '-') Quantity(-value.value, value.dimension) else value
                }
                if (next == '(') {
                    offset++
                    val value = sum()
                    space(); require(text.getOrNull(offset) == ')') { "Schließende Klammer fehlt." }
                    offset++; return value
                }
                if (next.isDigit() || next == '.') {
                    val number = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
                        .find(text, offset)?.takeIf { it.range.first == offset }
                        ?: throw IllegalArgumentException("Ungültige Zahl.")
                    offset += number.value.length
                    var result = Quantity(number.value.toDouble())
                    space()
                    val start = offset
                    val name = identifier()
                    if (name in units && name != "pi") {
                        val unit = units.getValue(name)
                        result = Quantity(result.value * unit.value, unit.dimension)
                    } else offset = start
                    return result
                }
                val name = identifier()
                require(name.isNotEmpty()) { "Ungültiges Zeichen bei Position ${offset + 1}." }
                return resolve(name)
            } finally { depth-- }
        }
        private fun identifier(): String {
            val start = offset
            if (text.getOrNull(offset)?.let { it.isLetter() || it == '_' } != true) return ""
            offset++
            while (text.getOrNull(offset)?.let { it.isLetterOrDigit() || it == '_' } == true) offset++
            return text.substring(start, offset)
        }
        private fun space() { while (text.getOrNull(offset)?.isWhitespace() == true) offset++ }
    }
}
