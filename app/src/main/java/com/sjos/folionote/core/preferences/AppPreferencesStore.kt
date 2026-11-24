package com.sjos.folionote.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sjos.folionote.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "folionote_prefs")

enum class OcrProviderType(val label: String, val defaultEndpoint: String) {
    GOOGLE_CLOUD_VISION("Google Cloud Vision OCR", "https://vision.googleapis.com/v1/images:annotate"),
    OPENAI_VISION_COMPAT("Vision OpenAI-Compat", "https://api.openai.com/v1/chat/completions"),
    CUSTOM_OCR_ENDPOINT("Endpoint OCR Personalizado", "")
}

enum class TranscriptionProviderType(val label: String, val defaultEndpoint: String) {
    OPENAI_AUDIO_COMPAT("OpenAI Audio / Whisper", "https://api.openai.com/v1/audio/transcriptions"),
    CUSTOM_TRANSCRIPTION_ENDPOINT("Endpoint Transcrição Personalizado", "")
}

enum class DriveConflictPolicy(val label: String) {
    ASK_USER("Perguntar ao usuário (recomendado)"),
    DUPLICATE_BOTH("Duplicar ambos sem sobrescrever"),
    KEEP_NEWER("Mais recente vence"),
    KEEP_LOCAL("Manter versão local"),
    KEEP_REMOTE("Manter versão da nuvem")
}

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.DARK,
    val activeEmbeddingModelId: String? = null,
    val chunkSizeChars: Int = 900,
    val chunkOverlapChars: Int = 160,
    val ragTopK: Int = 6,
    val showSourcesBanner: Boolean = true,
    val externalDisclosureAck: Boolean = false,
    // Dedicated Gemini Embedding 2 user-key config (completely isolated from chat providers)
    val geminiEmbeddingEnabled: Boolean = false,
    val geminiEmbeddingModelId: String = "gemini-embedding-2-preview",
    val geminiEmbeddingDimensions: Int = 768,
    val geminiEmbeddingEncryptedKey: String = "",
    val geminiEmbeddingLastValidatedAt: Long? = null,
    // Decoupled External OCR config (no local AI models)
    val ocrProviderType: OcrProviderType = OcrProviderType.GOOGLE_CLOUD_VISION,
    val ocrEndpointUrl: String = OcrProviderType.GOOGLE_CLOUD_VISION.defaultEndpoint,
    val ocrModelId: String = "",
    val ocrEncryptedApiKey: String = "",
    // Decoupled External Audio/Video Transcription config (no local AI models)
    val transcriptionProviderType: TranscriptionProviderType = TranscriptionProviderType.OPENAI_AUDIO_COMPAT,
    val transcriptionEndpointUrl: String = TranscriptionProviderType.OPENAI_AUDIO_COMPAT.defaultEndpoint,
    val transcriptionModelId: String = "whisper-1",
    val transcriptionLanguage: String = "pt",
    val transcriptionEncryptedApiKey: String = "",
    // Google Account & Drive Sync/Backup
    val driveAccountEmail: String? = null,
    val driveAccountName: String? = null,
    val driveAccountPhotoUrl: String? = null,
    val driveEncryptedAccessToken: String = "",
    val driveEncryptedRefreshToken: String = "",
    val driveTokenExpiresAt: Long = 0L,
    val driveOAuthClientId: String = "",
    val driveSyncEnabled: Boolean = false,
    val driveConflictPolicy: DriveConflictPolicy = DriveConflictPolicy.ASK_USER,
    val driveLastBackupAt: Long? = null,
    val driveLastSyncAt: Long? = null,
    val driveLastSyncStatus: String = "",
    val driveLastSyncError: String? = null
) {
    val hasGeminiEmbeddingKey: Boolean
        get() = geminiEmbeddingEncryptedKey.isNotBlank()

    val isGeminiEmbeddingActive: Boolean
        get() = geminiEmbeddingEnabled && geminiEmbeddingEncryptedKey.isNotBlank()

    val hasOcrConfigured: Boolean
        get() = ocrEndpointUrl.isNotBlank() && (ocrEncryptedApiKey.isNotBlank() || ocrProviderType == OcrProviderType.CUSTOM_OCR_ENDPOINT)

    val hasTranscriptionConfigured: Boolean
        get() = transcriptionEndpointUrl.isNotBlank() && (transcriptionEncryptedApiKey.isNotBlank() || transcriptionProviderType == TranscriptionProviderType.CUSTOM_TRANSCRIPTION_ENDPOINT)

    val isGoogleDriveConnected: Boolean
        get() = !driveAccountEmail.isNullOrBlank() && (driveEncryptedAccessToken.isNotBlank() || driveEncryptedRefreshToken.isNotBlank())
}

class AppPreferencesStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_preference")
        val ACTIVE_EMBEDDING_MODEL_ID = stringPreferencesKey("active_embedding_model_id")
        val CHUNK_SIZE_CHARS = intPreferencesKey("chunk_size_chars")
        val CHUNK_OVERLAP_CHARS = intPreferencesKey("chunk_overlap_chars")
        val RAG_TOP_K = intPreferencesKey("rag_top_k")
        val SHOW_SOURCES_BANNER = booleanPreferencesKey("show_sources_banner")
        val EXTERNAL_DISCLOSURE_ACK = booleanPreferencesKey("external_disclosure_ack")

        val GEMINI_EMB_ENABLED = booleanPreferencesKey("gemini_emb_enabled")
        val GEMINI_EMB_MODEL_ID = stringPreferencesKey("gemini_emb_model_id")
        val GEMINI_EMB_DIMENSIONS = intPreferencesKey("gemini_emb_dimensions")
        val GEMINI_EMB_ENC_KEY = stringPreferencesKey("gemini_emb_enc_key")
        val GEMINI_EMB_VALIDATED_AT = longPreferencesKey("gemini_emb_validated_at")

        val OCR_PROVIDER_TYPE = stringPreferencesKey("ocr_provider_type")
        val OCR_ENDPOINT_URL = stringPreferencesKey("ocr_endpoint_url")
        val OCR_MODEL_ID = stringPreferencesKey("ocr_model_id")
        val OCR_ENC_KEY = stringPreferencesKey("ocr_enc_key")

        val TRANS_PROVIDER_TYPE = stringPreferencesKey("trans_provider_type")
        val TRANS_ENDPOINT_URL = stringPreferencesKey("trans_endpoint_url")
        val TRANS_MODEL_ID = stringPreferencesKey("trans_model_id")
        val TRANS_LANGUAGE = stringPreferencesKey("trans_language")
        val TRANS_ENC_KEY = stringPreferencesKey("trans_enc_key")

        val DRIVE_EMAIL = stringPreferencesKey("drive_account_email")
        val DRIVE_NAME = stringPreferencesKey("drive_account_name")
        val DRIVE_PHOTO_URL = stringPreferencesKey("drive_account_photo_url")
        val DRIVE_ENC_ACCESS_TOKEN = stringPreferencesKey("drive_enc_access_token")
        val DRIVE_ENC_REFRESH_TOKEN = stringPreferencesKey("drive_enc_refresh_token")
        val DRIVE_TOKEN_EXPIRES_AT = longPreferencesKey("drive_token_expires_at")
        val DRIVE_OAUTH_CLIENT_ID = stringPreferencesKey("drive_oauth_client_id")
        val DRIVE_SYNC_ENABLED = booleanPreferencesKey("drive_sync_enabled")
        val DRIVE_CONFLICT_POLICY = stringPreferencesKey("drive_conflict_policy")
        val DRIVE_LAST_BACKUP_AT = longPreferencesKey("drive_last_backup_at")
        val DRIVE_LAST_SYNC_AT = longPreferencesKey("drive_last_sync_at")
        val DRIVE_LAST_SYNC_STATUS = stringPreferencesKey("drive_last_sync_status")
        val DRIVE_LAST_SYNC_ERROR = stringPreferencesKey("drive_last_sync_error")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val theme = prefs[Keys.THEME]?.let {
            runCatching { ThemePreference.valueOf(it) }.getOrNull()
        } ?: ThemePreference.DARK
        val ocrType = prefs[Keys.OCR_PROVIDER_TYPE]?.let {
            runCatching { OcrProviderType.valueOf(it) }.getOrNull()
        } ?: OcrProviderType.GOOGLE_CLOUD_VISION
        val transType = prefs[Keys.TRANS_PROVIDER_TYPE]?.let {
            runCatching { TranscriptionProviderType.valueOf(it) }.getOrNull()
        } ?: TranscriptionProviderType.OPENAI_AUDIO_COMPAT
        val conflictPolicy = prefs[Keys.DRIVE_CONFLICT_POLICY]?.let {
            runCatching { DriveConflictPolicy.valueOf(it) }.getOrNull()
        } ?: DriveConflictPolicy.ASK_USER

        AppSettings(
            themePreference = theme,
            activeEmbeddingModelId = prefs[Keys.ACTIVE_EMBEDDING_MODEL_ID]?.takeIf { it.isNotBlank() },
            chunkSizeChars = (prefs[Keys.CHUNK_SIZE_CHARS] ?: 900).coerceIn(300, 4000),
            chunkOverlapChars = (prefs[Keys.CHUNK_OVERLAP_CHARS] ?: 160).coerceIn(0, 800),
            ragTopK = (prefs[Keys.RAG_TOP_K] ?: 6).coerceIn(1, 20),
            showSourcesBanner = prefs[Keys.SHOW_SOURCES_BANNER] ?: true,
            externalDisclosureAck = prefs[Keys.EXTERNAL_DISCLOSURE_ACK] ?: false,
            geminiEmbeddingEnabled = prefs[Keys.GEMINI_EMB_ENABLED] ?: false,
            geminiEmbeddingModelId = prefs[Keys.GEMINI_EMB_MODEL_ID]?.takeIf { it.isNotBlank() }
                ?: "gemini-embedding-2-preview",
            geminiEmbeddingDimensions = (prefs[Keys.GEMINI_EMB_DIMENSIONS] ?: 768).coerceIn(128, 3072),
            geminiEmbeddingEncryptedKey = prefs[Keys.GEMINI_EMB_ENC_KEY].orEmpty(),
            geminiEmbeddingLastValidatedAt = prefs[Keys.GEMINI_EMB_VALIDATED_AT],
            ocrProviderType = ocrType,
            ocrEndpointUrl = prefs[Keys.OCR_ENDPOINT_URL] ?: ocrType.defaultEndpoint,
            ocrModelId = prefs[Keys.OCR_MODEL_ID].orEmpty(),
            ocrEncryptedApiKey = prefs[Keys.OCR_ENC_KEY].orEmpty(),
            transcriptionProviderType = transType,
            transcriptionEndpointUrl = prefs[Keys.TRANS_ENDPOINT_URL] ?: transType.defaultEndpoint,
            transcriptionModelId = prefs[Keys.TRANS_MODEL_ID]?.takeIf { it.isNotBlank() } ?: "whisper-1",
            transcriptionLanguage = prefs[Keys.TRANS_LANGUAGE]?.takeIf { it.isNotBlank() } ?: "pt",
            transcriptionEncryptedApiKey = prefs[Keys.TRANS_ENC_KEY].orEmpty(),
            driveAccountEmail = prefs[Keys.DRIVE_EMAIL]?.takeIf { it.isNotBlank() },
            driveAccountName = prefs[Keys.DRIVE_NAME]?.takeIf { it.isNotBlank() },
            driveAccountPhotoUrl = prefs[Keys.DRIVE_PHOTO_URL]?.takeIf { it.isNotBlank() },
            driveEncryptedAccessToken = prefs[Keys.DRIVE_ENC_ACCESS_TOKEN].orEmpty(),
            driveEncryptedRefreshToken = prefs[Keys.DRIVE_ENC_REFRESH_TOKEN].orEmpty(),
            driveTokenExpiresAt = prefs[Keys.DRIVE_TOKEN_EXPIRES_AT] ?: 0L,
            driveOAuthClientId = prefs[Keys.DRIVE_OAUTH_CLIENT_ID].orEmpty(),
            driveSyncEnabled = prefs[Keys.DRIVE_SYNC_ENABLED] ?: false,
            driveConflictPolicy = conflictPolicy,
            driveLastBackupAt = prefs[Keys.DRIVE_LAST_BACKUP_AT],
            driveLastSyncAt = prefs[Keys.DRIVE_LAST_SYNC_AT],
            driveLastSyncStatus = prefs[Keys.DRIVE_LAST_SYNC_STATUS].orEmpty(),
            driveLastSyncError = prefs[Keys.DRIVE_LAST_SYNC_ERROR]?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.dataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setActiveEmbeddingModelId(modelInternalId: String?) {
        context.dataStore.edit {
            if (modelInternalId.isNullOrBlank()) {
                it.remove(Keys.ACTIVE_EMBEDDING_MODEL_ID)
            } else {
                it[Keys.ACTIVE_EMBEDDING_MODEL_ID] = modelInternalId
            }
        }
    }

    suspend fun updateChunkingConfig(chunkSizeChars: Int, overlapChars: Int, ragTopK: Int) {
        context.dataStore.edit {
            it[Keys.CHUNK_SIZE_CHARS] = chunkSizeChars.coerceIn(300, 4000)
            it[Keys.CHUNK_OVERLAP_CHARS] = overlapChars.coerceIn(0, (chunkSizeChars / 2))
            it[Keys.RAG_TOP_K] = ragTopK.coerceIn(1, 20)
        }
    }

    suspend fun dismissSourcesBanner() {
        context.dataStore.edit { it[Keys.SHOW_SOURCES_BANNER] = false }
    }

    suspend fun acknowledgeExternalDisclosure() {
        context.dataStore.edit { it[Keys.EXTERNAL_DISCLOSURE_ACK] = true }
    }

    suspend fun saveGeminiEmbeddingConfig(
        enabled: Boolean,
        modelId: String,
        dimensions: Int,
        encryptedKey: String?,
        validatedAt: Long? = null
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GEMINI_EMB_ENABLED] = enabled
            prefs[Keys.GEMINI_EMB_MODEL_ID] = modelId.trim().ifBlank { "gemini-embedding-2-preview" }
            prefs[Keys.GEMINI_EMB_DIMENSIONS] = dimensions.coerceIn(128, 3072)
            if (encryptedKey != null) {
                if (encryptedKey.isBlank()) prefs.remove(Keys.GEMINI_EMB_ENC_KEY)
                else prefs[Keys.GEMINI_EMB_ENC_KEY] = encryptedKey
            }
            if (validatedAt != null) {
                prefs[Keys.GEMINI_EMB_VALIDATED_AT] = validatedAt
            }
        }
    }

    suspend fun clearGeminiEmbeddingKey() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.GEMINI_EMB_ENC_KEY)
            prefs.remove(Keys.GEMINI_EMB_VALIDATED_AT)
            prefs[Keys.GEMINI_EMB_ENABLED] = false
        }
    }

    suspend fun saveOcrConfig(
        providerType: OcrProviderType,
        endpointUrl: String,
        modelId: String,
        encryptedKey: String?
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OCR_PROVIDER_TYPE] = providerType.name
            prefs[Keys.OCR_ENDPOINT_URL] = endpointUrl.trim()
            prefs[Keys.OCR_MODEL_ID] = modelId.trim()
            if (encryptedKey != null) {
                if (encryptedKey.isBlank()) prefs.remove(Keys.OCR_ENC_KEY)
                else prefs[Keys.OCR_ENC_KEY] = encryptedKey
            }
        }
    }

    suspend fun clearOcrKey() {
        context.dataStore.edit { it.remove(Keys.OCR_ENC_KEY) }
    }

    suspend fun saveTranscriptionConfig(
        providerType: TranscriptionProviderType,
        endpointUrl: String,
        modelId: String,
        language: String,
        encryptedKey: String?
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TRANS_PROVIDER_TYPE] = providerType.name
            prefs[Keys.TRANS_ENDPOINT_URL] = endpointUrl.trim()
            prefs[Keys.TRANS_MODEL_ID] = modelId.trim().ifBlank { "whisper-1" }
            prefs[Keys.TRANS_LANGUAGE] = language.trim().ifBlank { "pt" }
            if (encryptedKey != null) {
                if (encryptedKey.isBlank()) prefs.remove(Keys.TRANS_ENC_KEY)
                else prefs[Keys.TRANS_ENC_KEY] = encryptedKey
            }
        }
    }

    suspend fun clearTranscriptionKey() {
        context.dataStore.edit { it.remove(Keys.TRANS_ENC_KEY) }
    }

    suspend fun saveDriveAccountSession(
        email: String,
        displayName: String?,
        photoUrl: String?,
        encryptedAccessToken: String,
        encryptedRefreshToken: String?,
        expiresAt: Long
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DRIVE_EMAIL] = email
            if (!displayName.isNullOrBlank()) prefs[Keys.DRIVE_NAME] = displayName else prefs.remove(Keys.DRIVE_NAME)
            if (!photoUrl.isNullOrBlank()) prefs[Keys.DRIVE_PHOTO_URL] = photoUrl else prefs.remove(Keys.DRIVE_PHOTO_URL)
            prefs[Keys.DRIVE_ENC_ACCESS_TOKEN] = encryptedAccessToken
            if (!encryptedRefreshToken.isNullOrBlank()) {
                prefs[Keys.DRIVE_ENC_REFRESH_TOKEN] = encryptedRefreshToken
            }
            prefs[Keys.DRIVE_TOKEN_EXPIRES_AT] = expiresAt
            prefs.remove(Keys.DRIVE_LAST_SYNC_ERROR)
        }
    }

    suspend fun clearDriveAccountSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.DRIVE_EMAIL)
            prefs.remove(Keys.DRIVE_NAME)
            prefs.remove(Keys.DRIVE_PHOTO_URL)
            prefs.remove(Keys.DRIVE_ENC_ACCESS_TOKEN)
            prefs.remove(Keys.DRIVE_ENC_REFRESH_TOKEN)
            prefs.remove(Keys.DRIVE_TOKEN_EXPIRES_AT)
            prefs[Keys.DRIVE_SYNC_ENABLED] = false
            prefs.remove(Keys.DRIVE_LAST_SYNC_ERROR)
        }
    }

    suspend fun setDriveOAuthClientId(clientId: String) {
        context.dataStore.edit { prefs ->
            if (clientId.isBlank()) prefs.remove(Keys.DRIVE_OAUTH_CLIENT_ID)
            else prefs[Keys.DRIVE_OAUTH_CLIENT_ID] = clientId.trim()
        }
    }

    suspend fun setDriveSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DRIVE_SYNC_ENABLED] = enabled }
    }

    suspend fun setDriveConflictPolicy(policy: DriveConflictPolicy) {
        context.dataStore.edit { it[Keys.DRIVE_CONFLICT_POLICY] = policy.name }
    }

    suspend fun updateDriveSyncTelemetry(
        lastBackupAt: Long? = null,
        lastSyncAt: Long? = null,
        status: String? = null,
        error: String? = null,
        clearError: Boolean = false
    ) {
        context.dataStore.edit { prefs ->
            if (lastBackupAt != null) prefs[Keys.DRIVE_LAST_BACKUP_AT] = lastBackupAt
            if (lastSyncAt != null) prefs[Keys.DRIVE_LAST_SYNC_AT] = lastSyncAt
            if (status != null) prefs[Keys.DRIVE_LAST_SYNC_STATUS] = status
            if (clearError) {
                prefs.remove(Keys.DRIVE_LAST_SYNC_ERROR)
            } else if (error != null) {
                prefs[Keys.DRIVE_LAST_SYNC_ERROR] = error
            }
        }
    }

    suspend fun clearAllSensitiveCredentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.GEMINI_EMB_ENC_KEY)
            prefs.remove(Keys.GEMINI_EMB_VALIDATED_AT)
            prefs[Keys.GEMINI_EMB_ENABLED] = false
            prefs.remove(Keys.OCR_ENC_KEY)
            prefs.remove(Keys.TRANS_ENC_KEY)
            prefs.remove(Keys.DRIVE_ENC_ACCESS_TOKEN)
            prefs.remove(Keys.DRIVE_ENC_REFRESH_TOKEN)
        }
    }

    /**
     * Exports strictly non-secret UI, chunking, OCR/transcription endpoint metadata,
     * and conflict policy preferences. NEVER includes API keys, OAuth tokens, or passwords.
     */
    suspend fun exportNonSecretPreferencesJson(): JSONObject {
        val current = settingsFlow.first()
        return JSONObject().apply {
            put("themePreference", current.themePreference.name)
            put("chunkSizeChars", current.chunkSizeChars)
            put("chunkOverlapChars", current.chunkOverlapChars)
            put("ragTopK", current.ragTopK)
            put("showSourcesBanner", current.showSourcesBanner)
            put("geminiEmbeddingModelId", current.geminiEmbeddingModelId)
            put("geminiEmbeddingDimensions", current.geminiEmbeddingDimensions)
            put("ocrProviderType", current.ocrProviderType.name)
            put("ocrEndpointUrl", current.ocrEndpointUrl)
            put("ocrModelId", current.ocrModelId)
            put("transcriptionProviderType", current.transcriptionProviderType.name)
            put("transcriptionEndpointUrl", current.transcriptionEndpointUrl)
            put("transcriptionModelId", current.transcriptionModelId)
            put("transcriptionLanguage", current.transcriptionLanguage)
            put("driveConflictPolicy", current.driveConflictPolicy.name)
        }
    }

    suspend fun importNonSecretPreferencesJson(json: JSONObject) {
        context.dataStore.edit { prefs ->
            json.optString("themePreference").takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { ThemePreference.valueOf(raw) }.getOrNull()?.let { prefs[Keys.THEME] = it.name }
            }
            if (json.has("chunkSizeChars")) {
                prefs[Keys.CHUNK_SIZE_CHARS] = json.optInt("chunkSizeChars", 900).coerceIn(300, 4000)
            }
            if (json.has("chunkOverlapChars")) {
                prefs[Keys.CHUNK_OVERLAP_CHARS] = json.optInt("chunkOverlapChars", 160).coerceIn(0, 800)
            }
            if (json.has("ragTopK")) {
                prefs[Keys.RAG_TOP_K] = json.optInt("ragTopK", 6).coerceIn(1, 20)
            }
            if (json.has("showSourcesBanner")) {
                prefs[Keys.SHOW_SOURCES_BANNER] = json.optBoolean("showSourcesBanner", true)
            }
            json.optString("geminiEmbeddingModelId").takeIf { it.isNotBlank() }?.let {
                prefs[Keys.GEMINI_EMB_MODEL_ID] = it
            }
            if (json.has("geminiEmbeddingDimensions")) {
                prefs[Keys.GEMINI_EMB_DIMENSIONS] = json.optInt("geminiEmbeddingDimensions", 768).coerceIn(128, 3072)
            }
            json.optString("ocrProviderType").takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { OcrProviderType.valueOf(raw) }.getOrNull()?.let { prefs[Keys.OCR_PROVIDER_TYPE] = it.name }
            }
            json.optString("ocrEndpointUrl").takeIf { it.isNotBlank() }?.let {
                prefs[Keys.OCR_ENDPOINT_URL] = it
            }
            json.optString("ocrModelId").let {
                if (json.has("ocrModelId")) prefs[Keys.OCR_MODEL_ID] = it
            }
            json.optString("transcriptionProviderType").takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { TranscriptionProviderType.valueOf(raw) }.getOrNull()?.let { prefs[Keys.TRANS_PROVIDER_TYPE] = it.name }
            }
            json.optString("transcriptionEndpointUrl").takeIf { it.isNotBlank() }?.let {
                prefs[Keys.TRANS_ENDPOINT_URL] = it
            }
            json.optString("transcriptionModelId").takeIf { it.isNotBlank() }?.let {
                prefs[Keys.TRANS_MODEL_ID] = it
            }
            json.optString("transcriptionLanguage").takeIf { it.isNotBlank() }?.let {
                prefs[Keys.TRANS_LANGUAGE] = it
            }
            json.optString("driveConflictPolicy").takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { DriveConflictPolicy.valueOf(raw) }.getOrNull()?.let { prefs[Keys.DRIVE_CONFLICT_POLICY] = it.name }
            }
        }
    }
}
