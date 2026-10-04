package com.example.finance_planning.network.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Messages deliberately contain no received values, tokens or response bodies. */
class MobileContractViolation(message: String = "Backend response violates the mobile contract") : IllegalArgumentException(message)

interface MobileDto { fun toJson(): JSONObject }

/** Optional is distinct from a present null. Neither is converted to an empty value. */
sealed interface WireField<out T> {
    data object Absent : WireField<Nothing>
    data class Present<T>(val value: T) : WireField<T>
}

/** Only the contract's scalar cells / decimal request unions use this representation. */
sealed interface WireScalar {
    data class Text(val value: String) : WireScalar
    data class Numeric(val value: BigDecimal) : WireScalar
    data class Flag(val value: Boolean) : WireScalar
    companion object {
        fun decode(value: Any?): WireScalar = when (value) {
            is String -> Text(value)
            is Number -> Numeric(decimal(value))
            is Boolean -> Flag(value)
            else -> throw MobileContractViolation("Expected a scalar")
        }
    }
}

internal fun clean(value: Any?): Any? = if (value === JSONObject.NULL) null else value
internal fun expect(valid: Boolean) { if (!valid) throw MobileContractViolation() }
internal fun obj(value: Any?): JSONObject = value as? JSONObject ?: throw MobileContractViolation("Expected an object")
internal fun string(value: Any?): String = value as? String ?: throw MobileContractViolation("Expected a string")
internal fun boolean(value: Any?): Boolean = value as? Boolean ?: throw MobileContractViolation("Expected a boolean")
internal fun decimal(value: Any?): BigDecimal {
    expect(value is Number)
    return try { BigDecimal(value.toString()) } catch (_: NumberFormatException) { throw MobileContractViolation("Expected a finite number") }
}
internal fun integer(value: Any?): Long = try { decimal(value).longValueExact() }
    catch (_: ArithmeticException) { throw MobileContractViolation("Integer outside supported range") }
internal fun array(value: Any?): List<Any?> {
    val a = value as? JSONArray ?: throw MobileContractViolation("Expected an array")
    return (0 until a.length()).map { clean(a.get(it)) }
}
internal fun <T> nullable(value: Any?, decode: (Any) -> T): T? = clean(value)?.let(decode)
internal fun <T> decodeOrNull(decode: () -> T): T? = try { decode() } catch (_: MobileContractViolation) { null }
internal fun matches(check: () -> Unit): Boolean = try { check(); true } catch (_: MobileContractViolation) { false }

internal fun hasType(value: Any?, type: String): Boolean = when (type) {
    "null" -> value == null
    "object" -> value is JSONObject
    "array" -> value is JSONArray
    "string" -> value is String
    "boolean" -> value is Boolean
    "number" -> value is Number && matches { decimal(value) }
    "integer" -> value is Number && matches { expect(decimal(value).stripTrailingZeros().scale() <= 0) }
    else -> false
}
internal fun enumContains(encoded: String, value: Any?): Boolean = array(JSONArray(encoded)).any { allowed ->
    if (allowed is Number && value is Number) decimal(allowed).compareTo(decimal(value)) == 0 else allowed == value
}
internal fun validFormat(format: String, value: String): Boolean = try {
    when (format) {
        "uuid" -> { UUID.fromString(value); true }
        "date-time" -> { OffsetDateTime.parse(value); true }
        "date" -> { LocalDate.parse(value); true }
        else -> false
    }
} catch (_: IllegalArgumentException) { false }
  catch (_: java.time.format.DateTimeParseException) { false }

internal fun objectJson(values: Map<String, Any?>): JSONObject = JSONObject().also { out ->
    for ((key, value) in values) if (value !== WireField.Absent) out.put(key, encodedValue(value))
}
private fun encodedValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is WireField.Present<*> -> encodedValue(value.value)
    WireField.Absent -> throw MobileContractViolation("Absent value is not a JSON array element")
    is MobileDto -> value.toJson()
    is WireScalar.Text -> value.value
    is WireScalar.Numeric -> value.value
    is WireScalar.Flag -> value.value
    is List<*> -> JSONArray().also { a -> value.forEach { a.put(encodedValue(it)) } }
    is String, is Number, is Boolean -> value
    else -> throw MobileContractViolation("Unsupported wire value")
}
