package com.example.finance_planning.core

/** A persisted empty response is still a valid cache. Failed refreshes never overwrite it. */
object CacheFirstRead {
    suspend fun <T : Any> load(force: Boolean, cached: suspend () -> T?, fetch: suspend () -> T,
                               save: suspend (T) -> Unit): T {
        if (!force) cached()?.let { return it }
        val result = fetch()
        save(result)
        return result
    }
}
