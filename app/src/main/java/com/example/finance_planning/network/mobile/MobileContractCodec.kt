package com.example.finance_planning.network.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class MobileOperation(val value: String)

data class MobileSuccess<T : MobileDto>(val data: T, val meta: Meta)

/** Codec only: it does not construct a client, attach credentials or choose an environment. */
class MobileContractConverter : Converter.Factory() {
    override fun requestBodyConverter(type: Type, parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>, retrofit: Retrofit): Converter<*, RequestBody>? {
        val raw = getRawType(type)
        if (!MobileDto::class.java.isAssignableFrom(raw)) return null
        return Converter<MobileDto, RequestBody> { dto ->
            val json = dto.toJson()
            ContractChecks.check(raw.simpleName, json)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MOBILE_REQUEST_BYTES) throw MobileContractViolation("Request exceeds the mobile byte budget")
            bytes.toRequestBody("application/json; charset=utf-8".toMediaType())
        }
    }

    override fun responseBodyConverter(type: Type, annotations: Array<Annotation>,
        retrofit: Retrofit): Converter<ResponseBody, *>? {
        val operation = annotations.filterIsInstance<MobileOperation>().singleOrNull()?.value ?: return null
        val raw = getRawType(type)
        if (raw == MobileSuccess::class.java) {
            val parameter = (type as? ParameterizedType)?.actualTypeArguments?.singleOrNull()
                ?: throw MobileContractViolation("Missing response data type")
            val dataClass = getRawType(parameter)
            return Converter<ResponseBody, MobileSuccess<MobileDto>> { body ->
                val json = readMobileObject(body)
                ContractChecks.check(operation + "Response", json)
                MobileSuccess(MobileDtoCodecs.decode(dataClass, json.getJSONObject("data")),
                    Meta.decode(json.getJSONObject("meta")))
            }
        }
        if (operation == "health" && raw == HealthDto::class.java) return Converter<ResponseBody, HealthDto> {
            HealthDto.decode(readMobileObject(it))
        }
        return null
    }
}

internal const val MOBILE_REQUEST_BYTES = 2_097_152
internal const val MOBILE_RESPONSE_BYTES = 4_194_304L

/** The surrounding transport must also bound Retrofit's eager non-2xx buffering. */
internal fun readMobileObject(body: ResponseBody): JSONObject = body.use {
    val source = it.source()
    source.request(MOBILE_RESPONSE_BYTES + 1)
    if (source.buffer.size > MOBILE_RESPONSE_BYTES) throw MobileContractViolation("Response exceeds the mobile byte budget")
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(source.buffer.readByteArray())).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw MobileContractViolation("Invalid UTF-8 response") }
    try {
        StrictMobileJson(text).parse()
    } catch (_: org.json.JSONException) { throw MobileContractViolation("Malformed JSON response") }
}

enum class MobileTransportKind { TIMEOUT, DNS, CONNECTION, IO }

sealed interface MobileResult<out T> {
    data class Success<T>(val data: T, val meta: Meta) : MobileResult<T>
    data class ServerFailure(val status: Int, val error: ApiError, val meta: Meta) : MobileResult<Nothing>
    data class ProtocolFailure(val status: Int?, val requestId: String?) : MobileResult<Nothing>
    data class TransportFailure(val kind: MobileTransportKind, val mutationOutcomeUnknown: Boolean) : MobileResult<Nothing>
    data object ContextChanged : MobileResult<Nothing>
    data object InvalidRequest : MobileResult<Nothing>
    data object CredentialsUnavailable : MobileResult<Nothing>
}

/** No sign-out, refresh, retry, cache write or broker effect is performed by response mapping. */
object MobileResultMapper {
    fun <T : MobileDto> map(response: retrofit2.Response<MobileSuccess<T>>): MobileResult<T> {
        val status = response.code()
        val observed = response.headers()["X-Request-ID"]?.takeIf {
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(it)
        }
        return try {
            if (response.isSuccessful) {
                // The frozen inventory declares 200 objects only; no manufactured 204 success.
                val body = response.body() ?: throw MobileContractViolation("Missing success body")
                expect(status == 200 && body.meta.http_status == status.toLong() && body.meta.request_id == observed)
                MobileResult.Success(body.data, body.meta)
            } else {
                val body = response.errorBody() ?: throw MobileContractViolation("Missing failure body")
                val json = readMobileObject(body)
                ContractChecks.check("Failure", json)
                val meta = Meta.decode(json.getJSONObject("meta"))
                expect(meta.http_status == status.toLong() && meta.request_id == observed)
                MobileResult.ServerFailure(status, ApiError.decode(json.getJSONObject("error")), meta)
            }
        } catch (_: MobileContractViolation) { MobileResult.ProtocolFailure(status, observed) }
    }
}
