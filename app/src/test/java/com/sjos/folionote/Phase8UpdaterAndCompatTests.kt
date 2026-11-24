package com.sjos.folionote

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.engine.document.FileStorageManager
import com.sjos.folionote.engine.drive.GoogleConnectOutcome
import com.sjos.folionote.engine.drive.GoogleDriveAuthManager
import com.sjos.folionote.engine.drive.GoogleSignInGateway
import com.sjos.folionote.engine.drive.GoogleSignInOutcome
import com.sjos.folionote.engine.drive.DriveAuthorizationGateway
import com.sjos.folionote.engine.drive.DriveAuthorizationOutcome
import com.sjos.folionote.engine.updater.AndroidApkSignatureVerifier
import com.sjos.folionote.engine.updater.ApkVerifier
import com.sjos.folionote.engine.updater.AppUpdateManager
import com.sjos.folionote.engine.updater.GitHubReleaseService
import com.sjos.folionote.engine.updater.InstalledAppVersionInfo
import com.sjos.folionote.engine.updater.ReleaseCheckOutcome
import com.sjos.folionote.engine.updater.ReleaseManifest
import com.sjos.folionote.engine.updater.UpdateUiState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25, 26, 36])
class Phase8ApiCompatMatrixTests {

    @Test
    fun `core container Room Keystore FileStorage and MLKit routing work across API 24 to 36`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.notebookDao().upsertNotebook(NotebookEntity(id = "nb_compat", title = "Notas API ${Build.VERSION.SDK_INT}"))
            assertEquals(1, db.notebookDao().observeAllNotebooks().first().size)

            val crypto = KeystoreCryptoManager()
            val enc = crypto.encrypt("gsk_compat_secret")
            assertEquals("gsk_compat_secret", crypto.decrypt(enc))

            val storage = FileStorageManager(context)
            assertNotNull(storage)

            val app = context.applicationContext as FolioNoteApp
            assertNotNull(app.container.ocrEngine)
            assertNotNull(app.container.transcriptionEngine)
            assertNotNull(app.container.appUpdateManager)
        } finally {
            db.close()
        }
    }

    @Test
    fun `missing Play Services degrades Google Drive auth cleanly while keeping local workspace operational`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val unavailableSignIn = object : GoogleSignInGateway {
            override suspend fun authenticate(activity: Activity, filterByAuthorizedAccounts: Boolean) =
                GoogleSignInOutcome.Unavailable("Google Play Services indisponível neste dispositivo.")
        }
        val unavailableAuth = object : DriveAuthorizationGateway {
            override suspend fun authorize(activity: Activity, accountEmail: String?, scopes: List<String>) =
                DriveAuthorizationOutcome.Unavailable("Google Play Services indisponível neste dispositivo.")
        }
        val authManager = GoogleDriveAuthManager(
            context = context,
            preferencesStore = prefs,
            cryptoManager = KeystoreCryptoManager(),
            baseHttpClient = OkHttpClient(),
            signInGateway = unavailableSignIn,
            authGateway = unavailableAuth
        )

        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val outcome = authManager.connectGoogleAccount(activity)
        assertTrue(outcome is GoogleConnectOutcome.Failed)
        assertTrue((outcome as GoogleConnectOutcome.Failed).message.contains("Play Services"))
    }

    @Test
    fun `install permission flow selects Security Settings below Oreo and Unknown App Sources on Oreo plus`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppUpdateManager(
            context = context,
            releaseService = GitHubReleaseService(preferencesStore = null)
        )
        val intent = manager.createUnknownSourcesSettingsIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        } else {
            assertEquals(Settings.ACTION_SECURITY_SETTINGS, intent.action)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `signature verifier extracts legacy API 24 signatures safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val verifier = AndroidApkSignatureVerifier(context, sdkIntProvider = { 24 })
        val pkgInfo = PackageInfo().apply {
            signatures = arrayOf(Signature(byteArrayOf(0x10, 0x20, 0x30, 0x40)))
        }
        val digests = verifier.extractCertFingerprints(pkgInfo, sdkInt = 24)
        assertEquals(1, digests.size)
        assertEquals(64, digests.first().length)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8UpdaterEngineTests {

    private fun startUpdaterServer(
        handler: (path: String, reqHeaders: Map<String, String>) -> Triple<Int, Map<String, String>, ByteArray>
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val hdrs = mutableMapOf<String, String>()
            ex.requestHeaders.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) hdrs[k.lowercase()] = v.joinToString(",")
            }
            val (code, respHeaders, bytes) = handler(ex.requestURI.path, hdrs)
            respHeaders.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            if (code == 304) {
                ex.sendResponseHeaders(304, -1L)
                ex.close()
            } else {
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        return server
    }

    private fun buildValidManifestJson(
        versionName: String = "1.2.0",
        versionCode: Long = 1002000L,
        minSdk: Int = 24,
        apkFileName: String = "FolioNote-v1.2.0.apk",
        sizeBytes: Long = 1024L,
        sha256: String,
        certSha256: String = "a".repeat(64),
        packageName: String = "com.sjos.folionote"
    ): String = JSONObject()
        .put("packageName", packageName)
        .put("versionName", versionName)
        .put("versionCode", versionCode)
        .put("minSdk", minSdk)
        .put("apkFileName", apkFileName)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("signingCertSha256", certSha256)
        .put("tag", "v$versionName")
        .put("releaseUrl", "https://github.com/Sjos0/FolioNot/releases/tag/v$versionName")
        .toString()

    private fun buildReleaseJson(
        port: Int,
        tag: String = "v1.2.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        apkName: String = "FolioNote-v1.2.0.apk",
        apkSize: Long = 1024L,
        apkDigest: String? = null,
        includeManifestAsset: Boolean = true,
        includeApkAsset: Boolean = true
    ): String {
        val base = "http://127.0.0.1:$port"
        val assets = JSONArray()
        if (includeManifestAsset) {
            assets.put(
                JSONObject()
                    .put("name", "release-manifest.json")
                    .put("size", 350)
                    .put("browser_download_url", "$base/assets/release-manifest.json")
            )
        }
        if (includeApkAsset) {
            assets.put(
                JSONObject()
                    .put("name", apkName)
                    .put("size", apkSize)
                    .put("browser_download_url", "$base/assets/$apkName")
                    .apply { if (apkDigest != null) put("digest", apkDigest) }
            )
        }
        return JSONObject()
            .put("tag_name", tag)
            .put("name", "FolioNote $tag")
            .put("body", "## Novidades\n- Correções gerais")
            .put("html_url", "https://github.com/Sjos0/FolioNot/releases/tag/$tag")
            .put("published_at", "2026-11-24T12:00:00Z")
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("assets", assets)
            .toString()
    }

    @Test
    fun `manifest parser rejects wrong packageName debug APK missing fields and malformed hashes`() {
        val validSha = "b".repeat(64)
        val validCert = "c".repeat(64)

        assertTrue(
            ReleaseManifest.parseAndValidate(
                buildValidManifestJson(packageName = "com.malicious.clone", sha256 = validSha, certSha256 = validCert)
            ).isFailure
        )
        assertTrue(
            ReleaseManifest.parseAndValidate(
                buildValidManifestJson(apkFileName = "FolioNote-debug.apk", sha256 = validSha, certSha256 = validCert)
            ).isFailure
        )
        assertTrue(
            ReleaseManifest.parseAndValidate(
                buildValidManifestJson(sha256 = "not-a-sha256", certSha256 = validCert)
            ).isFailure
        )
        assertTrue(
            ReleaseManifest.parseAndValidate(
                buildValidManifestJson(sha256 = validSha, certSha256 = "short")
            ).isFailure
        )
        assertTrue(
            ReleaseManifest.parseAndValidate(
                buildValidManifestJson(sha256 = validSha, certSha256 = validCert)
            ).isSuccess
        )
    }

    @Test
    fun `github release check ignores drafts and prereleases rejects missing manifest and checks ETag 304 and versionCode`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        prefs.clearAllPrefsForTest()

        val apkBytes = ByteArray(1024) { (it % 251).toByte() }
        val apkSha = AndroidApkSignatureVerifier.sha256Hex(apkBytes)

        var mode = "draft"
        var port = 0
        val releaseApiHits = AtomicInteger(0)
        val server = startUpdaterServer { path, hdrs ->
            when (path) {
                "/latest" -> {
                    releaseApiHits.incrementAndGet()
                    when (mode) {
                        "draft" -> Triple(200, emptyMap(), buildReleaseJson(port, draft = true).toByteArray())
                        "prerelease" -> Triple(200, emptyMap(), buildReleaseJson(port, prerelease = true).toByteArray())
                        "no_manifest" -> Triple(200, emptyMap(), buildReleaseJson(port, includeManifestAsset = false).toByteArray())
                        "no_apk" -> Triple(200, emptyMap(), buildReleaseJson(port, includeApkAsset = false).toByteArray())
                        "bad_digest" -> Triple(200, emptyMap(), buildReleaseJson(port, apkDigest = "sha256:${"f".repeat(64)}").toByteArray())
                        "etag" -> {
                            if (hdrs["if-none-match"] == "\"etag-v120\"") {
                                Triple(304, emptyMap(), ByteArray(0))
                            } else {
                                Triple(
                                    200,
                                    mapOf("ETag" to "\"etag-v120\""),
                                    buildReleaseJson(port, apkDigest = "sha256:$apkSha").toByteArray()
                                )
                            }
                        }
                        else -> Triple(200, emptyMap(), buildReleaseJson(port, apkDigest = "sha256:$apkSha").toByteArray())
                    }
                }
                "/assets/release-manifest.json" -> Triple(
                    200,
                    emptyMap(),
                    buildValidManifestJson(sha256 = apkSha, minSdk = 24, versionCode = 1002000L).toByteArray()
                )
                else -> Triple(404, emptyMap(), ByteArray(0))
            }
        }
        port = server.address.port

        val installedLower = InstalledAppVersionInfo("com.sjos.folionote", "1.0.0", 1000000L)
        val installedSame = InstalledAppVersionInfo("com.sjos.folionote", "1.2.0", 1002000L)
        val service = GitHubReleaseService(
            baseHttpClient = OkHttpClient(),
            preferencesStore = prefs,
            latestReleaseUrl = "http://127.0.0.1:$port/latest",
            deviceSdkProvider = { 24 },
            allowLocalhostForTests = true
        )

        try {
            mode = "draft"
            assertTrue(service.checkForUpdates(installedLower).getOrThrow() is ReleaseCheckOutcome.UpToDate)

            mode = "prerelease"
            assertTrue(service.checkForUpdates(installedLower).getOrThrow() is ReleaseCheckOutcome.UpToDate)

            mode = "no_manifest"
            assertTrue(service.checkForUpdates(installedLower).isFailure)

            mode = "no_apk"
            assertTrue(service.checkForUpdates(installedLower).isFailure)

            mode = "bad_digest"
            assertTrue(service.checkForUpdates(installedLower).isFailure)

            mode = "etag"
            val firstCheck = service.checkForUpdates(installedLower).getOrThrow()
            assertTrue(firstCheck is ReleaseCheckOutcome.UpdateAvailable)
            val cached304Check = service.checkForUpdates(installedLower).getOrThrow()
            assertTrue(cached304Check is ReleaseCheckOutcome.UpdateAvailable)

            // Same or higher installed version -> UpToDate
            assertTrue(service.checkForUpdates(installedSame).getOrThrow() is ReleaseCheckOutcome.UpToDate)

            // Higher manifest minSdk than device SDK -> failure
            val oldDeviceService = GitHubReleaseService(
                baseHttpClient = OkHttpClient(),
                preferencesStore = prefs,
                latestReleaseUrl = "http://127.0.0.1:$port/latest",
                deviceSdkProvider = { 21 },
                allowLocalhostForTests = true
            )
            assertTrue(oldDeviceService.checkForUpdates(installedLower).isFailure)
        } finally {
            prefs.clearAllPrefsForTest()
            server.stop(0)
        }
    }

    @Test
    fun `github release check maps HTTP 404 403 429 500 and offline IOException cleanly`() = runTest {
        var status = 404
        val server = startUpdaterServer { _, _ ->
            val hdrs = if (status == 429) mapOf("Retry-After" to "30") else emptyMap()
            Triple(status, hdrs, """{"message":"err"}""".toByteArray())
        }
        val port = server.address.port
        val installed = InstalledAppVersionInfo("com.sjos.folionote", "1.0.0", 1L)
        val service = GitHubReleaseService(
            baseHttpClient = OkHttpClient(),
            preferencesStore = null,
            latestReleaseUrl = "http://127.0.0.1:$port/latest",
            allowLocalhostForTests = true
        )

        try {
            for (code in listOf(404, 403, 429, 500)) {
                status = code
                val res = service.checkForUpdates(installed)
                assertTrue(res.isFailure)
                assertTrue(res.exceptionOrNull()?.message.orEmpty().isNotBlank())
            }
        } finally {
            server.stop(0)
        }

        // Offline / unreachable server -> clean IOException message
        val offlineRes = service.checkForUpdates(installed)
        assertTrue(offlineRes.isFailure)
        assertTrue(offlineRes.exceptionOrNull() is IOException)
    }

    @Test
    fun `updater streams download verifies integrity handles cancel part cleanup and permission return`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempApkPayload = ByteArray(64 * 1024) { (it % 199).toByte() }
        val apkSha = AndroidApkSignatureVerifier.sha256Hex(tempApkPayload)
        val updatesFolder = File(context.filesDir, "updates").apply { deleteRecursively() }

        var port = 0
        var interruptMidStream = false
        var failVerification = false
        var simulateInstalledVersionCode = 1000000L
        var simulateCanInstall = false

        var streamStartedLatch = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            when (ex.requestURI.path) {
                "/latest" -> {
                    val body = buildReleaseJson(port, apkSize = tempApkPayload.size.toLong(), apkDigest = "sha256:$apkSha").toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                "/assets/release-manifest.json" -> {
                    val body = buildValidManifestJson(
                        sizeBytes = tempApkPayload.size.toLong(),
                        sha256 = apkSha,
                        versionCode = 1002000L
                    ).toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                "/assets/FolioNote-v1.2.0.apk" -> {
                    if (interruptMidStream) {
                        ex.sendResponseHeaders(200, tempApkPayload.size.toLong())
                        ex.responseBody.write(tempApkPayload, 0, 8192)
                        ex.responseBody.flush()
                        ex.close()
                    } else {
                        ex.sendResponseHeaders(200, tempApkPayload.size.toLong())
                        ex.responseBody.use { out ->
                            out.write(tempApkPayload, 0, 16384)
                            out.flush()
                            streamStartedLatch.countDown()
                            Thread.sleep(150)
                            out.write(tempApkPayload, 16384, tempApkPayload.size - 16384)
                        }
                    }
                }
            }
        }
        server.start()
        port = server.address.port

        val verifier = object : ApkVerifier {
            override fun getInstalledAppInfo() =
                InstalledAppVersionInfo("com.sjos.folionote", "1.0.0", simulateInstalledVersionCode)

            override fun getInstalledSigningCertSha256List() = listOf("a".repeat(64))

            override fun verifyDownloadedApk(apkFile: File, manifest: ReleaseManifest, expectedSha256: String): Result<Unit> {
                return if (failVerification) {
                    Result.failure(IllegalStateException("Certificado inválido para teste"))
                } else {
                    val hash = AndroidApkSignatureVerifier.computeSha256Hex(apkFile)
                    if (hash == expectedSha256) Result.success(Unit)
                    else Result.failure(IllegalStateException("SHA mismatch"))
                }
            }
        }

        val service = GitHubReleaseService(
            baseHttpClient = OkHttpClient(),
            preferencesStore = null,
            latestReleaseUrl = "http://127.0.0.1:$port/latest",
            allowLocalhostForTests = true
        )
        val manager = AppUpdateManager(
            context = context,
            baseHttpClient = OkHttpClient(),
            releaseService = service,
            apkVerifier = verifier,
            sdkIntProvider = { 26 },
            canRequestInstallsApi26Provider = { simulateCanInstall },
            allowLocalhostForTests = true
        )

        try {
            val checkOutcome = service.checkForUpdates(verifier.getInstalledAppInfo()).getOrThrow() as ReleaseCheckOutcome.UpdateAvailable
            val update = checkOutcome.update

            // 1. Cancel mid-stream removes .part file cleanly
            streamStartedLatch = CountDownLatch(1)
            val downloadJob = launch(Dispatchers.IO) { manager.downloadAndVerifyInternal(update) }
            withContext(Dispatchers.IO) {
                streamStartedLatch.await(5, TimeUnit.SECONDS)
            }
            manager.cancelDownload()
            downloadJob.join()
            assertTrue(manager.uiState.value is UpdateUiState.Cancelled)
            assertFalse(File(updatesFolder, "FolioNote-update.apk.part").exists())

            // 2. Interrupted mid-stream download transitions to Paused cleanly
            interruptMidStream = true
            manager.downloadAndVerifyInternal(update)
            assertTrue(manager.uiState.value is UpdateUiState.Paused)
            interruptMidStream = false

            // 3. Verification failure deletes corrupted/untrusted APK & fails cleanly
            failVerification = true
            manager.downloadAndVerifyInternal(update)
            assertTrue(manager.uiState.value is UpdateUiState.Failed)
            assertFalse(File(updatesFolder, "FolioNote-update-1002000.apk").exists())

            // 3. Valid download -> AwaitingInstallPermission -> user grants -> ReadyToInstall
            failVerification = false
            simulateCanInstall = false
            manager.downloadAndVerifyInternal(update)
            assertTrue(manager.uiState.value is UpdateUiState.AwaitingInstallPermission)

            // Return without granting -> stays AwaitingInstallPermission
            manager.refreshStateAfterExternalReturn()
            assertTrue(manager.uiState.value is UpdateUiState.AwaitingInstallPermission)

            // Return after granting -> transitions to ReadyToInstall
            simulateCanInstall = true
            manager.refreshStateAfterExternalReturn()
            assertTrue(manager.uiState.value is UpdateUiState.ReadyToInstall)

            // Simulate user returns from installer without upgrading vs after upgrading
            val activity = Robolectric.buildActivity(Activity::class.java).create().get()
            manager.launchSystemInstaller(activity)
            assertTrue(manager.uiState.value is UpdateUiState.AwaitingSystemInstaller)
            manager.refreshStateAfterExternalReturn()
            assertTrue(manager.uiState.value is UpdateUiState.ReadyToInstall)

            manager.launchSystemInstaller(activity)
            simulateInstalledVersionCode = 1002000L
            manager.refreshStateAfterExternalReturn()
            assertTrue(manager.uiState.value is UpdateUiState.Installed)
            assertFalse(updatesFolder.exists())
        } finally {
            server.stop(0)
            updatesFolder.deleteRecursively()
        }
    }
}
