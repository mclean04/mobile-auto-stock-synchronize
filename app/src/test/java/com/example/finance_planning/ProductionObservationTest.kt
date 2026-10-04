package com.example.finance_planning

import com.example.finance_planning.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class ProductionObservationTest {
    private fun temp(name: String): File = Files.createTempDirectory(name).toFile()
    private fun lines(log: ProductionObservationLog): List<JSONObject> = log.files()
        .flatMap { it.readLines() }.filter(String::isNotBlank).map(::JSONObject)

    private fun write(log: ProductionObservationLog, sequence: Int = 1) = log.record(
        ObservationComponent.PLANNING, ObservationAction.PLANNING_REFRESH,
        ObservationStage.SERVER_ACCEPTED, ObservationResult.SUCCEEDED,
        ObservationCorrelation(runId = "run-$sequence", planId = "plan-$sequence",
            actionId = "action-$sequence", version = sequence,
            sourceId = "source-sheet-0001", sourceGeneration = 3), durationMs = sequence.toLong())

    @Test fun rotationCapsFilesAndRetentionPrunesExpiredEntries() {
        val root = temp("observation-rotation")
        try {
            var now = Instant.parse("2026-09-25T00:00:00Z")
            val log = ProductionObservationLog(File(root, "logs"), clock = { now },
                maxFileBytes = 700, maxFiles = 3, retention = Duration.ofDays(2))
            repeat(20) { assertTrue(write(log, it + 1)) }
            assertEquals(3, log.files().size)
            assertTrue(log.files().all { it.length() <= 700 })
            assertTrue(lines(log).size in 1..20)

            log.files().forEach { assertTrue(it.setLastModified(now.minus(Duration.ofDays(3)).toEpochMilli())) }
            now = now.plus(Duration.ofDays(3))
            assertTrue(write(log, 21))
            assertEquals(1, lines(log).size)
            assertEquals("run-21", lines(log).single().getString("run_id"))
        } finally { root.deleteRecursively() }
    }

    @Test fun fixedSchemaRedactsUnsafeCorrelationAndNeverStoresMessagesOrBodies() {
        val root = temp("observation-redaction")
        try {
            val log = ProductionObservationLog(File(root, "logs"))
            assertTrue(log.record(ObservationComponent.BROKER, ObservationAction.PLACE_ORDER,
                ObservationStage.BROKER_REQUEST, ObservationResult.FAILED,
                ObservationCorrelation(runId = "Bearer private-token", planId = "safe-plan",
                    actionId = "action-1", requestId = "request-1", version = 2,
                    sourceId = "source-sheet-0001", sourceGeneration = 3), 12,
                ObservationError.filtered("api_key=private-value")))
            val raw = log.files().single().readText()
            assertFalse(raw.contains("private-token"))
            assertFalse(raw.contains("private-value"))
            assertFalse(raw.contains("headers"))
            assertFalse(raw.contains("body"))
            val value = JSONObject(raw.trim())
            assertEquals("REDACTED", value.getString("run_id"))
            assertEquals("REDACTED", value.getString("error_code"))
            assertEquals("UNKNOWN", value.getString("event_id"))
            assertEquals("2", value.getString("version"))
            assertEquals(12L, value.getLong("duration_ms"))
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitExportCopiesSnapshotWithoutClearingSourceAndWritesPrivateManifest() {
        val root = temp("observation-export")
        try {
            val log = ProductionObservationLog(File(root, "logs"))
            assertTrue(write(log))
            val before = log.files().single().readBytes()
            val export = log.exportTo(File(root, "export"))!!
            assertEquals(1, export.files)
            assertEquals(before.size.toLong(), export.bytes)
            assertArrayEquals(before, File(export.directory, "observation-0.jsonl").readBytes())
            assertArrayEquals(before, log.files().single().readBytes())
            val manifest = JSONObject(File(export.directory, "manifest.json").readText())
            assertEquals(false, manifest.getBoolean("automatic_upload"))
            assertEquals(14, manifest.getLong("retention_days"))
            assertEquals(4, manifest.getInt("max_files"))
        } finally { root.deleteRecursively() }
    }

    @Test fun storageFailureIsIsolatedAndCannotRepeatBusinessMutation() {
        val root = temp("observation-failure")
        try {
            var attempts = 0
            val log = ProductionObservationLog(File(root, "logs"), beforeWrite = {
                throw IOException("simulated private storage failure")
            })
            assertFalse(write(log))
            attempts++ // The business action remains owned by its caller; logging never invokes it.
            assertEquals(1, attempts)
            assertNull(log.exportTo(File(root, "export")))
        } finally { root.deleteRecursively() }
    }
}
