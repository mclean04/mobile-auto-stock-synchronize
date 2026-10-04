package com.example.finance_planning.core

object DnseCredentialFormat {
    fun valid(key: String, secret: String): Boolean =
        listOf(key, secret).all { it.isNotBlank() && it.none { c -> c.isWhitespace() || c.code < 33 || c.code > 126 } }
}
