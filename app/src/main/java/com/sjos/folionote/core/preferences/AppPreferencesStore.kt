package com.sjos.folionote.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sjos.folionote.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "folionote_prefs")

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.DARK,
    val activeEmbeddingModelId: String? = null,
    val chunkSizeChars: Int = 900,
    val chunkOverlapChars: Int = 160,
    val ragTopK: Int = 6,
    val showSourcesBanner: Boolean = true,
    val externalDisclosureAck: Boolean = false
)

class AppPreferencesStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_preference")
        val ACTIVE_EMBEDDING_MODEL_ID = stringPreferencesKey("active_embedding_model_id")
        val CHUNK_SIZE_CHARS = intPreferencesKey("chunk_size_chars")
        val CHUNK_OVERLAP_CHARS = intPreferencesKey("chunk_overlap_chars")
        val RAG_TOP_K = intPreferencesKey("rag_top_k")
        val SHOW_SOURCES_BANNER = booleanPreferencesKey("show_sources_banner")
        val EXTERNAL_DISCLOSURE_ACK = booleanPreferencesKey("external_disclosure_ack")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val theme = prefs[Keys.THEME]?.let {
            runCatching { ThemePreference.valueOf(it) }.getOrNull()
        } ?: ThemePreference.DARK
        AppSettings(
            themePreference = theme,
            activeEmbeddingModelId = prefs[Keys.ACTIVE_EMBEDDING_MODEL_ID]?.takeIf { it.isNotBlank() },
            chunkSizeChars = (prefs[Keys.CHUNK_SIZE_CHARS] ?: 900).coerceIn(300, 4000),
            chunkOverlapChars = (prefs[Keys.CHUNK_OVERLAP_CHARS] ?: 160).coerceIn(0, 800),
            ragTopK = (prefs[Keys.RAG_TOP_K] ?: 6).coerceIn(1, 20),
            showSourcesBanner = prefs[Keys.SHOW_SOURCES_BANNER] ?: true,
            externalDisclosureAck = prefs[Keys.EXTERNAL_DISCLOSURE_ACK] ?: false
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
}
