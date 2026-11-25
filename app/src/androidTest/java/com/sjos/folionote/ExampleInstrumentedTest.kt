package com.sjos.folionote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun applicationContextAndCoreContainerInitialize() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
        assertEquals("com.sjos.folionote", appContext.packageName)

        val app = appContext.applicationContext as FolioNoteApp
        assertNotNull(app.container.ocrEngine)
        assertNotNull(app.container.transcriptionEngine)
        assertNotNull(app.container.appUpdateManager)
        assertTrue(app.container.appUpdateManager.getInstalledVersionInfo().versionCode >= 1L)
    }

    @Test
    fun mainActivityLaunchesOnApi24() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertEquals("com.sjos.folionote", activity.packageName)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun bundledLatinOcrRecognizesTextWithoutNetwork() {
        val bitmap = Bitmap.createBitmap(1200, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 120f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        canvas.drawText("FOLIONOTE", 60f, 230f, paint)

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                60,
                TimeUnit.SECONDS
            )
            assertTrue("Bundled OCR did not recognize the test text: ${result.text}", result.text.contains("FOLIO", ignoreCase = true))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    @Test
    fun updateApkFileProviderReturnsContentUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        val fakeApk = File(updatesDir, "api24-smoke.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fakeApk
            )
            assertEquals("content", uri.scheme)
            assertTrue(uri.path.orEmpty().contains("updates"))
        } finally {
            fakeApk.delete()
            updatesDir.delete()
        }
    }
}
