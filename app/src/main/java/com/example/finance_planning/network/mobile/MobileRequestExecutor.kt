package com.example.finance_planning.network.mobile

import com.example.finance_planning.network.HttpResponseTooLarge
import com.example.finance_planning.network.SupersededNetworkContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException

/** The application adapter must advance generation for logout/account changes, including UID reuse. */
data class MobileOwner(val uid: String, val generation: Long)

interface MobileIdentityProvider {
    fun current(): MobileOwner?
    suspend fun headers(owner: MobileOwner): Map<String, String>
}

/** No header values live in the shared client; each invocation gets its own immutable lease. */
class MobileRequestContext internal constructor(val owner: MobileOwner?, private val verify: () -> Unit) {
    fun check() {
        try { verify() } catch (error: Exception) {
            if (error is IOException) throw error
            throw SupersededNetworkContext().apply { initCause(error) }
        }
    }
}

data class OwnedMobileResult<out T>(val owner: MobileOwner?, val result: MobileResult<T>)
sealed interface MobileHealthResult {
    data class Success(val data: HealthDto) : MobileHealthResult
    data class Failure(val reason: MobileResult<Nothing>) : MobileHealthResult
}

/** Injected into the generated DataSource; no retry, sign-out, cache or domain-journal effects. */
class MobileRequestExecutor(private val identity: MobileIdentityProvider, private val active: () -> Unit) {
    suspend fun <T : MobileDto> execute(operation: String, mutation: Boolean,
        path: JSONObject, query: JSONObject, body: MobileDto?,
        request: suspend (Map<String, String>, MobileRequestContext) -> Response<MobileSuccess<T>>): OwnedMobileResult<T> {
        val owner = identity.current()
        if (owner == null) return OwnedMobileResult(null, MobileResult.ContextChanged)
        val context = MobileRequestContext(owner) {
            active()
            if (identity.current() != owner) throw SupersededNetworkContext()
        }
        fun result(value: MobileResult<T>) = OwnedMobileResult(owner, value)
        val headers = try {
            context.check()
            ContractChecks.check(operation + "Request", JSONObject().put("path", path).put("query", query)
                .put("body", body?.toJson() ?: JSONObject.NULL))
            if (body != null && body.toJson().toString().toByteArray(Charsets.UTF_8).size > MOBILE_REQUEST_BYTES)
                throw MobileContractViolation("Request exceeds the mobile byte budget")
            identity.headers(owner).toMap().also {
                context.check()
                ContractChecks.check("AuthHeaders", JSONObject(it))
                if (it.any { (key, value) -> '\r' in key || '\n' in key || '\r' in value || '\n' in value })
                    throw MobileContractViolation("Invalid credential header")
            }
        } catch (error: CancellationException) { throw error
        } catch (_: SupersededNetworkContext) { return result(MobileResult.ContextChanged)
        } catch (_: MobileContractViolation) { return result(MobileResult.InvalidRequest)
        } catch (_: Exception) { return result(MobileResult.CredentialsUnavailable) }
        return try {
            val response = request(headers, context)
            val mapped = MobileResultMapper.map(response)
            // Mutations retain their original owner and known/unknown server result for the journal.
            if (!mutation) context.check()
            result(mapped)
        } catch (error: CancellationException) { throw error
        } catch (_: SupersededNetworkContext) { result(MobileResult.ContextChanged)
        } catch (_: MobileContractViolation) { result(MobileResult.ProtocolFailure(null, null))
        } catch (_: HttpResponseTooLarge) { result(MobileResult.ProtocolFailure(null, null))
        } catch (error: IOException) { result(MobileResult.TransportFailure(transportKind(error), mutation)) }
    }

    suspend fun health(request: suspend (MobileRequestContext) -> Response<HealthDto>): MobileHealthResult = try {
        val context = MobileRequestContext(null, active)
        context.check()
        val response = request(context)
        context.check()
        val body = response.body()
        if (response.code() == 200 && body != null) MobileHealthResult.Success(body)
        else { response.errorBody()?.close(); MobileHealthResult.Failure(MobileResult.ProtocolFailure(response.code(), null)) }
    } catch (error: CancellationException) { throw error
    } catch (_: SupersededNetworkContext) { MobileHealthResult.Failure(MobileResult.ContextChanged)
    } catch (_: MobileContractViolation) { MobileHealthResult.Failure(MobileResult.ProtocolFailure(null, null))
    } catch (_: HttpResponseTooLarge) { MobileHealthResult.Failure(MobileResult.ProtocolFailure(null, null))
    } catch (error: IOException) { MobileHealthResult.Failure(MobileResult.TransportFailure(transportKind(error), false)) }

    private fun transportKind(error: IOException): MobileTransportKind = when (error) {
        is java.net.SocketTimeoutException -> MobileTransportKind.TIMEOUT
        is java.net.UnknownHostException -> MobileTransportKind.DNS
        is java.net.ConnectException -> MobileTransportKind.CONNECTION
        else -> MobileTransportKind.IO
    }
}
