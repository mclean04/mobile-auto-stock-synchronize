package com.example.finance_planning.core

import com.example.finance_planning.BuildConfig
import java.net.URI

/** Debug-only Backend origin policy. Identity, FCM and DNSE keep their normal configuration. */
object LocalBackend {
    val active: Boolean get() = BuildConfig.DEBUG && BuildConfig.LOCAL_BACKEND
    fun accepts(url: String): Boolean = active && runCatching {
        val origin = URI(BuildConfig.BACKEND_ORIGIN)
        val uri = URI(url)
        origin.scheme == "http" && origin.host == "127.0.0.1" && origin.port in 1..65535 &&
            origin.userInfo == null && origin.fragment == null && origin.rawQuery == null &&
            origin.rawPath in listOf("", "/") &&
            uri.scheme == origin.scheme && uri.host == origin.host && uri.port == origin.port &&
            uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)
}
