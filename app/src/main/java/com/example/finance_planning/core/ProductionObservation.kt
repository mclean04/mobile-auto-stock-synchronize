package com.example.finance_planning.core

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ObservationComponent { PLANNING, CACHE, SOURCE, TRADE, BROKER, BACKEND, NOTIFICATION }
enum class ObservationAction {
    PLANNING_REFRESH, PLANNING_CACHE, SOURCE_VALIDATE, PLACE_ORDER, CANCEL_ORDER,
    PLACED_ORDER_UPDATE, FCM_RECEIVE, NOTIFICATION_FETCH, NOTIFICATION_OPEN, NOTIFICATION_RECEIPT
}
enum class ObservationStage {
    REQUEST, CACHE_READ, CACHE_COMMIT, SOURCE_CHECK, PREFLIGHT, SERVER_ACCEPTED,
    BROKER_REQUEST, BROKER_ACKNOWLEDGED, BROKER_CANCEL_ACKNOWLEDGED, BROKER_FILLED,
    RECEIVED, DISPLAYED, OPENED, RECEIPT
}
enum class ObservationResult {
    STARTED, SUCCEEDED, FAILED, ACCEPTED, OBSERVED, NOT_OBSERVED, HIT, MISS, CHANGED
}

data class ObservationCorrelation(
    val runId: String? = null,
    val eventId: String? = null,
    val planId: String? = null,
    val actionId: String? = null,
    val requestId: String? = null,
    val brokerOrderId: String? = null,
    val version: Int? = null,
    val sourceId: String? = null,
    val sourceGeneration: Long? = null
) {
    companion object {
        fun intent(value: PlanningIntent, requestId: String? = null, brokerOrderId: String? = null) =
            ObservationCorrelation(runId = value.raw.safeText("run_id")
                    ?: value.raw.safeText("source_run_id"),
                planId = value.planId.toString(), actionId = value.intentId.toString(),
                requestId = requestId, brokerOrderId = brokerOrderId, version = value.version,
                sourceId = value.sourceContext.sourceId,
                sourceGeneration = value.sourceContext.sourceGeneration)

        fun notification(delivery: NotificationDelivery, event: JSONObject? = null): ObservationCorrelation {
            val namespace = delivery.namespace?.substringBeforeLast(':')
            val generation = delivery.namespace?.substringAfterLast(':')?.toLongOrNull()
            val source = event?.optJSONObject("source_context")
            return ObservationCorrelation(
                runId = event?.safeText("run_id"), eventId = delivery.eventId,
                planId = event?.safeText("plan_id") ?: delivery.planId,
                actionId = event?.safeText("intent_id") ?: event?.safeText("action_id"),
                version = event?.optInt("version")?.takeIf { it > 0 } ?: delivery.version?.toIntOrNull(),
                sourceId = source?.safeText("source_id") ?: namespace,
                sourceGeneration = source?.optLong("source_generation")?.takeIf { it > 0 } ?: generation
            )
        }

        fun event(value: JSONObject): ObservationCorrelation {
            val source = value.optJSONObject("source_context")
            return ObservationCorrelation(runId = value.safeText("run_id"),
                eventId = value.safeText("event_id"), planId = value.safeText("plan_id"),
                actionId = value.safeText("intent_id") ?: value.safeText("action_id"),
                version = value.optInt("version").takeIf { it > 0 },
                sourceId = source?.safeText("source_id"),
                sourceGeneration = source?.optLong("source_generation")?.takeIf { it > 0 })
        }

        private fun JSONObject.safeText(key: String): String? = optString(key)
            .takeIf { it.isNotBlank() && it != "null" }
    }
}

class ObservationError private constructor(val code: String) {
    companion object {
        val NONE = ObservationError("NONE")

        fun http(status: Int, serverCode: String? = null): ObservationError {
            val suffix = serverCode?.uppercase(Locale.ROOT)?.replace(Regex("[^A-Z0-9_]+"), "_")
                ?.take(64)?.takeIf { it.isNotBlank() }
            return ObservationError("HTTP_${status.coerceIn(0, 999)}" + (suffix?.let { "_$it" } ?: ""))
        }

        fun brokerHttp(status: Int) = ObservationError("BROKER_HTTP_${status.coerceIn(0, 999)}")

        fun fromThrowable(error: Throwable): ObservationError = ObservationError(when (error) {
            is kotlinx.coroutines.CancellationException -> "CANCELLED"
            is PlanningPreflightUnavailable -> "PREFLIGHT_UNAVAILABLE"
            is PlanningVersionChanged -> "PLANNING_VERSION_CHANGED"
            is PlanningExecutionClaimed -> "EXECUTION_ALREADY_CLAIMED"
            is PlanningGateFailure -> "PLANNING_GATE_BLOCKED"
            is AppFailure -> "APP_FAILURE"
            is java.net.SocketTimeoutException -> "TIMEOUT"
            is java.net.UnknownHostException -> "DNS"
            is javax.net.ssl.SSLException -> "TLS"
            is IOException -> "NETWORK_IO"
            is IllegalArgumentException -> "INVALID_INPUT"
            is IllegalStateException -> "INVALID_STATE"
            else -> "LOCAL_ERROR"
        })

        internal fun filtered(value: String): ObservationError = ObservationError(sanitizeCode(value))

        private fun sanitizeCode(value: String): String {
            val lowered = value.lowercase(Locale.ROOT)
            if (listOf("bearer", "token", "secret", "password", "passcode", "otp", "api_key",
                    "authorization", "aiza", "ya29.").any(lowered::contains)) return "REDACTED"
            return value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]+"), "_")
                .trim('_').take(80).ifBlank { "UNKNOWN" }
        }
    }
}

interface ObservationSink {
    fun record(component: ObservationComponent, action: ObservationAction, stage: ObservationStage,
               result: ObservationResult, correlation: ObservationCorrelation = ObservationCorrelation(),
               durationMs: Long = 0, error: ObservationError = ObservationError.NONE): Boolean

    companion object {
        val NONE = object : ObservationSink {
            override fun record(component: ObservationComponent, action: ObservationAction,
                                stage: ObservationStage, result: ObservationResult,
                                correlation: ObservationCorrelation, durationMs: Long,
                                error: ObservationError) = true
        }
    }
}

data class ObservationExport(val directory: File, val files: Int, val bytes: Long)

/** Bounded JSONL observability. It accepts only typed, allowlisted fields and never throws to callers. */
class ProductionObservationLog(
    private val directory: File,
    private val clock: () -> Instant = Instant::now,
    private val displayZone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh"),
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val retention: Duration = DEFAULT_RETENTION,
    private val beforeWrite: () -> Unit = {}
) : ObservationSink {
    init {
        require(maxFileBytes in 256..(2L * 1024 * 1024))
        require(maxFiles in 1..10)
        require(!retention.isNegative && !retention.isZero && retention <= Duration.ofDays(90))
    }

    override fun record(component: ObservationComponent, action: ObservationAction,
                        stage: ObservationStage, result: ObservationResult,
                        correlation: ObservationCorrelation, durationMs: Long,
                        error: ObservationError): Boolean = runCatching {
        synchronized(this) {
            beforeWrite()
            val now = clock()
            prepareDirectory()
            prune(now)
            val line = JSONObject()
                .put("schema_version", 1)
                .put("timestamp_utc", DateTimeFormatter.ISO_INSTANT.format(now))
                .put("timestamp_display", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(displayZone)))
                .put("display_timezone", displayZone.id)
                .put("component", component.name)
                .put("action", action.name)
                .put("stage", stage.name)
                .put("result", result.name)
                .put("duration_ms", durationMs.coerceIn(0, MAX_DURATION_MS))
                .put("error_code", error.code)
                .put("run_id", safeId(correlation.runId))
                .put("event_id", safeId(correlation.eventId))
                .put("plan_id", safeId(correlation.planId))
                .put("action_id", safeId(correlation.actionId))
                .put("request_id", safeId(correlation.requestId))
                .put("broker_order_id", safeId(correlation.brokerOrderId))
                .put("version", correlation.version?.takeIf { it > 0 }?.toString() ?: UNKNOWN)
                .put("source_id", safeId(correlation.sourceId))
                .put("source_generation", correlation.sourceGeneration?.takeIf { it > 0 }?.toString() ?: UNKNOWN)
                .toString() + "\n"
            val bytes = line.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_RECORD_BYTES)
            val active = file(0)
            if (active.exists() && active.length() > 0 && active.length() + bytes.size > maxFileBytes) rotate()
            file(0).appendBytes(bytes)
            privateFile(file(0))
        }
    }.isSuccess

    fun exportTo(destination: File): ObservationExport? = runCatching {
        synchronized(this) {
            beforeWrite()
            val now = clock()
            prepareDirectory()
            prune(now)
            require(destination.canonicalFile != directory.canonicalFile)
            require(!destination.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            if (!destination.exists()) require(destination.mkdirs())
            privateDirectory(destination)
            var count = 0
            var bytes = 0L
            files().forEach { source ->
                val target = File(destination, source.name)
                source.copyTo(target, overwrite = true)
                privateFile(target)
                count++
                bytes += target.length()
            }
            val manifest = JSONObject().put("schema_version", 1)
                .put("captured_at_utc", DateTimeFormatter.ISO_INSTANT.format(now))
                .put("source_file_count", count).put("source_bytes", bytes)
                .put("max_file_bytes", maxFileBytes).put("max_files", maxFiles)
                .put("retention_days", retention.toDays()).put("automatic_upload", false)
            File(destination, "manifest.json").writeText(manifest.toString())
            privateFile(File(destination, "manifest.json"))
            ObservationExport(destination, count, bytes)
        }
    }.getOrNull()

    internal fun files(): List<File> = (0 until maxFiles).map(::file).filter(File::isFile)

    private fun prepareDirectory() {
        if (!directory.exists()) require(directory.mkdirs())
        require(directory.isDirectory)
        privateDirectory(directory)
    }

    private fun prune(now: Instant) {
        val cutoff = now.minus(retention).toEpochMilli()
        files().filter { it.lastModified() in 1 until cutoff }.forEach { it.delete() }
    }

    private fun rotate() {
        file(maxFiles - 1).delete()
        for (index in maxFiles - 2 downTo 0) {
            val source = file(index)
            if (source.exists()) {
                val target = file(index + 1)
                target.delete()
                require(source.renameTo(target))
                privateFile(target)
            }
        }
    }

    private fun file(index: Int) = File(directory, "observation-$index.jsonl")

    private fun safeId(value: String?): String {
        if (value.isNullOrBlank()) return UNKNOWN
        val lowered = value.lowercase(Locale.ROOT)
        if (listOf("bearer", "token", "secret", "password", "passcode", "otp", "api_key",
                "authorization", "aiza", "ya29.").any(lowered::contains)) return "REDACTED"
        return value.takeIf { SAFE_ID.matches(it) } ?: UNKNOWN
    }

    private fun privateDirectory(value: File) {
        value.setReadable(false, false); value.setWritable(false, false); value.setExecutable(false, false)
        value.setReadable(true, true); value.setWritable(true, true); value.setExecutable(true, true)
    }

    private fun privateFile(value: File) {
        value.setReadable(false, false); value.setWritable(false, false); value.setExecutable(false, false)
        value.setReadable(true, true); value.setWritable(true, true)
    }

    companion object {
        const val DIRECTORY_NAME = "production-observation"
        const val DEFAULT_MAX_FILE_BYTES = 256L * 1024
        const val DEFAULT_MAX_FILES = 4
        val DEFAULT_RETENTION: Duration = Duration.ofDays(14)
        private const val MAX_RECORD_BYTES = 4096
        private const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
        private const val UNKNOWN = "UNKNOWN"
        private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,200}")

        fun elapsedMs(startedNanos: Long, nowNanos: Long = System.nanoTime()): Long =
            ((nowNanos - startedNanos).coerceAtLeast(0) / 1_000_000).coerceAtMost(MAX_DURATION_MS)
    }
}
