package com.sjos.folionote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sjos.folionote.data.local.SourceType
import com.sjos.folionote.engine.document.DocumentExtractor
import com.sjos.folionote.engine.updater.AndroidApkSignatureVerifier
import kotlinx.coroutines.runBlocking
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun executeShellCommand(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test
    fun bundledLatinOcrRecognizesTextWithNetworkDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            // Disable every network transport before creating the recognizer. The production
            // dependency is com.google.mlkit:text-recognition (bundled model), not the Play
            // Services download-on-demand artifact.
            executeShellCommand("settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true; svc wifi disable; svc data disable")
            val offline = (0 until 50).any {
                if (connectivity.activeNetworkInfo?.isConnected != true) {
                    true
                } else {
                    SystemClock.sleep(100L)
                    false
                }
            }
            assertTrue(
                "The emulator still has an active network; this is not an offline OCR test",
                offline
            )

            val bitmap = Bitmap.createBitmap(1200, 360, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 120f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            canvas.drawText("FOLIONOTE", 60f, 230f, paint)

            var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
            try {
                val activeRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer = activeRecognizer
                val result = Tasks.await(
                    activeRecognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    60,
                    TimeUnit.SECONDS
                )
                assertTrue(
                    "Bundled OCR did not recognize the test image while offline: ${result.text}",
                    result.text.contains("FOLIO", ignoreCase = true)
                )

                // Exercise the production PDF extraction -> PdfRenderer -> bundled ML Kit path
                // while the same emulator remains offline, not just a mocked page renderer.
                val pngBytes = ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
                val scannedPdf = File(context.cacheDir, "offline-ocr-${System.nanoTime()}.pdf")
                try {
                    com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
                        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
                            com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4
                        )
                        document.addPage(page)
                        val image = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                            .createFromByteArray(document, pngBytes, "offline-ocr-fixture")
                        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use { stream ->
                            stream.drawImage(image, 40f, 500f, 500f, 150f)
                        }
                        document.save(scannedPdf)
                    }

                    val app = context.applicationContext as FolioNoteApp
                    val extractedPdf = runBlocking {
                        DocumentExtractor(ocrEngine = app.container.ocrEngine)
                            .extract(scannedPdf, SourceType.PDF)
                    }
                    assertTrue(
                        "Bundled OCR did not recognize scanned PDF text while offline: ${extractedPdf.fullText}",
                        extractedPdf.fullText.contains("FOLIO", ignoreCase = true)
                    )
                    assertEquals("true", extractedPdf.metadata["ocr"])
                    assertFalse(extractedPdf.requiresOcr)
                } finally {
                    scannedPdf.delete()
                }
            } finally {
                recognizer?.close()
                bitmap.recycle()
            }
        } finally {
            executeShellCommand("settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false; svc wifi enable; svc data enable")
        }
    }

    @Test
    fun installedApkSigningCertificateIsReadableOnTheDeviceApiLevel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fingerprints = AndroidApkSignatureVerifier(context).getInstalledSigningCertSha256List()

        assertTrue("The installed FolioNote APK must expose its signing certificate", fingerprints.isNotEmpty())
        assertTrue("The installed signing certificate must be SHA-256", fingerprints.all { it.length == 64 })
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
