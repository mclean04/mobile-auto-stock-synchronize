package com.example.finance_planning.core

import org.json.JSONObject

/** Stored through Vault; callers never move credentials between environments. */
class DnseCredentialStore(private val read: (String) -> String?, private val write: (String, String) -> Unit,
                          private val remove: (String) -> Unit) {
    private fun slot(uid: String, production: Boolean) = "dnse:$uid:" + if (production) "production" else "sandbox"
    private fun migrate(uid: String) {
        val legacy = read("dnse:$uid") ?: return
        val production = JSONObject(legacy).getBoolean("production")
        if (read(slot(uid, production)) == null) write(slot(uid, production), legacy)
        if (read("dnse_environment:$uid") == null) write("dnse_environment:$uid", production.toString())
        remove("dnse:$uid")
    }
    fun production(uid: String): Boolean { migrate(uid); return read("dnse_environment:$uid") == "true" }
    fun config(uid: String): String? { migrate(uid); return read(slot(uid, production(uid))) }
    fun config(uid: String, production: Boolean): String? { migrate(uid); return read(slot(uid, production)) }
    fun has(uid: String, production: Boolean): Boolean { migrate(uid); return read(slot(uid, production)) != null }
    fun select(uid: String, production: Boolean) { migrate(uid); write("dnse_environment:$uid", production.toString()) }
    fun save(uid: String, key: String, secret: String, production: Boolean, unit: String) {
        require(DnseCredentialFormat.valid(key.trim(), secret.trim()) && unit in setOf("1", "1000")) { "Invalid DNSE credential format" }
        migrate(uid)
        write(slot(uid, production), JSONObject().put("key", key.trim()).put("secret", secret.trim())
            .put("production", production).put("vndPerUnit", unit).toString())
        select(uid, production)
    }
    fun delete(uid: String, production: Boolean) { migrate(uid); remove(slot(uid, production)) }
    fun clear(uid: String) {
        remove("dnse:$uid"); remove(slot(uid, true)); remove(slot(uid, false)); remove("dnse_environment:$uid")
    }
}
