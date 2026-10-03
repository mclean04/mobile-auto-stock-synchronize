package com.example.finance_planning.network

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** Narrow Gson scalar adapters: reject coercion and integer truncation; DTOs own field validation. */
object PlanningScalars {
    val integer: TypeAdapter<Int> = object : TypeAdapter<Int>() {
        override fun read(reader: JsonReader): Int {
            protocol(reader.peek() == JsonToken.NUMBER)
            return try { reader.nextString().toBigDecimal().intValueExact() }
                catch (_: ArithmeticException) { throw PlanningProtocolFailure() }
                catch (_: NumberFormatException) { throw PlanningProtocolFailure() }
        }
        override fun write(out: JsonWriter, value: Int) { out.value(value) }
    }.nullSafe()
    val boolean: TypeAdapter<Boolean> = object : TypeAdapter<Boolean>() {
        override fun read(reader: JsonReader): Boolean {
            protocol(reader.peek() == JsonToken.BOOLEAN); return reader.nextBoolean()
        }
        override fun write(out: JsonWriter, value: Boolean) { out.value(value) }
    }.nullSafe()
    val string: TypeAdapter<String> = object : TypeAdapter<String>() {
        override fun read(reader: JsonReader): String {
            protocol(reader.peek() == JsonToken.STRING); return reader.nextString()
        }
        override fun write(out: JsonWriter, value: String) { out.value(value) }
    }.nullSafe()
}
