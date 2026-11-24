package com.sjos.folionote

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.SourceStatus
import com.sjos.folionote.data.repository.NotebookRepository
import com.sjos.folionote.engine.document.FileStorageManager
import com.sjos.folionote.engine.drive.CredentialAuthResult
import com.sjos.folionote.engine.drive.DriveAuthException
import com.sjos.folionote.engine.drive.DriveAuthResult
import com.sjos.folionote.engine.drive.DriveAuthorizationGateway
import com.sjos.folionote.engine.drive.DriveFileItem
import com.sjos.folionote.engine.drive.DriveHttpException
import com.sjos.folionote.engine.drive.DriveSyncWorker
import com.sjos.folionote.engine.drive.GoogleAccountProfile
import com.sjos.folionote.engine.drive.GoogleConnectOutcome
import com.sjos.folionote.engine.drive.GoogleCredentialAuthenticator
import com.sjos.folionote.engine.drive.GoogleDriveApiClient
import com.sjos.folionote.engine.drive.GoogleDriveAuthManager
import com.sjos.folionote.engine.retrieval.HybridRetriever
import com.sjos.folionote.engine.worker.SourcePipelineManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase7DriveAuthAndImportTests {

    private class FakeOAuthAndDriveServer {
        var userEmail = "user@folionote.app"
        var userName = "FolioNote User"
        val revokedTokens = CopyOnWriteArrayList<String>()
        val recordedRequests = CopyOnWriteArrayList<String>()
        var failDriveRequests = false

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.createContext("/") { exchange ->
                val method = exchange.requestMethod
                val path = exchange.requestURI.path
                val rawQuery = exchange.requestURI.rawQuery.orEmpty()
                recordedRequests.add("$method $path?$rawQuery")
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

                fun respond(code: Int, contentType: String, bytes: ByteArray) {
                    exchange.responseHeaders.add("Content-Type", contentType)
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }

                when {
                    path == "/oauth2/revoke" && method == "POST" -> {
                        revokedTokens.add(body)
                        respond(200, "application/json", "{}".toByteArray())
                    }

                    path == "/userinfo" && method == "GET" -> {
                        val json = JSONObject()
                            .put("email", userEmail)
                            .put("name", userName)
                            .put("picture", "https://example.com/avatar.png")
                        respond(200, "application/json", json.toString().toByteArray())
                    }

                    path == "/drive/v3/files" && method == "GET" -> {
                        if (failDriveRequests) {
                            respond(503, "application/json", """{"error":{"message":"Service unavailable"}}""".toByteArray())
                            return@createContext
                        }
                        val q = rawQuery.split("&")
                            .firstOrNull { it.startsWith("q=") }
                            ?.substringAfter("q=")
                            ?.let { URLDecoder.decode(it, "UTF-8") }
                            .orEmpty()

                        val allFiles = listOf(
                            JSONObject()
                                .put("id", "gdoc_1")
                                .put("name", "Plano de Pesquisa")
                                .put("mimeType", "application/vnd.google-apps.document")
                                .put("size", "0")
                                .put("modifiedTime", "2026-11-24T10:00:00Z")
                                .put("webViewLink", "https://docs.google.com/document/d/gdoc_1/edit"),
                            JSONObject()
                                .put("id", "txt_2")
                                .put("name", "Notas_Campo.txt")
                                .put("mimeType", "text/plain")
                                .put("size", "128")
                                .put("modifiedTime", "2026-11-24T11:00:00Z")
                                .put("webViewLink", "https://drive.google.com/file/d/txt_2/view")
                        )

                        val filtered = if (q.contains("name contains '")) {
                            val term = q.substringAfter("name contains '").substringBefore("'")
                            allFiles.filter { it.getString("name").contains(term, ignoreCase = true) }
                        } else {
                            allFiles
                        }
                        val arr = JSONArray().apply { filtered.forEach { put(it) } }
                        respond(200, "application/json", JSONObject().put("files", arr).toString().toByteArray())
                    }

                    path == "/drive/v3/files/gdoc_1/export" && method == "GET" -> {
                        if (failDriveRequests) {
                            respond(503, "application/json", """{"error":{"message":"Export failed"}}""".toByteArray())
                            return@createContext
                        }
                        respond(200, "application/pdf", "%PDF-1.4\n%fake-exported-doc".toByteArray())
                    }

                    path == "/drive/v3/files/txt_2" && method == "GET" && rawQuery.contains("alt=media") -> {
                        if (failDriveRequests) {
                            respond(503, "application/json", """{"error":{"message":"Download failed"}}""".toByteArray())
                            return@createContext
                        }
                        respond(200, "text/plain", "Conteudo de pesquisa vindo do Google Drive.".toByteArray())
                    }

                    else -> respond(404, "application/json", """{"error":{"message":"Not found"}}""".toByteArray())
                }
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private class FakeCredentialAuthenticator : GoogleCredentialAuthenticator {
        var nextResult: CredentialAuthResult = CredentialAuthResult.Authenticated(
            profile = GoogleAccountProfile("user@folionote.app", "FolioNote User"),
            idToken = "id_token_abc"
        )
        var clearCalls = 0
        var lastServerClientId: String? = null

        override suspend fun authenticate(activity: Activity, serverClientId: String): CredentialAuthResult {
            lastServerClientId = serverClientId
            return nextResult
        }

        override suspend fun clearSession() {
            clearCalls++
        }
    }

    private class FakeAuthorizationGateway : DriveAuthorizationGateway {
        var nextAuthorizeResult: DriveAuthResult = DriveAuthResult.Authorized("ya29.silent_ok", 3600L)
        var nextIntentResult: DriveAuthResult = DriveAuthResult.Authorized("ya29.consent_ok", 3600L)
        var authorizeCalls = 0
        var lastScopes: List<String> = emptyList()
        var lastEmail: String? = null
        var revokeCalls = 0

        override suspend fun authorize(
            context: Context,
            scopes: List<String>,
            accountEmail: String?
        ): DriveAuthResult {
            authorizeCalls++
            lastScopes = scopes
            lastEmail = accountEmail
            return nextAuthorizeResult
        }

        override fun extractAuthorizationFromIntent(context: Context, data: Intent?): DriveAuthResult {
            return nextIntentResult
        }

        override suspend fun revokeAccess(context: Context, scopes: List<String>, accountEmail: String?) {
            revokeCalls++
        }
    }

    private fun dummyIntentSender(context: Context): IntentSenderRequest {
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent("com.sjos.folionote.TEST_CONSENT"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return IntentSenderRequest.Builder(pi.intentSender).build()
    }

    private fun ensureWorkManager(context: Context) {
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    @Test
    fun `1_valid_token_is_returned_without_unnecessary_renewal_calls`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer()
        val gateway = FakeAuthorizationGateway()
        var now = 1_000_000L

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "FolioNote User",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.still_valid"),
                expiresAt = now + 30 * 60_000L
            )

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                authorizationGateway = gateway,
                nowProvider = { now }
            )

            val token = manager.getValidAccessToken().getOrThrow()
            assertEquals("ya29.still_valid", token)
            assertEquals(0, gateway.authorizeCalls)
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `2_expired_or_corrupted_token_is_never_returned_and_triggers_renewal`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer()
        val gateway = FakeAuthorizationGateway()
        var now = 1_000_000L

        try {
            prefs.clearDriveAccountSession()
            // Token expires within skew window -> MUST NOT be returned
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "User",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.expired_secret"),
                expiresAt = now + 10_000L
            )

            gateway.nextAuthorizeResult = DriveAuthResult.NeedsResolution(dummyIntentSender(context))
            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                authorizationGateway = gateway,
                nowProvider = { now }
            )

            val expiredRes = manager.getValidAccessToken()
            assertTrue(expiredRes.isFailure)
            assertTrue(expiredRes.exceptionOrNull() is DriveAuthException.ReauthRequired)

            // Corrupted/undecryptable token even with future expiry must also never be returned
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "User",
                photoUrl = null,
                encryptedAccessToken = "enc_v1:corrupted_base64_payload",
                expiresAt = now + 3_600_000L
            )
            gateway.nextAuthorizeResult = DriveAuthResult.Authorized("ya29.recovered_after_corrupt", 3600L)
            val recovered = manager.getValidAccessToken().getOrThrow()
            assertEquals("ya29.recovered_after_corrupt", recovered)
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `3_expired_token_uses_silent_authorization_client_renewal_without_legacy_oauth_token_fallback`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer()
        val gateway = FakeAuthorizationGateway().apply {
            nextAuthorizeResult = DriveAuthResult.Authorized("ya29.new_silent_token", 3600L)
        }
        val now = 2_000_000L

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "User",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.old_expired"),
                expiresAt = now - 5_000L
            )

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                authorizationGateway = gateway,
                nowProvider = { now }
            )

            val token = manager.getValidAccessToken().getOrThrow()
            assertEquals("ya29.new_silent_token", token)
            assertEquals(1, gateway.authorizeCalls)
            assertEquals("user@folionote.app", gateway.lastEmail)
            assertTrue(server.recordedRequests.none { it.contains("/oauth2/token") })

            val updatedSettings = prefs.settingsFlow.first()
            assertEquals("ya29.new_silent_token", crypto.decrypt(updatedSettings.driveEncryptedAccessToken))
            assertFalse(updatedSettings.driveNeedsReauth)
            assertTrue(updatedSettings.driveTokenExpiresAt > now + 3_000_000L)
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `4_needs_resolution_flows_into_interactive_consent_and_updates_access_token`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer()
        val fakeAuth = FakeCredentialAuthenticator()
        val gateway = FakeAuthorizationGateway().apply {
            nextAuthorizeResult = DriveAuthResult.NeedsResolution(dummyIntentSender(context))
            nextIntentResult = DriveAuthResult.Authorized("ya29.interactive_consented", 3600L)
        }
        val now = 3_000_000L

        try {
            prefs.clearDriveAccountSession()

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                credentialAuthenticator = fakeAuth,
                authorizationGateway = gateway,
                serverClientIdProvider = { "web-client.apps.googleusercontent.com" },
                nowProvider = { now }
            )

            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val connectOutcome = manager.connectGoogleAccount(activity)
            assertTrue(connectOutcome is GoogleConnectOutcome.NeedsConsentResolution)
            assertEquals("web-client.apps.googleusercontent.com", fakeAuth.lastServerClientId)

            val completedProfile = manager.completeAuthorizationResult(activity, Intent()).getOrThrow()
            assertEquals("user@folionote.app", completedProfile.email)
            assertTrue(server.recordedRequests.none { it.contains("/oauth2/token") })

            val saved = prefs.settingsFlow.first()
            assertTrue(saved.isGoogleDriveConnected)
            assertFalse(saved.driveNeedsReauth)
            assertEquals("ya29.interactive_consented", crypto.decrypt(saved.driveEncryptedAccessToken))
            assertEquals("ya29.interactive_consented", manager.getValidAccessToken().getOrThrow())
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `5_renewal_failure_sets_reauth_required_flag_without_losing_account_context`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer()
        val gateway = FakeAuthorizationGateway().apply {
            nextAuthorizeResult = DriveAuthResult.NeedsResolution(dummyIntentSender(context))
        }
        val now = 4_000_000L

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "User",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.expired"),
                expiresAt = now - 1000L
            )

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                authorizationGateway = gateway,
                nowProvider = { now }
            )

            val res = manager.getValidAccessToken()
            assertTrue(res.isFailure)
            assertTrue(res.exceptionOrNull() is DriveAuthException.ReauthRequired)
            assertNotNull((res.exceptionOrNull() as DriveAuthException.ReauthRequired).resolutionIntent)
            assertTrue(server.recordedRequests.none { it.contains("/oauth2/token") })

            val settings = prefs.settingsFlow.first()
            assertTrue(settings.driveNeedsReauth)
            assertTrue(settings.isGoogleDriveConnected) // preserves account context for one-tap reconnect
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `6_credentials_are_preserved_across_profile_refresh_and_replaced_on_account_switch`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer().apply {
            userEmail = "primary@folionote.app"
            userName = "Primary Updated"
        }
        val gateway = FakeAuthorizationGateway().apply {
            nextAuthorizeResult = DriveAuthResult.Authorized("ya29.renewed_during_profile", 3600L)
        }
        val fakeAuth = FakeCredentialAuthenticator()
        val now = 5_000_000L

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession(
                email = "primary@folionote.app",
                displayName = "Primary",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.expired_before_profile"),
                expiresAt = now - 5_000L
            )

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                credentialAuthenticator = fakeAuth,
                authorizationGateway = gateway,
                serverClientIdProvider = { "web-client.apps.googleusercontent.com" },
                nowProvider = { now }
            )

            val profile = manager.refreshProfile().getOrThrow()
            assertEquals("Primary Updated", profile.displayName)

            val afterProfile = prefs.settingsFlow.first()
            assertEquals("ya29.renewed_during_profile", crypto.decrypt(afterProfile.driveEncryptedAccessToken))

            // Switch to another Google account -> Credential Manager session cleared & new account replaces old credentials
            fakeAuth.nextResult = CredentialAuthResult.Authenticated(
                GoogleAccountProfile("secondary@folionote.app", "Secondary"),
                "id_token_sec"
            )
            server.userEmail = "secondary@folionote.app"
            server.userName = "Secondary"
            gateway.nextAuthorizeResult = DriveAuthResult.Authorized("ya29.secondary_token", 3600L)

            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val switchOutcome = manager.connectGoogleAccount(activity, switchAccount = true)
            assertTrue(switchOutcome is GoogleConnectOutcome.Connected)
            assertEquals(1, fakeAuth.clearCalls)

            val afterSwitch = prefs.settingsFlow.first()
            assertEquals("secondary@folionote.app", afterSwitch.driveAccountEmail)
            assertEquals("ya29.secondary_token", crypto.decrypt(afterSwitch.driveEncryptedAccessToken))
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `7_disconnect_and_revoke_clears_tokens_gateway_and_credential_manager`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer()
        val fakeAuth = FakeCredentialAuthenticator()
        val gateway = FakeAuthorizationGateway()

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "User",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.to_revoke"),
                expiresAt = System.currentTimeMillis() + 3600_000L
            )

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                credentialAuthenticator = fakeAuth,
                authorizationGateway = gateway
            )

            manager.disconnectAndRevoke()
            assertEquals(1, gateway.revokeCalls)
            assertEquals(1, fakeAuth.clearCalls)
            assertTrue(server.revokedTokens.any { it.contains("ya29.to_revoke") })

            val cleared = prefs.settingsFlow.first()
            assertFalse(cleared.isGoogleDriveConnected)
            assertNull(cleared.driveAccountEmail)
            assertEquals("", cleared.driveEncryptedAccessToken)
            assertFalse(cleared.driveNeedsReauth)
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `8_network_failure_during_renewal_preserves_session_without_forcing_reauth`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val gateway = FakeAuthorizationGateway().apply {
            nextAuthorizeResult = DriveAuthResult.NetworkError(IOException("Offline"))
        }
        val now = 6_000_000L

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "User",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.expired"),
                expiresAt = now - 10_000L
            )

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                authorizationGateway = gateway,
                nowProvider = { now }
            )

            val res = manager.getValidAccessToken()
            assertTrue(res.isFailure)
            assertTrue(res.exceptionOrNull() is IOException)

            val settings = prefs.settingsFlow.first()
            assertFalse(settings.driveNeedsReauth)
            assertTrue(settings.isGoogleDriveConnected)
        } finally {
            prefs.clearDriveAccountSession()
        }
    }

    @Test
    fun `9_workmanager_pauses_on_reauth_retries_on_network_and_recovers_after_reauth`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureWorkManager(context)
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val server = FakeOAuthAndDriveServer()
        val gateway = FakeAuthorizationGateway()
        val now = 7_000_000L

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession(
                email = "user@folionote.app",
                displayName = "User",
                photoUrl = null,
                encryptedAccessToken = crypto.encrypt("ya29.expired"),
                expiresAt = now - 10_000L
            )

            val manager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:${server.port}/oauth2",
                userInfoUrl = "http://127.0.0.1:${server.port}/userinfo",
                authorizationGateway = gateway,
                nowProvider = { now }
            )

            // Network error -> WorkManager retries up to attempt limit
            val ioRetry = DriveSyncWorker.classifyWorkResult(Result.failure<Unit>(IOException("Timeout")), attemptCount = 1)
            assertEquals(ListenableWorker.Result.retry(), ioRetry)
            val ioExhausted = DriveSyncWorker.classifyWorkResult(Result.failure<Unit>(IOException("Timeout")), attemptCount = 4)
            assertEquals(ListenableWorker.Result.failure(), ioExhausted)

            // Reauth required / HTTP 401 -> WorkManager stops immediately (no retry loop!)
            val authFail = DriveSyncWorker.classifyWorkResult(
                Result.failure<Unit>(DriveAuthException.ReauthRequired("Revoked")),
                attemptCount = 0
            )
            assertEquals(ListenableWorker.Result.failure(), authFail)
            val http401Fail = DriveSyncWorker.classifyWorkResult(
                Result.failure<Unit>(DriveHttpException(401, "Unauthorized")),
                attemptCount = 0
            )
            assertEquals(ListenableWorker.Result.failure(), http401Fail)

            // Trigger reauth state via failed silent renewal -> precondition halts background worker immediately
            gateway.nextAuthorizeResult = DriveAuthResult.NeedsResolution(dummyIntentSender(context))
            assertTrue(manager.getValidAccessToken().isFailure)
            val pausedSettings = prefs.settingsFlow.first()
            assertTrue(pausedSettings.driveNeedsReauth)
            assertEquals(ListenableWorker.Result.failure(), DriveSyncWorker.evaluatePrecondition(pausedSettings))

            // Complete interactive consent -> driveNeedsReauth resets & background worker can run again
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            gateway.nextIntentResult = DriveAuthResult.Authorized("ya29.reauthorized_after_consent", 3600L)
            val reconnected = manager.completeAuthorizationResult(activity, Intent())
            assertTrue(reconnected.isSuccess)
            val resumedSettings = prefs.settingsFlow.first()
            assertFalse(resumedSettings.driveNeedsReauth)
            assertNull(DriveSyncWorker.evaluatePrecondition(resumedSettings))
            assertEquals("ya29.reauthorized_after_consent", manager.getValidAccessToken().getOrThrow())
            assertEquals(ListenableWorker.Result.success(), DriveSyncWorker.classifyWorkResult(Result.success(Unit), attemptCount = 0))
        } finally {
            prefs.clearDriveAccountSession()
            server.stop()
        }
    }

    @Test
    fun `10_google_drive_file_import_lists_exports_preserves_metadata_and_handles_cancel_and_network_error`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ensureWorkManager(context)
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val fileStorage = FileStorageManager(context)
        val server = FakeOAuthAndDriveServer()
        val gateway = FakeAuthorizationGateway().apply {
            nextAuthorizeResult = DriveAuthResult.Authorized("ya29.drive_readonly_token", 3600L)
        }
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()

        try {
            db.notebookDao().upsertNotebook(NotebookEntity("nb_drive_import", "Pesquisa Drive"))

            val port = server.port
            val apiClient = GoogleDriveApiClient(
                baseHttpClient = OkHttpClient(),
                driveApiBaseUrl = "http://127.0.0.1:$port/drive/v3",
                driveUploadBaseUrl = "http://127.0.0.1:$port/upload/drive/v3"
            )
            val authManager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                authorizationGateway = gateway
            )

            val repo = NotebookRepository(
                db = db,
                fileStorageManager = fileStorage,
                pipelineManager = SourcePipelineManager(context, db),
                retriever = HybridRetriever()
            )

            // 1. Incremental drive.readonly authorization is separate from appDataFolder backups
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val importAuth = authManager.authorizeDriveFileImport(activity)
            assertTrue(importAuth is DriveAuthResult.Authorized)
            assertEquals(listOf(GoogleDriveAuthManager.SCOPE_DRIVE_READONLY), gateway.lastScopes)

            // 2. List Drive files & filter query
            val allDriveFiles = apiClient.listUserDriveFiles("ya29.drive_readonly_token").getOrThrow()
            assertEquals(2, allDriveFiles.size)
            val filtered = apiClient.listUserDriveFiles("ya29.drive_readonly_token", nameQuery = "Notas").getOrThrow()
            assertEquals(1, filtered.size)
            assertEquals("txt_2", filtered.first().id)

            // 3. Import binary TXT + Google Docs export -> local storage + Room + Drive origin metadata + ProcessingJob
            val txtItem = allDriveFiles.first { it.id == "txt_2" }
            val importedTxt = repo.importDriveFile(
                notebookId = "nb_drive_import",
                accessToken = "ya29.drive_readonly_token",
                item = txtItem,
                apiClient = apiClient
            ).getOrThrow()

            assertTrue(File(importedTxt.filePath).exists())
            assertEquals("Conteudo de pesquisa vindo do Google Drive.", File(importedTxt.filePath).readText())
            assertEquals(SourceStatus.PENDING.name, importedTxt.processingStatus)
            val txtMeta = JSONObject(importedTxt.metadataJson)
            assertEquals("google_drive", txtMeta.getString("origin"))
            assertEquals("txt_2", txtMeta.getString("drive_file_id"))
            assertEquals("https://drive.google.com/file/d/txt_2/view", txtMeta.getString("drive_web_view_link"))

            val gdocItem = allDriveFiles.first { it.id == "gdoc_1" }
            val importedDoc = repo.importDriveFile(
                notebookId = "nb_drive_import",
                accessToken = "ya29.drive_readonly_token",
                item = gdocItem,
                apiClient = apiClient
            ).getOrThrow()
            assertEquals("Plano de Pesquisa.pdf", importedDoc.name)
            assertEquals("application/pdf", importedDoc.mimeType)
            assertTrue(File(importedDoc.filePath).readBytes().isNotEmpty())
            val docMeta = JSONObject(importedDoc.metadataJson)
            assertEquals("google_drive", docMeta.getString("origin"))
            assertEquals("application/vnd.google-apps.document", docMeta.getString("drive_mime_type"))

            // 4. SAF Google Drive URI detection preserves Drive origin too
            assertTrue(fileStorage.isGoogleDriveUri(Uri.parse("content://com.google.android.apps.docs.storage/document/acc=1")))
            assertFalse(fileStorage.isGoogleDriveUri(Uri.parse("content://com.android.externalstorage.documents/document/primary:a.pdf")))

            // 5. Cancellation before download leaves database clean
            val countBeforeCancel = db.sourceDao().getSourcesForNotebookOnce("nb_drive_import").size
            val cancelledRes = repo.importDriveFile(
                notebookId = "nb_drive_import",
                accessToken = "ya29.drive_readonly_token",
                item = txtItem,
                apiClient = apiClient,
                isCancelled = { true }
            )
            assertTrue(cancelledRes.isFailure)
            assertTrue(cancelledRes.exceptionOrNull() is CancellationException)
            assertEquals(countBeforeCancel, db.sourceDao().getSourcesForNotebookOnce("nb_drive_import").size)

            // 6. Network/HTTP failure during Drive download returns failure cleanly without corrupting sources
            server.failDriveRequests = true
            val failedRes = repo.importDriveFile(
                notebookId = "nb_drive_import",
                accessToken = "ya29.drive_readonly_token",
                item = DriveFileItem("txt_2", "Notas_Campo.txt", 128L, "", 0L, mimeType = "text/plain"),
                apiClient = apiClient
            )
            assertTrue(failedRes.isFailure)
            assertEquals(countBeforeCancel, db.sourceDao().getSourcesForNotebookOnce("nb_drive_import").size)
        } finally {
            fileStorage.deleteNotebookDirectory("nb_drive_import")
            db.close()
            server.stop()
        }
    }
}
