package com.example.finance_planning.network

import com.example.finance_planning.BuildConfig

/** Raw diagnostics are permitted only in debug builds. */
object HttpLogFormat {
    fun headers(headers: Iterable<Pair<String, String>>, log: (String) -> Unit) {
        if (!BuildConfig.DEBUG) return
        headers.forEach { (name, value) -> log("$name: $value") }
    }
    fun body(raw: String?, log: (String) -> Unit) {
        if (!BuildConfig.DEBUG || raw.isNullOrEmpty()) return
        log("")
        // Keep UTF-8 chunks small enough for Logcat, including non-ASCII text.
        raw.chunked(900).forEach(log)
    }
}
