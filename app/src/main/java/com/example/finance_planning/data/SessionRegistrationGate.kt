package com.example.finance_planning.data

import com.example.finance_planning.network.SupersededNetworkContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fences queued registration and drains the current exchange before revocation starts. */
internal class SessionRegistrationGate(private val pending: () -> Boolean) {
    private val mutex = Mutex()
    suspend fun <T> register(action: suspend () -> T): T = mutex.withLock {
        checkOpen()
        action().also { checkOpen() }
    }
    suspend fun <T> logout(fence: () -> Unit, action: suspend () -> T): T {
        fence() // Synchronous persistent fence, before waiting for an in-flight registration.
        return mutex.withLock { action() }
    }
    fun checkOpen() { if (pending()) throw SupersededNetworkContext() }
}
