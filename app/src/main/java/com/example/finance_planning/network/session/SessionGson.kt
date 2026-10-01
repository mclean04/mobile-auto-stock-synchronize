package com.example.finance_planning.network.session

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/** One small Gson boundary for the pinned session contract; legacy converters remain independent. */
object SessionGson {
    val responseType: Type = object : TypeToken<BaseResponse<SessionRegistrationDto>>() {}.type
    private fun demand(value: Boolean) { if (!value) throw SessionProtocolFailure() }
    internal fun uuid(value: String): String = value.also {
        demand(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(it))
    }
    private fun JsonReader.fields(vararg required: String, read: (String) -> Unit) {
        demand(peek() == JsonToken.BEGIN_OBJECT)
        beginObject()
        val remaining = required.toMutableSet()
        while (hasNext()) { val name = nextName(); demand(remaining.remove(name)); read(name) }
        endObject(); demand(remaining.isEmpty())
    }
    private fun JsonReader.text(max: Int): String {
        demand(peek() == JsonToken.STRING)
        return nextString().also { demand(it.codePointCount(0, it.length) <= max) }
    }
    private fun JsonReader.number(min: Int, max: Int): Int {
        demand(peek() == JsonToken.NUMBER)
        val raw = nextString()
        val value = try { raw.toBigDecimal().intValueExact() } catch (_: ArithmeticException) {
            throw SessionProtocolFailure()
        } catch (_: NumberFormatException) { throw SessionProtocolFailure() }
        return value.also { demand(it in min..max) }
    }
    private fun JsonReader.bool(): Boolean { demand(peek() == JsonToken.BOOLEAN); return nextBoolean() }
    private fun JsonReader.nil() { demand(peek() == JsonToken.NULL); nextNull() }
    private fun <T> JsonReader.nullable(read: JsonReader.() -> T): T? =
        if (peek() == JsonToken.NULL) { nil(); null } else read()
    private fun <T : Enum<T>> JsonReader.choice(values: Array<T>): T {
        val value = text(100); return values.firstOrNull { it.name == value } ?: throw SessionProtocolFailure()
    }
    private fun <T> JsonReader.list(max: Int, read: JsonReader.() -> T): List<T> {
        demand(peek() == JsonToken.BEGIN_ARRAY); beginArray()
        val result = mutableListOf<T>()
        while (hasNext()) { demand(result.size < max); result += read() }
        endArray(); return result
    }
    private fun JsonReader.status(): SyncStatusDto {
        var pending = 0; var cap = 0; var writes = false; var role = SessionRole.uploader
        fields("pending_sheet_batches", "count_capped_at", "sheet_writes", "role") {
            when (it) {
                "pending_sheet_batches" -> pending = number(0, 200)
                "count_capped_at" -> cap = number(200, 200)
                "sheet_writes" -> writes = bool()
                "role" -> role = choice(SessionRole.entries.toTypedArray())
            }
        }
        return SyncStatusDto(pending, cap, writes, role)
    }
    private fun JsonReader.registration(): DeviceRegistrationDto {
        var id = ""; var registered = false
        fields("device_id", "registered") {
            when (it) { "device_id" -> id = uuid(text(36)); "registered" -> registered = bool() }
        }
        demand(registered); return DeviceRegistrationDto(id, registered)
    }
    private fun JsonReader.data(): SessionRegistrationDto {
        var status: SyncStatusDto? = null; var registration: DeviceRegistrationDto? = null
        fields("session", "registration") {
            when (it) { "session" -> status = status(); "registration" -> registration = registration() }
        }
        return SessionRegistrationDto(requireNotNull(status), requireNotNull(registration))
    }
    private fun JsonReader.meta(): ResponseMeta {
        var version = ""; var id = ""; var status = 0
        fields("contract_version", "request_id", "http_status", "pagination") {
            when (it) {
                "contract_version" -> version = text(100).also { demand(it == "mobile-envelope.v2") }
                "request_id" -> id = uuid(text(36)).also {
                    demand(it == it.lowercase() && it[14] == '4' && it[19] in "89ab")
                }
                "http_status" -> status = number(100, 599)
                "pagination" -> nil()
            }
        }
        return ResponseMeta(version, id, status, null)
    }
    private fun JsonReader.code(max: Int) = text(max).also { demand(Regex("[a-z][a-z0-9_]*").matches(it)) }
    private fun JsonReader.issue(): ValidationIssue {
        var location = IssueLocation.body; var pointer = ""; var code = ""; var message = ""
        fields("location", "pointer", "code", "message") {
            when (it) {
                "location" -> location = choice(IssueLocation.entries.toTypedArray())
                "pointer" -> pointer = text(256).also { demand(Regex("(?:/(?:[^~/]|~[01])*)*").matches(it)) }
                "code" -> code = code(80)
                "message" -> message = text(200)
            }
        }
        return ValidationIssue(location, pointer, code, message)
    }
    private fun JsonReader.outcome(): OperationOutcome {
        var id: String? = null; var database = DatabaseOutcome.UNKNOWN; var projection = ProjectionOutcome.UNKNOWN
        var environment: TradingEnvironment? = null; var steps = emptyList<SessionCommittedStep>()
        fields("operation_id", "database", "projection", "environment", "committed_steps") {
            when (it) {
                "operation_id" -> id = nullable { uuid(text(36)) }
                "database" -> database = choice(DatabaseOutcome.entries.toTypedArray())
                "projection" -> projection = choice(ProjectionOutcome.entries.toTypedArray())
                "environment" -> environment = nullable { choice(TradingEnvironment.entries.toTypedArray()) }
                "committed_steps" -> steps = list(9) { choice(SessionCommittedStep.entries.toTypedArray()) }
            }
        }
        demand(id != null && environment == null && steps.distinct() == steps)
        when (database) {
            DatabaseOutcome.NOT_APPLIED -> demand(projection == ProjectionOutcome.NOT_APPLICABLE && steps.isEmpty())
            DatabaseOutcome.COMMITTED -> demand(projection == ProjectionOutcome.NOT_APPLICABLE && steps == listOf(SessionCommittedStep.DEVICE_REGISTRATION))
            DatabaseOutcome.UNKNOWN -> demand(projection == ProjectionOutcome.UNKNOWN && steps.isEmpty())
        }
        return OperationOutcome(id, database, projection, environment, steps)
    }
    private fun JsonReader.error(): ApiError {
        var code = ""; var message = ""; var issues = emptyList<ValidationIssue>(); var operation: OperationOutcome? = null
        fields("code", "message", "validation", "operation") {
            when (it) {
                "code" -> code = code(100)
                "message" -> message = text(300)
                "validation" -> issues = list(50) { issue() }
                "operation" -> operation = nullable { outcome() }
            }
        }
        return ApiError(code, message, issues, operation)
    }
    val gson = GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls()
        .registerTypeAdapter(responseType, object : TypeAdapter<BaseResponse<SessionRegistrationDto>>() {
            override fun read(reader: JsonReader): BaseResponse<SessionRegistrationDto> = with(reader) {
                var code = 0; var data: SessionRegistrationDto? = null; var error: ApiError? = null; var meta: ResponseMeta? = null
                fields("code", "data", "error", "meta") {
                    when (it) {
                        "code" -> code = number(100, 599)
                        "data" -> data = nullable { data() }
                        "error" -> error = nullable { error() }
                        "meta" -> meta = meta()
                    }
                }
                demand(meta?.http_status == code)
                demand(if (code == 200) data != null && error == null else code in 400..599 && data == null && error != null)
                BaseResponse(code, data, error, requireNotNull(meta))
            }
            override fun write(out: JsonWriter, value: BaseResponse<SessionRegistrationDto>?) =
                throw UnsupportedOperationException("Response only")
        })
        .registerTypeAdapter(SessionRegistrationRequest::class.java, object : TypeAdapter<SessionRegistrationRequest>() {
            override fun read(reader: JsonReader): SessionRegistrationRequest = throw UnsupportedOperationException("Request only")
            override fun write(out: JsonWriter, value: SessionRegistrationRequest) {
                uuid(value.device_id); demand(value.fcm_token.codePointCount(0, value.fcm_token.length) in 20..4096)
                out.beginObject().name("device_id").value(value.device_id)
                    .name("fcm_token").value(value.fcm_token).endObject()
            }
        }).create()

    fun decode(body: ResponseBody): BaseResponse<SessionRegistrationDto> = body.use {
        try {
            val source = it.source()
            source.request(4_194_305)
            if (source.buffer.size > 4_194_304) throw SessionProtocolFailure()
            val bytes = source.readByteArray()
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            gson.fromJson<BaseResponse<SessionRegistrationDto>>(text, responseType) ?: throw SessionProtocolFailure()
        } catch (_: Exception) { throw SessionProtocolFailure() }
    }
}

class SessionConverter : Converter.Factory() {
    override fun requestBodyConverter(type: Type, parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>, retrofit: Retrofit): Converter<*, RequestBody>? =
        if (type == SessionRegistrationRequest::class.java) Converter<SessionRegistrationRequest, RequestBody> {
            SessionGson.gson.toJson(it).toRequestBody("application/json; charset=utf-8".toMediaType())
        } else null
    override fun responseBodyConverter(type: Type, annotations: Array<Annotation>, retrofit: Retrofit): Converter<ResponseBody, *>? =
        if (type == SessionGson.responseType) Converter<ResponseBody, BaseResponse<SessionRegistrationDto>>(SessionGson::decode) else null
}
