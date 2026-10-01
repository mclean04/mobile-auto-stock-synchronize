package com.example.finance_planning.network

import com.example.finance_planning.BuildConfig

/** Compatibility helpers: arbitrary header/body contents must never reach a log sink. */
object HttpLogFormat {
    fun headers(headers: Iterable<Pair<String, String>>, log: (String) -> Unit) {
        if (!BuildConfig.DEBUG) return
        log("(headers omitted)")
    }
    fun body(raw: String?, log: (String) -> Unit) {
        if (!BuildConfig.DEBUG || raw.isNullOrEmpty()) return
        log("(body omitted)")
    }
}
