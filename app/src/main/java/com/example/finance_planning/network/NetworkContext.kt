package com.example.finance_planning.network

import java.io.IOException

/** No credential or selected source belongs to a shared client configuration. */
data class DnseConfiguration(val production: Boolean) {
    val origin: String get() = if (production) "https://openapi.dnse.com.vn/" else "https://sb-openapi.dnse.com.vn/"
}

class SupersededNetworkContext : IOException("Request context is no longer current")
