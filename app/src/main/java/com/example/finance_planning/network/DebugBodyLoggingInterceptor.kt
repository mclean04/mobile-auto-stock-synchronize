package com.example.finance_planning.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.ForwardingSink
import okio.BufferedSink
import okio.buffer

/** Full local Logcat is explicitly enabled for Backend debug builds only; other clients retain metadata. */
class DebugBodyLoggingInterceptor(private val log: (String) -> Unit,
    private val enabled: Boolean = com.example.finance_planning.BuildConfig.DEBUG,
    private val fullBodies: Boolean = false) : Interceptor {
    companion object { private const val MAX_ERROR_BYTES = 4096L }

    // Diagnostics must not turn a successful exchange into a failure, or mask its original error.
    private fun emit(line: String) { try { log(line) } catch (_: Exception) { } }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!com.example.finance_planning.BuildConfig.DEBUG || !enabled) return chain.proceed(chain.request())
        if (fullBodies) return inspectBackend(chain)
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

    // Keep each entry below Logcat's byte limit, including multibyte Unicode text.
    private fun chunks(prefix: String, value: String) {
        var offset = 0
        var remaining = value.codePointCount(0, value.length)
        while (remaining > 0) {
            val count = minOf(800, remaining)
            val end = value.offsetByCodePoints(offset, count)
            emit(prefix + value.substring(offset, end))
            offset = end
            remaining -= count
        }
    }

    private inner class Capture(private val prefix: String, private val limit: Long) {
        private val bytes = Buffer()
        private var truncated = false
        private var finished = false
        fun append(source: Buffer, offset: Long, count: Long) {
            if (finished) return
            val keep = minOf(count, limit - bytes.size)
            if (keep > 0) source.copyTo(bytes, offset, keep)
            if (keep < count) truncated = true
        }
        fun finish(state: String) {
            if (finished) return
            finished = true
            chunks(prefix, bytes.readUtf8())
            emit(prefix + "[body $state" + (if (truncated) "; truncated at $limit bytes" else "") + "]")
            bytes.clear()
        }
    }

    /** Observes the single real exchange. Never pre-serializes a body, drains a response or retries. */
    private fun inspectBackend(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        chunks("--> ", "${original.method} ${original.url}")
        original.headers.forEach { (name, value) -> chunks("--> ", "$name: $value") }
        val requestBody = original.body
        val requestCapture = Capture("--> BODY ", 2L * 1024 * 1024)
        val observed = requestBody?.let { body ->
            object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = body.isOneShot()
                override fun isDuplex() = body.isDuplex()
                override fun writeTo(sink: BufferedSink) {
                    val tee = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) {
                            requestCapture.append(source, 0, byteCount)
                            super.write(source, byteCount)
                        }
                    }.buffer()
                    try {
                        body.writeTo(tee)
                        tee.emit()
                        requestCapture.finish("written")
                    } catch (error: Exception) {
                        requestCapture.finish("write failed; partial/attempted")
                        throw error
                    }
                }
            }
        }
        if (requestBody == null) requestCapture.finish("absent")
        val response = try {
            chain.proceed(original.newBuilder().method(original.method, observed).build())
        } catch (error: Exception) {
            requestCapture.finish("not fully written")
            emit("<-- HTTP FAILED: ${ApiDiagnostics.failure(error)} exception=${ApiDiagnostics.exception(error)} causes=${ApiDiagnostics.causes(error)} (${elapsed()}ms)")
            throw error
        }
        requestCapture.finish("not consumed")
        emit("<-- ${response.code} (${elapsed()}ms)")
        response.headers.forEach { (name, value) -> chunks("<-- ", "$name: $value") }
        val body = response.body ?: return response.also { emit("<-- BODY [body absent]") }
        val capture = Capture("<-- BODY ", 4L * 1024 * 1024)
        val source = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                val count = super.read(sink, byteCount)
                if (count > 0) capture.append(sink, sink.size - count, count)
                if (count == -1L) capture.finish("complete")
                count
            } catch (error: Exception) {
                capture.finish("read failed; partial")
                emit("<-- BODY FAILED: ${ApiDiagnostics.failure(error)} exception=${ApiDiagnostics.exception(error)} causes=${ApiDiagnostics.causes(error)} (${elapsed()}ms)")
                throw error
            }
            override fun close() {
                capture.finish("closed before EOF; partial/not consumed")
                super.close()
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
    }

}
