package com.example.finance_planning.core

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

/** Android resources for text emitted outside Compose (workers, errors and notifications). */
interface TextResources {
    fun get(@StringRes id: Int, vararg args: Any): String
    val locale: Locale
}

object AppText {
    private lateinit var resources: TextResources
    fun initialize(context: Context) {
        val app = context.applicationContext
        initialize(object : TextResources {
            override fun get(id: Int, vararg args: Any): String = app.getString(id, *args)
            override val locale: Locale
                get() {
                    val locales = app.resources.configuration.locales
                    return (0 until locales.size()).map { locales[it] }
                        .firstOrNull { it.language in setOf("vi", "en") } ?: Locale.forLanguageTag("vi")
                }
        })
    }
    fun initialize(provider: TextResources) { resources = provider }
    fun get(@StringRes id: Int, vararg args: Any): String = resources.get(id, *args)
    val locale: Locale get() = resources.locale
}
