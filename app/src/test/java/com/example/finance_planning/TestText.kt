package com.example.finance_planning

import com.example.finance_planning.core.AppText
import com.example.finance_planning.core.TextResources
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Uses the actual translation files so JVM tests do not duplicate production text. */
object TestText {
    fun strings(language: String): Map<String, String> {
        val qualifier = if (language == "en") "values-en" else "values"
        val file = listOf(File("src/main/res/$qualifier/strings.xml"), File("app/src/main/res/$qualifier/strings.xml"))
            .first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.removeSurrounding("\"")
                .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }
    fun install(language: String = "vi") {
        val values = strings(language)
        val ids = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        AppText.initialize(object : TextResources {
            override val locale = Locale.forLanguageTag(language)
            override fun get(id: Int, vararg args: Any): String = String.format(locale, values.getValue(ids.getValue(id)), *args)
        })
    }
}
