package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Debug metadata only: no header values, raw URLs, request bodies or response bodies. */
class DebugBodyLoggingInterceptor(private val log: (String) -> Unit, private val enabled: Boolean = com.example.finance_planning.BuildConfig.DEBUG) : Interceptor {
    companion object { private const val MAX_ERROR_BYTES = 4096L }

    // Diagnostics must not turn a successful exchange into a failure, or mask its original error.
    private fun emit(line: String) { try { log(line) } catch (_: Exception) { } }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!com.example.finance_planning.BuildConfig.DEBUG || !enabled) return chain.proceed(chain.request())
        val request = chain.request()
        val method = request.method.takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD") } ?: "other"
        val uri = request.url.toUri()
        val route = "${ApiDiagnostics.service(uri)} ${ApiDiagnostics.route(uri)}"
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        emit("--> $method $route")
        val response = try {
            chain.proceed(request)
        } catch (error: Exception) {
            emit("<-- HTTP FAILED: ${ApiDiagnostics.failure(error)} $method $route (${elapsed()}ms)")
            throw error
        }
        emit("<-- ${response.code} $method $route (${elapsed()}ms)")
        val body = response.body ?: return response
        // Observe only bytes the caller reads. Never peek, drain, or serialize a request for logging.
        // Keep a bounded error sample solely to select a fixed allowlisted code after a complete read.
        val sample = Buffer()
        var sampled = 0L
        var finished = false
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                val read = super.read(sink, byteCount)
                if (read > 0 && !response.isSuccessful && sampled <= MAX_ERROR_BYTES) {
                    if (read <= MAX_ERROR_BYTES - sampled) {
                        sink.copyTo(sample, sink.size - read, read)
                        sampled += read
                    } else {
                        sample.clear()
                        sampled = MAX_ERROR_BYTES + 1
                    }
                }
                if (read == -1L && !finished) {
                    finished = true
                    if (!response.isSuccessful && sampled <= MAX_ERROR_BYTES) {
                        ApiDiagnostics.httpErrorCode(sample.readUtf8())?.let {
                            emit("<-- error-code: $it $method $route")
                        }
                    }
                    sample.clear()
                }
                read
            } catch (error: Exception) {
                sample.clear()
                finished = true
                emit("<-- BODY FAILED: ${ApiDiagnostics.failure(error)} $method $route (${elapsed()}ms)")
                throw error
            }

            override fun close() {
                sample.clear()
                try { super.close() } catch (error: Exception) {
                    emit("<-- BODY CLOSE FAILED: ${ApiDiagnostics.failure(error)} $method $route (${elapsed()}ms)")
                    throw error
                }
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
    }
}
