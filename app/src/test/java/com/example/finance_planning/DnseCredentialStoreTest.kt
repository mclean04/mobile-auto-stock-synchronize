package com.example.finance_planning

import com.example.finance_planning.core.DnseCredentialStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnseCredentialStoreTest {
    private val values = mutableMapOf<String, String>()
    private val store = DnseCredentialStore(values::get, { k, v -> values[k] = v }, { values.remove(it) })
    private fun key(uid: String = "owner") = store.config(uid)?.let { JSONObject(it).getString("key") }
    @Test fun switchingUsesOnlyKeysOfSelectedEnvironmentAndPreservesUnits() {
        store.save("owner", "prod-key", "prod-secret", true, "1000")
        store.save("owner", "sandbox-key", "sandbox-secret", false, "1")
        assertEquals("sandbox-key", key())
        store.select("owner", true)
        assertEquals("prod-key", key())
        assertEquals("prod-secret", JSONObject(store.config("owner")!!).getString("secret"))
        assertEquals("1000", JSONObject(store.config("owner")!!).getString("vndPerUnit"))
        store.select("owner", false); assertEquals("sandbox-key", key())
    }
    @Test fun missingEnvironmentNeverFallsBackToOtherKeys() {
        store.save("owner", "prod-key", "prod-secret", true, "1")
        store.select("owner", false)
        assertNull(store.config("owner")); assertFalse(store.has("owner", false)); assertTrue(store.has("owner", true))
        assertFalse(store.production("owner"))
    }
    @Test fun deleteOnlyRemovesSelectedPair() {
        store.save("owner", "prod", "secret-p", true, "1")
        store.save("owner", "sandbox", "secret-s", false, "1")
        store.delete("owner", false)
        assertNull(key()); assertTrue(store.has("owner", true)); assertFalse(store.has("owner", false))
        store.select("owner", true); assertEquals("prod", key())
    }
    @Test fun legacyKeysMigrateWithoutChangingRawConfigOrCopyingToOtherEnvironment() {
        for (production in listOf(true, false)) {
            values.clear()
            val legacy = JSONObject().put("key", "old-key").put("secret", "old-secret")
                .put("production", production).put("vndPerUnit", "1000").toString()
            values["dnse:owner"] = legacy
            assertEquals(legacy, store.config("owner"))
            assertEquals(production, store.production("owner"))
            assertNull(values["dnse:owner"])
            assertFalse(store.has("owner", !production))
        }
    }
    @Test fun accountIsolationAndLogoutClearBothPairs() {
        store.save("owner", "prod", "secret-p", true, "1")
        store.save("owner", "sandbox", "secret-s", false, "1")
        store.save("other", "other-key", "other-secret", true, "1")
        store.clear("owner")
        assertFalse(store.has("owner", true)); assertFalse(store.has("owner", false))
        assertEquals("other-key", key("other"))
    }
}
