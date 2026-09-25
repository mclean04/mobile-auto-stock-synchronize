package com.example.finance_planning

import com.example.finance_planning.core.*
import com.example.finance_planning.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class QaStartupIsolationTest {
    private val device = "d506628f-c0b8-4d6a-9c21-612128341ede"
    private val campaign = QaCampaign("campaign", "notifications", "a".repeat(64), "NOTIFICATION")
    private val config = QaNotificationConfig("qa-user", device, "qa-source-A0001:3", "unit-only", campaign)
    private val files = mutableListOf<File>()
    private fun admission() = JSONObject().put("schema_version", "finance-qa-startup-admission.v1")
        .put("mode", "QA_NOTIFICATION").put("firebase_project_id", "auto-stock-synchronization")
        .put("target_uid", config.targetUid).put("target_device_id", device)
        .put("notification_namespace", config.namespace).put("campaign", campaign.json())
    private fun load(raw: String): File = File.createTempFile("qa-startup-", ".json").also {
        files.add(it); it.writeText(raw); QaStartupIsolation.loadPrivateSelection(it)
    }
    @After fun cleanup() {
        files.forEach { it.delete() }
        QaStartupIsolation.loadPrivateSelection(File("/nonexistent-qa-unit-selection"))
    }
    private fun denied(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun trustedAdmissionSurvivesReloadAndMissingMalformedOrWrongIdentityNeverFallsBack() {
        load("{partial") // A trusted private selection closes ordinary as well as isolated builds.
        assertTrue(QaStartupIsolation.active)
        denied { QaStartupIsolation.requireNotification(config, config.targetUid, device) }
        val file = load(admission().toString())
        QaStartupIsolation.requireNotification(config, config.targetUid, device)
        QaStartupIsolation.loadPrivateSelection(file) // process reconstruction uses identical private selection
        QaStartupIsolation.requireNotification(config, config.targetUid, device)
        denied { QaStartupIsolation.requireBusiness() }
        denied { QaStartupIsolation.requireNotification(config, "other-user", device) }
        denied { QaStartupIsolation.requireNotification(config.copy(campaign = campaign.copy(sessionId = "other")), config.targetUid, device) }
        denied { QaStartupIsolation.requireNotification(config.copy(campaign = null), config.targetUid, device) }
        for (raw in listOf("{bad", "{}", admission().put("mode", "ORDINARY").toString(),
                admission().put("firebase_project_id", "other-project").toString())) {
            load(raw)
            denied { QaStartupIsolation.requireNotification(config, config.targetUid, device) }
            denied { QaStartupIsolation.requireBusiness() }
            assertFalse(QaStartupIsolation.firebaseAllowed("auto-stock-synchronization"))
        }
    }

    @Test fun absentSelectionUsesBuildModeButBrokenLinksRemainClosed() {
        val file = File.createTempFile("qa-absent-", ".json").also { files.add(it); it.delete() }
        QaStartupIsolation.loadPrivateSelection(file)
        assertEquals(BuildConfig.QA_STARTUP_ISOLATED, QaStartupIsolation.active)
        assertFalse(QaStartupIsolation.admissionValid)
        java.nio.file.Files.createSymbolicLink(file.toPath(), File(file.parentFile, "missing-admission-target").toPath())
        QaStartupIsolation.loadPrivateSelection(file)
        assertTrue(QaStartupIsolation.active)
        assertFalse(QaStartupIsolation.admissionValid)
        denied { QaStartupIsolation.requireBusiness() }
    }

    @Test fun defaultHttpsDnseReadAndIndependentTradingClientDenyBeforeAnyRequest() = runBlocking {
        load(admission().toString())
        val intercepted = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { intercepted.incrementAndGet(); error("must not reach client") }.build()
        val broker = DnseTradingApi("unit-key", "unit-secret", false, client)
        assertTrue(runCatching { Transport().request("https://example.invalid/v1/sync/batches", "POST", emptyMap(), JSONObject()) }.exceptionOrNull() is QaIsolationDenied)
        assertTrue(runCatching { DnseHttpTransport.request("https://openapi.dnse.com.vn/accounts", emptyMap()) }.exceptionOrNull() is QaIsolationDenied)
        assertTrue(runCatching { broker.accounts() }.exceptionOrNull() is QaIsolationDenied)
        assertTrue(runCatching { broker.emailOtp() }.exceptionOrNull() is QaIsolationDenied)
        assertTrue(runCatching { broker.cancel("account", "order", "unit-token") }.exceptionOrNull() is QaIsolationDenied)
        assertEquals(0, intercepted.get())
    }

    @Test fun trustedInMemoryFakeCannotFallThroughToDnsOrProduction() = runBlocking {
        val dns = AtomicInteger()
        val network = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                dns.incrementAndGet(); error("DNS must be unreachable")
            }
        }).build()
        val fallThrough = DnseTradingApi("unit-key", "unit-secret", false, network, qaInMemoryFakeOnly = true)
        assertTrue(runCatching { fallThrough.accounts() }.exceptionOrNull() is QaIsolationDenied)
        val fake = network.newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("unit fake")
                .body("[]".toResponseBody()).build()
        }.build()
        assertTrue(DnseTradingApi("unit-key", "unit-secret", false, fake, qaInMemoryFakeOnly = true).accounts().isEmpty())
        denied { DnseTradingApi("unit-key", "unit-secret", true, fake, qaInMemoryFakeOnly = true) }
        assertEquals(0, dns.get())
    }

    @Test fun tokenCallbacksAndRecoveredBusinessWorkNeverDispatchBusinessInQa() {
        var business = 0; var qa = 0
        val isolated = QaStartupPolicy(true)
        isolated.tokenCallback({ business++ }, { qa++ })
        val pendingBusinessJournal = mutableListOf("pending-original-report")
        for ((endpoint, campaignBound) in listOf(null to false, NotificationEndpoint.PRODUCTION to false,
                NotificationEndpoint.QA to false)) {
            if (!isolated.deferSavedWork(endpoint, campaignBound)) pendingBusinessJournal.clear()
        }
        assertEquals(listOf("pending-original-report"), pendingBusinessJournal)
        assertEquals(0, business); assertEquals(1, qa)
        assertFalse(isolated.deferSavedWork(NotificationEndpoint.QA, true))
        val ordinary = QaStartupPolicy(false)
        ordinary.requireBusiness()
        ordinary.tokenCallback({ business++ }, { qa++ })
        assertFalse(ordinary.deferSavedWork(null, false))
        assertEquals(1, business); assertEquals(1, qa)
    }
}
