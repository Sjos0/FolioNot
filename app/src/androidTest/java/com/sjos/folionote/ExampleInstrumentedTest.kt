package com.sjos.folionote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContextAndVerifyCoreContainer() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
        assertEquals("com.sjos.folionote", appContext.packageName)

        val app = appContext.applicationContext as FolioNoteApp
        assertNotNull(app.container.ocrEngine)
        assertNotNull(app.container.transcriptionEngine)
        assertNotNull(app.container.appUpdateManager)
        assertTrue(app.container.appUpdateManager.getInstalledVersionInfo().versionCode >= 1L)
    }
}
