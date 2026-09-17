package com.example.finance_planning

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageResourcesTest {
    private fun language(tag: String) = InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tag))
        })
    }
    @Test fun androidSelectsVietnameseDefaultEnglishTranslationAndVietnameseFallback() {
        assertEquals("Lệnh", language("vi").getString(R.string.orders))
        assertEquals("Orders", language("en-US").getString(R.string.orders))
        assertEquals("Lệnh", language("fr-FR").getString(R.string.orders))
        assertEquals("Đã gửi 12 bản ghi • 17/09/2026", language("vi").getString(R.string.uploaded_records, 12, "17/09/2026"))
        assertEquals("Uploaded 12 records • 17 Sep 2026", language("en").getString(R.string.uploaded_records, 12, "17 Sep 2026"))
    }
}
