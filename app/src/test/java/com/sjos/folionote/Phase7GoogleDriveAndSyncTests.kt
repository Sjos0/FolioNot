package com.sjos.folionote

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.DriveConflictPolicy
import com.sjos.folionote.core.preferences.OcrProviderType
import com.sjos.folionote.core.preferences.TranscriptionProviderType
import com.sjos.folionote.core.security.KeystoreCryptoManager
import com.sjos.folionote.data.local.CollectionEntity
import com.sjos.folionote.data.local.ConversationEntity
import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.FolioNoteDatabase
import com.sjos.folionote.data.local.MessageEntity
import com.sjos.folionote.data.local.NotebookEntity
import com.sjos.folionote.data.local.ProviderEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.StudioArtifactEntity
import com.sjos.folionote.data.local.SyncRecordEntity
import com.sjos.folionote.data.local.SyncTombstoneEntity
import com.sjos.folionote.engine.document.FileStorageManager
import com.sjos.folionote.engine.drive.BackupArchiveManager
import com.sjos.folionote.engine.drive.ConflictResolutionChoice
import com.sjos.folionote.engine.drive.DriveSyncEngine
import com.sjos.folionote.engine.drive.GoogleDriveApiClient
import com.sjos.folionote.engine.drive.GoogleDriveAuthManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase7GoogleDriveAndSyncTests {

    private class FakeDriveServer {
        data class StoredDriveFile(
            var id: String,
            var name: String,
            var bytes: ByteArray,
            var modifiedTime: String = "2026-11-24T10:00:00Z",
            var appProperties: Map<String, String> = emptyMap()
        )

        data class UploadSession(
            val sessionId: String,
            val targetFileId: String?,
            val fileName: String,
            val totalBytes: Long,
            val appProperties: Map<String, String>,
            val receivedChunks: ByteArrayOutputStream = ByteArrayOutputStream(),
            var bytesReceived: Long = 0L
        )

        val files = ConcurrentHashMap<String, StoredDriveFile>()
        val sessions = ConcurrentHashMap<String, UploadSession>()
        val recordedPaths = CopyOnWriteArrayList<String>()
        var revokedTokens = CopyOnWriteArrayList<String>()
        var profilePictureToReturn: String? = "https://lh3.googleusercontent.com/avatar-folionote.png"
        var failUploadOnceAfterFirstChunk = false
        var failDownloadOnceAtMidpoint = false

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.createContext("/") { exchange ->
                val method = exchange.requestMethod
                val path = exchange.requestURI.path
                val rawQuery = exchange.requestURI.rawQuery.orEmpty()
                recordedPaths.add("$method $path")
                val bodyBytes = exchange.requestBody.readBytes()

                fun respondBytes(code: Int, contentType: String, bytes: ByteArray, extraHeaders: Map<String, String> = emptyMap()) {
                    exchange.responseHeaders.add("Content-Type", contentType)
                    extraHeaders.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
                    if (code == 204 || code == 308) {
                        exchange.sendResponseHeaders(code, -1)
                        exchange.responseBody.close()
                    } else {
                        exchange.sendResponseHeaders(code, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }

                fun respondJson(code: Int, json: String, extraHeaders: Map<String, String> = emptyMap()) {
                    respondBytes(code, "application/json", json.toByteArray(Charsets.UTF_8), extraHeaders)
                }

                when {
                    // OAuth token exchange & refresh
                    path == "/oauth2/token" && method == "POST" -> {
                        val form = bodyBytes.toString(Charsets.UTF_8)
                        val json = if (form.contains("grant_type=refresh_token")) {
                            JSONObject()
                                .put("access_token", "ya29.refreshed_access_token")
                                .put("expires_in", 3600)
                        } else {
                            JSONObject()
                                .put("access_token", "ya29.initial_access_token")
                                .put("refresh_token", "1//refresh_token_secret")
                                .put("expires_in", 3600)
                        }
                        respondJson(200, json.toString())
                    }

                    // OAuth token revoke
                    path == "/oauth2/revoke" && method == "POST" -> {
                        revokedTokens.add(bodyBytes.toString(Charsets.UTF_8))
                        respondJson(200, "{}")
                    }

                    // Google UserInfo
                    path == "/userinfo" && method == "GET" -> {
                        val json = JSONObject()
                            .put("email", "pesquisador@folionote.app")
                            .put("name", "Pesquisador FolioNote")
                        if (!profilePictureToReturn.isNullOrBlank()) {
                            json.put("picture", profilePictureToReturn)
                        }
                        respondJson(200, json.toString())
                    }

                    // Drive v3 list files
                    path == "/drive/v3/files" && method == "GET" -> {
                        val qParam = rawQuery.split("&")
                            .firstOrNull { it.startsWith("q=") }
                            ?.substringAfter("q=")
                            ?.let { URLDecoder.decode(it, "UTF-8") }
                            .orEmpty()

                        val matched = files.values.filter { f ->
                            when {
                                qParam.contains("name = '") -> {
                                    val target = qParam.substringAfter("name = '").substringBefore("'")
                                    f.name == target
                                }
                                qParam.contains("name contains '") -> {
                                    val sub = qParam.substringAfter("name contains '").substringBefore("'")
                                    f.name.contains(sub)
                                }
                                else -> true
                            }
                        }
                        val arr = JSONArray()
                        matched.forEach { arr.put(driveFileJson(it)) }
                        respondJson(200, JSONObject().put("files", arr).toString())
                    }

                    // Drive v3 initiate resumable upload (POST new or PATCH existing)
                    path.startsWith("/upload/drive/v3/files") && (method == "POST" || method == "PATCH") -> {
                        val existingId = path.removePrefix("/upload/drive/v3/files").trimStart('/').takeIf { it.isNotBlank() }
                        val meta = JSONObject(bodyBytes.toString(Charsets.UTF_8))
                        val fileName = meta.optString("name", "backup.zip")
                        val totalLen = exchange.requestHeaders.getFirst("X-Upload-Content-Length")?.toLongOrNull() ?: 0L
                        val props = mutableMapOf<String, String>()
                        meta.optJSONObject("appProperties")?.let { p ->
                            p.keys().forEach { k -> props[k] = p.optString(k, "") }
                        }
                        val sessionId = UUID.randomUUID().toString()
                        sessions[sessionId] = UploadSession(
                            sessionId = sessionId,
                            targetFileId = existingId,
                            fileName = fileName,
                            totalBytes = totalLen,
                            appProperties = props
                        )
                        val location = "http://127.0.0.1:$port/upload/session/$sessionId"
                        respondJson(200, "{}", mapOf("Location" to location))
                    }

                    // Drive v3 resumable chunk PUT & status probe
                    path.startsWith("/upload/session/") && method == "PUT" -> {
                        val sid = path.removePrefix("/upload/session/")
                        val session = sessions[sid]
                        if (session == null) {
                            respondJson(404, """{"error":{"message":"Session expired"}}""")
                            return@createContext
                        }
                        val contentRange = exchange.requestHeaders.getFirst("Content-Range").orEmpty()
                        if (contentRange.startsWith("bytes */")) {
                            val upper = (session.bytesReceived - 1L).coerceAtLeast(0L)
                            if (session.bytesReceived == 0L) {
                                respondBytes(308, "application/json", ByteArray(0))
                            } else {
                                respondBytes(308, "application/json", ByteArray(0), mapOf("Range" to "bytes=0-$upper"))
                            }
                            return@createContext
                        }

                        if (bodyBytes.isNotEmpty()) {
                            session.receivedChunks.write(bodyBytes)
                            session.bytesReceived += bodyBytes.size
                        }

                        val done = session.totalBytes == 0L || session.bytesReceived >= session.totalBytes
                        if (!done) {
                            if (failUploadOnceAfterFirstChunk) {
                                failUploadOnceAfterFirstChunk = false
                                respondJson(503, """{"error":{"message":"Simulated transient drop after chunk"}}""")
                            } else {
                                val upper = session.bytesReceived - 1L
                                respondBytes(308, "application/json", ByteArray(0), mapOf("Range" to "bytes=0-$upper"))
                            }
                        } else {
                            val fileId = session.targetFileId ?: "drv_${UUID.randomUUID().toString().take(8)}"
                            val stored = StoredDriveFile(
                                id = fileId,
                                name = session.fileName,
                                bytes = session.receivedChunks.toByteArray(),
                                appProperties = session.appProperties
                            )
                            files[fileId] = stored
                            sessions.remove(sid)
                            respondJson(200, driveFileJson(stored).toString())
                        }
                    }

                    // Drive v3 download alt=media (supports Range partial download)
                    path.startsWith("/drive/v3/files/") && method == "GET" && rawQuery.contains("alt=media") -> {
                        val fileId = path.removePrefix("/drive/v3/files/")
                        val stored = files[fileId]
                        if (stored == null) {
                            respondJson(404, """{"error":{"message":"Not found"}}""")
                            return@createContext
                        }
                        val rangeHeader = exchange.requestHeaders.getFirst("Range")
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val start = rangeHeader.removePrefix("bytes=").substringBefore("-").toIntOrNull() ?: 0
                            val slice = stored.bytes.copyOfRange(start.coerceIn(0, stored.bytes.size), stored.bytes.size)
                            respondBytes(206, "application/octet-stream", slice)
                        } else if (failDownloadOnceAtMidpoint) {
                            failDownloadOnceAtMidpoint = false
                            // Force premature stream close at midpoint? Even simpler: serve partial directly into .part in test
                            respondBytes(200, "application/octet-stream", stored.bytes)
                        } else {
                            respondBytes(200, "application/octet-stream", stored.bytes)
                        }
                    }

                    // Drive v3 rename PATCH
                    path.startsWith("/drive/v3/files/") && method == "PATCH" -> {
                        val fileId = path.removePrefix("/drive/v3/files/")
                        val stored = files[fileId]
                        if (stored == null) {
                            respondJson(404, """{"error":{"message":"Not found"}}""")
                        } else {
                            val body = JSONObject(bodyBytes.toString(Charsets.UTF_8))
                            stored.name = body.optString("name", stored.name)
                            respondJson(200, driveFileJson(stored).toString())
                        }
                    }

                    // Drive v3 delete
                    path.startsWith("/drive/v3/files/") && method == "DELETE" -> {
                        val fileId = path.removePrefix("/drive/v3/files/")
                        files.remove(fileId)
                        respondBytes(204, "application/json", ByteArray(0))
                    }

                    else -> respondJson(404, """{"error":{"message":"Unhandled $method $path"}}""")
                }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun driveFileJson(f: StoredDriveFile): JSONObject = JSONObject().apply {
            put("id", f.id)
            put("name", f.name)
            put("size", f.bytes.size.toString())
            put("modifiedTime", f.modifiedTime)
            val props = JSONObject()
            f.appProperties.forEach { (k, v) -> props.put(k, v) }
            put("appProperties", props)
        }
    }

    // Test 12: Native Google Sign-In + AuthorizationClient flow, silent renewal, Keystore persistence, and safe disconnect/revoke
    @Test
    fun `native google drive auth refreshes tokens encrypts in keystore and revokes safely without touching gemini key`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val fakeDrive = FakeDriveServer()

        try {
            prefs.clearAllSensitiveCredentials()
            prefs.clearDriveAccountSession()
            prefs.saveGeminiEmbeddingConfig(true, "gemini-embedding-2-preview", 768, crypto.encrypt("AIzaKeepMyGeminiKey"))

            var silentCalls = 0
            var revokedGateway = false
            var clearedCreds = false
            val fakeAuthenticator = object : com.sjos.folionote.engine.drive.GoogleCredentialAuthenticator {
                override suspend fun authenticate(
                    activity: android.app.Activity,
                    serverClientId: String
                ) = com.sjos.folionote.engine.drive.CredentialAuthResult.Authenticated(
                    profile = com.sjos.folionote.engine.drive.GoogleAccountProfile(
                        email = "pesquisador@folionote.app",
                        displayName = "Pesquisador FolioNote"
                    ),
                    idToken = "id_token_123"
                )

                override suspend fun clearSession() {
                    clearedCreds = true
                }
            }
            val fakeGateway = object : com.sjos.folionote.engine.drive.DriveAuthorizationGateway {
                override suspend fun authorize(
                    context: Context,
                    scopes: List<String>,
                    accountEmail: String?
                ): com.sjos.folionote.engine.drive.DriveAuthResult {
                    silentCalls++
                    assertTrue(scopes.contains(GoogleDriveAuthManager.SCOPE_DRIVE_APPDATA))
                    assertFalse(scopes.contains(GoogleDriveAuthManager.SCOPE_DRIVE_READONLY))
                    return com.sjos.folionote.engine.drive.DriveAuthResult.Authorized(
                        accessToken = if (silentCalls == 1) "ya29.initial_access_token" else "ya29.silent_renewed_token",
                        expiresInSeconds = 3600L
                    )
                }

                override fun extractAuthorizationFromIntent(
                    context: Context,
                    data: android.content.Intent?
                ) = com.sjos.folionote.engine.drive.DriveAuthResult.Cancelled

                override suspend fun revokeAccess(context: Context, scopes: List<String>, accountEmail: String?) {
                    revokedGateway = true
                }
            }

            val port = fakeDrive.port
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val authManager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:$port/oauth2",
                userInfoUrl = "http://127.0.0.1:$port/userinfo",
                credentialAuthenticator = fakeAuthenticator,
                authorizationGateway = fakeGateway,
                serverClientIdProvider = { "web-client-dev.apps.googleusercontent.com" }
            )

            // 1. Connect via Credential Manager + AuthorizationClient
            val outcome = authManager.connectGoogleAccount(activity)
            assertTrue(outcome is com.sjos.folionote.engine.drive.GoogleConnectOutcome.Connected)
            val profile = (outcome as com.sjos.folionote.engine.drive.GoogleConnectOutcome.Connected).profile
            assertEquals("pesquisador@folionote.app", profile.email)

            val saved = prefs.settingsFlow.first()
            assertTrue(saved.isGoogleDriveConnected)
            assertFalse(saved.driveEncryptedAccessToken.contains("ya29.initial_access_token"))
            assertEquals("ya29.initial_access_token", crypto.decrypt(saved.driveEncryptedAccessToken))

            // 2. Expired token triggers silent AuthorizationClient renewal cleanly
            prefs.saveDriveAccountSession(
                email = saved.driveAccountEmail!!,
                displayName = saved.driveAccountName,
                photoUrl = saved.driveAccountPhotoUrl,
                encryptedAccessToken = saved.driveEncryptedAccessToken,
                encryptedRefreshToken = null,
                expiresAt = System.currentTimeMillis() - 10_000L
            )
            val refreshedToken = authManager.getValidAccessToken().getOrThrow()
            assertEquals("ya29.silent_renewed_token", refreshedToken)

            // 3. Disconnect & revoke removes Drive tokens cleanly while keeping Gemini Embedding 2 key untouched!
            authManager.disconnectAndRevoke()
            assertTrue(revokedGateway)
            assertTrue(clearedCreds)
            assertTrue(fakeDrive.revokedTokens.any { it.contains("ya29.silent_renewed_token") })
            val afterDisconnect = prefs.settingsFlow.first()
            assertFalse(afterDisconnect.isGoogleDriveConnected)
            assertNull(afterDisconnect.driveAccountEmail)
            assertTrue(afterDisconnect.hasGeminiEmbeddingKey)
            assertEquals("AIzaKeepMyGeminiKey", crypto.decrypt(afterDisconnect.geminiEmbeddingEncryptedKey))
        } finally {
            prefs.clearAllSensitiveCredentials()
            prefs.clearDriveAccountSession()
            fakeDrive.stop()
        }
    }

    // Test 13: Google profile photo URL fetching, persistence, refreshProfile, and blank-photo fallback
    @Test
    fun `google profile photo url fetches persists refreshes and handles absent picture cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val fakeDrive = FakeDriveServer()

        try {
            prefs.clearDriveAccountSession()
            val port = fakeDrive.port
            val authManager = GoogleDriveAuthManager(
                context = context,
                preferencesStore = prefs,
                cryptoManager = crypto,
                baseHttpClient = OkHttpClient(),
                oauthBaseUrl = "http://127.0.0.1:$port/oauth2",
                userInfoUrl = "http://127.0.0.1:$port/userinfo"
            )

            // With picture URL present
            fakeDrive.profilePictureToReturn = "https://lh3.googleusercontent.com/avatar-folionote.png"
            val p1 = authManager.saveAccessTokenAndLoadProfile("ya29.token1", "refresh1", 3600L).getOrThrow()
            assertEquals("https://lh3.googleusercontent.com/avatar-folionote.png", p1.photoUrl)
            assertEquals("https://lh3.googleusercontent.com/avatar-folionote.png", prefs.settingsFlow.first().driveAccountPhotoUrl)
            assertEquals("Pesquisador FolioNote", prefs.settingsFlow.first().driveAccountName)

            // When Google removes/omits picture URL -> refreshProfile clears photoUrl cleanly for initial-letter fallback
            fakeDrive.profilePictureToReturn = null
            val p2 = authManager.refreshProfile().getOrThrow()
            assertNull(p2.photoUrl)
            assertNull(prefs.settingsFlow.first().driveAccountPhotoUrl)
        } finally {
            prefs.clearDriveAccountSession()
            fakeDrive.stop()
        }
    }

    // Test 14: Backup ZIP creation & listing strictly excludes all API keys, OAuth tokens, and passwords
    @Test
    fun `backup zip creation and drive upload strictly exclude all api keys and oauth tokens`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val fileStorage = FileStorageManager(context)
        val fakeDrive = FakeDriveServer()
        val tempDir = Files.createTempDirectory("folionote_bk_test").toFile()

        try {
            prefs.clearAllSensitiveCredentials()
            prefs.clearDriveAccountSession()

            // Store secrets across every subsystem
            val geminiSecret = "AIza-SUPER-SECRET-GEMINI-KEY-999"
            val chatSecret = "sk-SUPER-SECRET-CHAT-KEY-888"
            val ocrSecret = "vision-SECRET-OCR-777"
            val transSecret = "whisper-SECRET-TRANS-666"
            val driveAccessSecret = "ya29.SECRET-DRIVE-ACCESS"
            val driveRefreshSecret = "1//SECRET-DRIVE-REFRESH"

            prefs.saveGeminiEmbeddingConfig(true, "gemini-embedding-2-preview", 1536, crypto.encrypt(geminiSecret))
            prefs.saveOcrConfig(OcrProviderType.GOOGLE_CLOUD_VISION, "https://vision.googleapis.com/v1/images:annotate", "", crypto.encrypt(ocrSecret))
            prefs.saveTranscriptionConfig(TranscriptionProviderType.OPENAI_AUDIO_COMPAT, "https://api.openai.com/v1/audio/transcriptions", "whisper-1", "pt", crypto.encrypt(transSecret))
            prefs.saveDriveAccountSession("user@test.com", "User", null, crypto.encrypt(driveAccessSecret), crypto.encrypt(driveRefreshSecret), System.currentTimeMillis() + 3600_000L)

            db.providerAndModelDao().upsertProvider(
                ProviderEntity("p_secret", "Provedor Chat", "https://api.openai.com/v1", encryptedApiKey = crypto.encrypt(chatSecret))
            )

            // Seed notebook data & local file
            val nbDir = fileStorage.ensureNotebookSourcesDir("nb_bk")
            val srcFile = File(nbDir, "artigo.md").apply { writeText("# Artigo Seguro\nConteúdo do usuário.") }
            db.notebookDao().upsertCollection(CollectionEntity("col_bk", "Pós"))
            db.notebookDao().upsertNotebook(NotebookEntity("nb_bk", "Caderno Backup", collectionId = "col_bk"))
            db.sourceDao().upsertSource(
                SourceEntity("src_bk", "nb_bk", "artigo.md", "text/markdown", "MARKDOWN", srcFile.absolutePath, srcFile.length(), "hash_bk", extractedText = "Conteúdo do usuário.")
            )
            db.chunkAndEmbeddingDao().insertChunks(
                listOf(DocumentChunkEntity("chk_bk", "src_bk", "nb_bk", 0, "Artigo", 1, 0, 20, "Conteúdo do usuário.", 5))
            )
            db.conversationAndMessageDao().upsertConversation(ConversationEntity("conv_bk", "nb_bk", "Dúvidas"))
            db.conversationAndMessageDao().upsertMessage(MessageEntity("msg_bk", "conv_bk", "USER", "Resuma", orderIndex = 1))

            val backupMgr = BackupArchiveManager(context, db, fileStorage, prefs)
            val port = fakeDrive.port
            val apiClient = GoogleDriveApiClient(OkHttpClient(), "http://127.0.0.1:$port/drive/v3", "http://127.0.0.1:$port/upload/drive/v3")
            val authMgr = GoogleDriveAuthManager(context, prefs, crypto, OkHttpClient(), "http://127.0.0.1:$port/oauth2", userInfoUrl = "http://127.0.0.1:$port/userinfo")
            val syncEngine = DriveSyncEngine(context, db, prefs, fileStorage, authMgr, apiClient, backupMgr)

            // Create local zip and inspect every single entry
            val localZip = File(tempDir, "audit_backup.zip")
            val summary = backupMgr.createBackupArchive(localZip)
            assertEquals(1, summary.notebookCount)
            assertEquals(1, summary.sourceCount)
            assertEquals(1, summary.messageCount)

            val entriesContent = mutableMapOf<String, ByteArray>()
            ZipInputStream(localZip.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    entriesContent[e.name] = zis.readBytes()
                    e = zis.nextEntry
                }
            }
            assertTrue(entriesContent.containsKey(BackupArchiveManager.ENTRY_MANIFEST))
            assertTrue(entriesContent.containsKey(BackupArchiveManager.ENTRY_DATABASE))
            assertTrue(entriesContent.keys.any { it.startsWith("files/nb_bk/") })

            val allText = entriesContent.values.joinToString("\n") { it.toString(Charsets.UTF_8) }
            val forbiddenSubstrings = listOf(
                geminiSecret, chatSecret, ocrSecret, transSecret, driveAccessSecret, driveRefreshSecret,
                "encryptedApiKey", "geminiEmbeddingEncryptedKey", "ocrEncryptedApiKey", "transcriptionEncryptedApiKey",
                "driveEncryptedAccessToken", "driveEncryptedRefreshToken", "enc_v1:", "test_v1:"
            )
            for (secret in forbiddenSubstrings) {
                assertFalse("Backup ZIP leaked secret marker: $secret", allText.contains(secret))
            }

            // Upload backup & verify listing on Drive
            val uploaded = syncEngine.createAndUploadBackup("meu_backup").getOrThrow()
            assertTrue(uploaded.name.contains("folionote_backup"))
            val listed = syncEngine.refreshBackupList().getOrThrow()
            assertEquals(1, listed.size)
            assertEquals(uploaded.id, listed.first().id)
        } finally {
            prefs.clearAllSensitiveCredentials()
            prefs.clearDriveAccountSession()
            fileStorage.deleteNotebookDirectory("nb_bk")
            db.close()
            fakeDrive.stop()
            tempDir.deleteRecursively()
        }
    }

    // Test 15: Backup restore integrity into clean DB & storage while keeping existing local API keys untouched
    @Test
    fun `backup restore rebuilds entities and source files in app storage without altering local keys`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbSource = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java).allowMainThreadQueries().build()
        val dbTarget = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java).allowMainThreadQueries().build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val fileStorage = FileStorageManager(context)
        val tempDir = Files.createTempDirectory("folionote_restore_test").toFile()

        try {
            prefs.clearAllSensitiveCredentials()
            val nbDir = fileStorage.ensureNotebookSourcesDir("nb_rest")
            val srcFile = File(nbDir, "nota_rest.md").apply { writeText("Conteúdo preservado no backup ZIP") }

            dbSource.notebookDao().upsertCollection(CollectionEntity("col_r", "Pesquisa"))
            dbSource.notebookDao().upsertNotebook(NotebookEntity("nb_rest", "Caderno Restaurado", collectionId = "col_r", isPinned = true))
            dbSource.sourceDao().upsertSource(
                SourceEntity("src_r", "nb_rest", "nota_rest.md", "text/markdown", "MARKDOWN", srcFile.absolutePath, srcFile.length(), "sha_r", extractedText = "Conteúdo preservado")
            )
            dbSource.chunkAndEmbeddingDao().insertChunks(listOf(DocumentChunkEntity("chk_r", "src_r", "nb_rest", 0, "Topo", 1, 0, 20, "Conteúdo preservado", 4)))
            dbSource.conversationAndMessageDao().upsertConversation(ConversationEntity("conv_r", "nb_rest", "Debate"))
            dbSource.conversationAndMessageDao().upsertMessage(MessageEntity("msg_r", "conv_r", "ASSISTANT", "Resposta salva", orderIndex = 1))
            dbSource.conversationAndMessageDao().upsertStudioArtifact(StudioArtifactEntity("art_r", "nb_rest", "SUMMARY", "Resumo", "# Resumo\nOK", 1))

            val backupZip = File(tempDir, "restore_me.zip")
            BackupArchiveManager(context, dbSource, fileStorage, prefs).createBackupArchive(backupZip)

            // Delete local file to simulate clean device, and set a different local Gemini key on target device
            fileStorage.deleteNotebookDirectory("nb_rest")
            assertFalse(srcFile.exists())
            prefs.saveGeminiEmbeddingConfig(true, "gemini-embedding-2-preview", 768, crypto.encrypt("AIzaTargetDeviceKey"))

            val restoreSummary = BackupArchiveManager(context, dbTarget, fileStorage, prefs)
                .restoreFromBackupArchive(backupZip)

            assertEquals(1, restoreSummary.collectionsRestored)
            assertEquals(1, restoreSummary.notebooksRestored)
            assertEquals(1, restoreSummary.sourcesRestored)
            assertEquals(1, restoreSummary.chunksRestored)
            assertEquals(1, restoreSummary.conversationsRestored)
            assertEquals(1, restoreSummary.messagesRestored)
            assertEquals(1, restoreSummary.artifactsRestored)
            assertEquals(1, restoreSummary.filesCopied)

            val restoredSource = dbTarget.sourceDao().getSourceById("src_r")!!
            val restoredFile = File(restoredSource.filePath)
            assertTrue(restoredFile.exists())
            assertEquals("Conteúdo preservado no backup ZIP", restoredFile.readText())
            assertEquals("Caderno Restaurado", dbTarget.notebookDao().getNotebookById("nb_rest")?.title)

            // Local Gemini Embedding 2 key on target device remained untouched
            val targetSettings = prefs.settingsFlow.first()
            assertEquals("AIzaTargetDeviceKey", crypto.decrypt(targetSettings.geminiEmbeddingEncryptedKey))
        } finally {
            prefs.clearAllSensitiveCredentials()
            fileStorage.deleteNotebookDirectory("nb_rest")
            dbSource.close()
            dbTarget.close()
            tempDir.deleteRecursively()
        }
    }

    // Test 16: Backup rename and delete via Drive API & DriveSyncEngine
    @Test
    fun `drive sync engine renames and deletes remote backups cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java).allowMainThreadQueries().build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val fileStorage = FileStorageManager(context)
        val fakeDrive = FakeDriveServer()

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession("user@test.com", "User", null, crypto.encrypt("ya29.valid"), null, System.currentTimeMillis() + 3600_000L)

            val port = fakeDrive.port
            val apiClient = GoogleDriveApiClient(OkHttpClient(), "http://127.0.0.1:$port/drive/v3", "http://127.0.0.1:$port/upload/drive/v3")
            val authMgr = GoogleDriveAuthManager(context, prefs, crypto, OkHttpClient(), "http://127.0.0.1:$port/oauth2", userInfoUrl = "http://127.0.0.1:$port/userinfo")
            val syncEngine = DriveSyncEngine(context, db, prefs, fileStorage, authMgr, apiClient, BackupArchiveManager(context, db, fileStorage, prefs))

            val created = syncEngine.createAndUploadBackup("backup_inicial").getOrThrow()
            assertTrue(syncEngine.runtimeState.value.backups.any { it.id == created.id })

            val renamed = syncEngine.renameBackup(created.id, "backup_renomeado").getOrThrow()
            assertEquals("backup_renomeado.zip", renamed.name)
            assertEquals("backup_renomeado.zip", syncEngine.runtimeState.value.backups.first { it.id == created.id }.name)

            val deleted = syncEngine.deleteBackup(created.id)
            assertTrue(deleted.isSuccess)
            assertTrue(syncEngine.runtimeState.value.backups.none { it.id == created.id })
            assertTrue(syncEngine.refreshBackupList().getOrThrow().isEmpty())
        } finally {
            prefs.clearDriveAccountSession()
            db.close()
            fakeDrive.stop()
        }
    }

    // Test 17: Bidirectional incremental sync (local -> remote, remote -> local, and bidirectional tombstones)
    @Test
    fun `bidirectional sync pushes local records pulls remote records and propagates tombstones both ways`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java).allowMainThreadQueries().build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val fileStorage = FileStorageManager(context)
        val fakeDrive = FakeDriveServer()

        try {
            prefs.clearDriveAccountSession()
            prefs.saveDriveAccountSession("sync@test.com", "Sync", null, crypto.encrypt("ya29.sync"), null, System.currentTimeMillis() + 3600_000L)

            val port = fakeDrive.port
            val apiClient = GoogleDriveApiClient(OkHttpClient(), "http://127.0.0.1:$port/drive/v3", "http://127.0.0.1:$port/upload/drive/v3")
            val authMgr = GoogleDriveAuthManager(context, prefs, crypto, OkHttpClient(), "http://127.0.0.1:$port/oauth2", userInfoUrl = "http://127.0.0.1:$port/userinfo")
            val syncEngine = DriveSyncEngine(context, db, prefs, fileStorage, authMgr, apiClient, BackupArchiveManager(context, db, fileStorage, prefs))

            // 1. Local data -> first sync pushes to Drive
            val nbDir = fileStorage.ensureNotebookSourcesDir("nb_sync1")
            val srcFile = File(nbDir, "leitura.txt").apply { writeText("Texto sincronizado na nuvem") }
            db.notebookDao().upsertNotebook(NotebookEntity("nb_sync1", "Caderno Local", createdAt = 100L, updatedAt = 100L))
            db.notebookDao().upsertNotebook(NotebookEntity("nb_tomb_remote", "Será Removido Remotamente", createdAt = 100L, updatedAt = 100L))
            db.sourceDao().upsertSource(
                SourceEntity("src_sync1", "nb_sync1", "leitura.txt", "text/plain", "TXT", srcFile.absolutePath, srcFile.length(), "h_s1", importedAt = 100L, updatedAt = 100L)
            )

            val pushRes = syncEngine.syncNow(scheduleRetryOnNetworkError = false).getOrThrow()
            assertTrue(pushRes.uploadedCount >= 3)

            // 2. Simulate another device updating the remote manifest: adds nb_remote & deletes nb_tomb_remote via tombstone
            val remoteManifest = fakeDrive.files.values.first { it.name == DriveSyncEngine.SYNC_MANIFEST_FILENAME }
            val manifestJson = JSONObject(remoteManifest.bytes.toString(Charsets.UTF_8))
            val nbsArr = manifestJson.getJSONArray("notebooks")
            val filteredNbs = JSONArray()
            for (i in 0 until nbsArr.length()) {
                val obj = nbsArr.getJSONObject(i)
                if (obj.getString("id") != "nb_tomb_remote") filteredNbs.put(obj)
            }
            filteredNbs.put(
                JSONObject()
                    .put("id", "nb_remote")
                    .put("title", "Criado no Tablet")
                    .put("description", "Notas")
                    .put("iconKey", "book")
                    .put("collectionId", JSONObject.NULL)
                    .put("isPinned", false)
                    .put("createdAt", 500L)
                    .put("updatedAt", 500L)
            )
            manifestJson.put("notebooks", filteredNbs)
            manifestJson.getJSONArray("tombstones").put(
                JSONObject()
                    .put("id", "NOTEBOOK:nb_tomb_remote")
                    .put("entityType", "NOTEBOOK")
                    .put("entityId", "nb_tomb_remote")
                    .put("deletedAt", System.currentTimeMillis() + 5_000L)
                    .put("revision", 2L)
            )
            remoteManifest.bytes = manifestJson.toString().toByteArray(Charsets.UTF_8)

            val pullRes = syncEngine.syncNow(scheduleRetryOnNetworkError = false).getOrThrow()
            assertTrue(pullRes.downloadedCount >= 1)
            assertEquals(1, pullRes.deletedLocallyCount)
            assertNotNull(db.notebookDao().getNotebookById("nb_remote"))
            assertNull(db.notebookDao().getNotebookById("nb_tomb_remote"))

            // 3. Delete nb_remote locally with a tombstone -> next sync deletes it from remote manifest
            val delAt = System.currentTimeMillis() + 10_000L
            db.syncDao().upsertTombstone(SyncTombstoneEntity("NOTEBOOK:nb_remote", "NOTEBOOK", "nb_remote", delAt, 3L, false))
            db.notebookDao().deleteNotebookById("nb_remote")

            val deleteRemoteRes = syncEngine.syncNow(scheduleRetryOnNetworkError = false).getOrThrow()
            assertEquals(1, deleteRemoteRes.deletedRemotelyCount)
            val updatedManifest = JSONObject(fakeDrive.files.values.first { it.name == DriveSyncEngine.SYNC_MANIFEST_FILENAME }.bytes.toString(Charsets.UTF_8))
            val remoteIds = (0 until updatedManifest.getJSONArray("notebooks").length()).map {
                updatedManifest.getJSONArray("notebooks").getJSONObject(it).getString("id")
            }
            assertFalse(remoteIds.contains("nb_remote"))
        } finally {
            prefs.clearDriveAccountSession()
            fileStorage.deleteNotebookDirectory("nb_sync1")
            db.close()
            fakeDrive.stop()
        }
    }

    // Test 18: Conflict detection & explicit choices (ASK_USER, DUPLICATE_BOTH, KEEP_LOCAL, KEEP_REMOTE)
    @Test
    fun `concurrent edits trigger explicit sync conflicts and resolve cleanly without silent data loss`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FolioNoteDatabase::class.java).allowMainThreadQueries().build()
        val prefs = AppPreferencesStore(context)
        val crypto = KeystoreCryptoManager()
        val fileStorage = FileStorageManager(context)
        val fakeDrive = FakeDriveServer()

        try {
            prefs.clearDriveAccountSession()
            prefs.setDriveConflictPolicy(DriveConflictPolicy.ASK_USER)
            prefs.saveDriveAccountSession("conflict@test.com", "User", null, crypto.encrypt("ya29.conf"), null, System.currentTimeMillis() + 3600_000L)

            val port = fakeDrive.port
            val apiClient = GoogleDriveApiClient(OkHttpClient(), "http://127.0.0.1:$port/drive/v3", "http://127.0.0.1:$port/upload/drive/v3")
            val authMgr = GoogleDriveAuthManager(context, prefs, crypto, OkHttpClient(), "http://127.0.0.1:$port/oauth2", userInfoUrl = "http://127.0.0.1:$port/userinfo")
            val syncEngine = DriveSyncEngine(context, db, prefs, fileStorage, authMgr, apiClient, BackupArchiveManager(context, db, fileStorage, prefs))

            // Initial baseline sync
            db.notebookDao().upsertNotebook(NotebookEntity("nb_conf", "Título Original", createdAt = 100L, updatedAt = 100L))
            syncEngine.syncNow(scheduleRetryOnNetworkError = false).getOrThrow()

            // Local edit
            db.notebookDao().upsertNotebook(NotebookEntity("nb_conf", "Edição Local", createdAt = 100L, updatedAt = System.currentTimeMillis() + 1_000L))

            // Concurrent remote edit
            val remoteManifest = fakeDrive.files.values.first { it.name == DriveSyncEngine.SYNC_MANIFEST_FILENAME }
            val json = JSONObject(remoteManifest.bytes.toString(Charsets.UTF_8))
            json.getJSONArray("notebooks").getJSONObject(0)
                .put("title", "Edição Remota")
                .put("updatedAt", System.currentTimeMillis() + 2_000L)
            remoteManifest.bytes = json.toString().toByteArray(Charsets.UTF_8)

            // Sync with ASK_USER -> records pending SyncConflictEntity without overwriting local edit
            val syncRes = syncEngine.syncNow(scheduleRetryOnNetworkError = false).getOrThrow()
            assertEquals(1, syncRes.conflictsDetected)
            assertEquals("Edição Local", db.notebookDao().getNotebookById("nb_conf")?.title)

            val pending = db.syncDao().getPendingConflictsOnce()
            assertEquals(1, pending.size)
            val conflict = pending.first()

            // Resolve with DUPLICATE_BOTH -> keeps local AND inserts "(Nuvem)" copy
            syncEngine.resolveConflict(conflict.id, ConflictResolutionChoice.DUPLICATE_BOTH).getOrThrow()
            assertTrue(db.syncDao().getPendingConflictsOnce().isEmpty())
            val allNotebooks = db.notebookDao().getAllNotebooksOnce()
            assertEquals(2, allNotebooks.size)
            assertTrue(allNotebooks.any { it.title == "Edição Local" })
            assertTrue(allNotebooks.any { it.title == "Edição Remota (Nuvem)" })

            // Also verify KEEP_LOCAL marks SyncRecord dirty & KEEP_REMOTE overwrites with remote
            db.syncDao().upsertConflict(conflict.copy(id = "c2", resolutionStatus = "PENDING"))
            syncEngine.resolveConflict("c2", ConflictResolutionChoice.KEEP_LOCAL).getOrThrow()
            assertTrue(db.syncDao().getSyncRecord(DriveSyncEngine.recordKey("NOTEBOOK", "nb_conf"))?.isDirty == true)

            db.syncDao().upsertConflict(conflict.copy(id = "c3", resolutionStatus = "PENDING"))
            syncEngine.resolveConflict("c3", ConflictResolutionChoice.KEEP_REMOTE).getOrThrow()
            assertEquals("Edição Remota", db.notebookDao().getNotebookById("nb_conf")?.title)
        } finally {
            prefs.clearDriveAccountSession()
            db.close()
            fakeDrive.stop()
        }
    }

    // Test 19: Resumable upload (HTTP 308 Range offset) & partial download recovery (HTTP 206 Range + .part)
    @Test
    fun `google drive client resumes interrupted chunked upload via 308 and partial download via range 206`() = runTest {
        val fakeDrive = FakeDriveServer()
        val tempDir = Files.createTempDirectory("folionote_resume_test").toFile()

        try {
            val port = fakeDrive.port
            val apiClient = GoogleDriveApiClient(OkHttpClient(), "http://127.0.0.1:$port/drive/v3", "http://127.0.0.1:$port/upload/drive/v3")

            // Create 180 KB binary payload (> 2 chunks at 64 KB chunkSizeBytes)
            val payload = ByteArray(180 * 1024) { idx -> (idx % 251).toByte() }
            val sourceFile = File(tempDir, "large_backup.zip").apply { writeBytes(payload) }

            // 1. Interrupt upload after first 64KB chunk
            fakeDrive.failUploadOnceAfterFirstChunk = true
            var capturedSessionUri: String? = null
            val failedAttempt = apiClient.resumableUploadFile(
                accessToken = "ya29.resume",
                localFile = sourceFile,
                remoteFileName = "folionote_backup_resume.zip",
                mimeType = "application/zip",
                chunkSizeBytes = 64 * 1024,
                onSessionCreated = { capturedSessionUri = it }
            )
            assertTrue(failedAttempt.isFailure)
            assertNotNull(capturedSessionUri)

            // 2. Resume using capturedSessionUri -> server responds 308 Range: bytes=0-65535 and client uploads remaining bytes
            val resumedUpload = apiClient.resumableUploadFile(
                accessToken = "ya29.resume",
                localFile = sourceFile,
                remoteFileName = "folionote_backup_resume.zip",
                mimeType = "application/zip",
                existingSessionUri = capturedSessionUri,
                chunkSizeBytes = 64 * 1024
            ).getOrThrow()

            val serverStored = fakeDrive.files[resumedUpload.id]!!
            assertArrayEquals(payload, serverStored.bytes)

            // 3. Partial download recovery: pre-seed .part file with first 50,000 bytes -> client requests Range: bytes=50000- and receives 206
            val downloadDest = File(tempDir, "restored.zip")
            val partFile = File(tempDir, "restored.zip.part").apply {
                writeBytes(payload.copyOfRange(0, 50_000))
            }
            assertTrue(partFile.exists())

            val downloaded = apiClient.downloadFile(
                accessToken = "ya29.resume",
                fileId = resumedUpload.id,
                destFile = downloadDest,
                resumeIfPartial = true
            ).getOrThrow()

            assertFalse(partFile.exists())
            assertArrayEquals(payload, downloaded.readBytes())
        } finally {
            fakeDrive.stop()
            tempDir.deleteRecursively()
        }
    }
}
