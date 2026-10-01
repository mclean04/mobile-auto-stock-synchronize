package com.example.finance_planning.network.mobile

/** Strict syntax and lossless numbers; Android JSONTokener otherwise coerces numeric cells to Double. */
internal class StrictMobileJson(private val text: String) {
    private var index = 0
    fun parse(): org.json.JSONObject {
        val parsed = value(0); whitespace(); expect(index == text.length)
        return obj(parsed)
    }
    private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
    private fun take(char: Char): Boolean {
        whitespace()
        if (index < text.length && text[index] == char) { index++; return true }
        return false
    }
    private fun value(depth: Int): Any {
        expect(depth <= 64); whitespace(); expect(index < text.length)
        return when (text[index]) {
            '{' -> {
                index++
                val out = org.json.JSONObject()
                if (take('}')) return out
                val keys = mutableSetOf<String>()
                do {
                    whitespace(); val key = quoted()
                    expect(keys.add(key)); expect(take(':')); out.put(key, value(depth + 1))
                } while (take(','))
                expect(take('}'))
                out
            }
            '[' -> {
                index++; val out = org.json.JSONArray()
                if (take(']')) return out
                do { out.put(value(depth + 1)) } while (take(','))
                expect(take(']')); out
            }
            '"' -> quoted()
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            'n' -> { literal("null"); org.json.JSONObject.NULL }
            else -> number()
        }
    }
    private fun literal(value: String) { expect(text.startsWith(value, index)); index += value.length }
    private fun quoted(): String {
        val start = index
        expect(index < text.length && text[index++] == '"')
        while (index < text.length) {
            val c = text[index++]
            if (c == '"') return org.json.JSONTokener(text.substring(start, index)).nextValue() as String
            expect(c.code >= 0x20)
            if (c == '\\') {
                expect(index < text.length)
                when (text[index++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> { repeat(4) { expect(index < text.length && text[index++] in "0123456789abcdefABCDEF") } }
                    else -> throw MobileContractViolation("Invalid JSON escape")
                }
            }
        }
        throw MobileContractViolation("Unterminated JSON string")
    }
    private fun number(): java.math.BigDecimal {
        val start = index
        if (index < text.length && text[index] == '-') index++
        expect(index < text.length)
        if (text[index] == '0') index++ else {
            expect(text[index] in '1'..'9')
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] == '.') {
            index++; val fraction = index
            while (index < text.length && text[index] in '0'..'9') index++
            expect(index > fraction)
        }
        if (index < text.length && text[index] in "eE") {
            index++; if (index < text.length && text[index] in "+-") index++
            val exponent = index
            while (index < text.length && text[index] in '0'..'9') index++
            expect(index > exponent)
        }
        expect(index > start)
        return try { java.math.BigDecimal(text.substring(start, index)) }
            catch (_: NumberFormatException) { throw MobileContractViolation("Invalid JSON number") }
    }
}
