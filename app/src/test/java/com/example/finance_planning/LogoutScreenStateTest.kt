package com.example.finance_planning

import com.example.finance_planning.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LogoutScreenStateTest {
    @Before fun text() { TestText.install() }

    @Test fun logoutDropsAccountDataAndNavigationWhileKeepingOperationLocked() {
        val privateData = JSONObject().put("private", true)
        val state = ScreenState(signedIn = true, approved = true, admin = true, busy = true,
            email = "unit@example.invalid", notificationNavigation = 4, detail = privateData,
            detailKind = DetailKind.NOTIFICATION,
            status = com.example.finance_planning.data.SessionStatus(1, 200, true, true), planning = privateData,
            dnse = privateData, notifications = listOf(privateData), selectedSource = "unit-source",
            orders = listOf(privateData), batches = listOf(privateData), localQueue = listOf("unit-pending"),
            hasDnse = true, hasProductionKeys = true, pushRegistered = true, message = "working")
        val result = state.withoutAccount(true)
        assertEquals(ScreenState(configured = true, busy = true, message = "working"), result)
        assertFalse(result.signedIn)
        assertNull(result.detail)
        assertEquals(0L, result.notificationNavigation)
    }

    @Test fun partialSignOutFailureRetainsErrorAndAllowsAnotherLoginAfterLoadingEnds() {
        val state = ScreenState(signedIn = true, approved = true, busy = false, message = "Sign-out failed; retry")
        val result = state.withoutAccount(true)
        assertFalse(result.signedIn)
        assertTrue(result.configured && !result.busy)
        assertEquals(state.message, result.message)
    }

    @Test fun repeatedCyclesNeverRestoreProtectedStateAndDoNotOverrideQaConfiguration() {
        var state = ScreenState(configured = true)
        repeat(3) {
            state = state.copy(signedIn = true, approved = true, notificationNavigation = 1,
                detail = JSONObject().put("cycle", it))
            state = state.withoutAccount(true)
            assertEquals(0L, state.notificationNavigation)
            assertNull(state.detail)
            assertTrue(state.configured && !state.busy && !state.signedIn)
        }
        assertFalse(state.withoutAccount(false).configured)
    }
}
