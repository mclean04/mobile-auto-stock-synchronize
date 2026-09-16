package com.example.finance_planning

import com.example.finance_planning.network.HttpFailure
import org.junit.Assert.*
import org.junit.Test

class BackendErrorTest {
    @Test fun acceptsKnownBackendReason() {
        assertEquals("identity_changed", HttpFailure.safeCode("""{"detail":"identity_changed"}"""))
        assertTrue(HttpFailure(403, "identity_changed").safe().safeMessage.contains("identity_changed"))
    }
    @Test fun refusesUntrustedErrorDetails() {
        assertNull(HttpFailure.safeCode("""{"detail":"Bearer private-token"}"""))
        assertNull(HttpFailure.safeCode("""{"detail":{"token":"private-token"}}"""))
        assertNull(HttpFailure.safeCode("<html>Cloud Run denied</html>"))
        assertNull(HttpFailure.safeCode(null))
    }
}