package com.sjos.folionote.engine.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.sjos.folionote.R
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GoogleAccountProfile(
    val email: String,
    val displayName: String? = null,
    val photoUrl: String? = null
)

data class PkceAuthRequest(
    val authorizationUri: Uri,
    val state: String,
    val codeVerifier: String,
    val clientId: String
)

sealed class GooglePlayServicesAuthOutcome {
    data class Authorized(val accessToken: String) : GooglePlayServicesAuthOutcome()
    data class NeedsConsentResolution(val result: AuthorizationResult) : GooglePlayServicesAuthOutcome()
    data class Failed(val message: String, val canFallbackToPkce: Boolean = true) : GooglePlayServicesAuthOutcome()
}

/**
 * Manages Google Account authorization for Google Drive (`drive.appdata`),
 * supporting both Google Play Services AuthorizationClient and OAuth 2.0 PKCE (S256),
 * token refresh, profile loading, Keystore-encrypted persistence, and safe revocation.
 */
class GoogleDriveAuthManager(
    private val context: Context,
    private val preferencesStore: AppPreferencesStore,
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
    private val oauthBaseUrl: String = "https://oauth2.googleapis.com",
    private val authPageBaseUrl: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val userInfoUrl: String = "https://www.googleapis.com/oauth2/v3/userinfo"
) {
    companion object {
        const val REDIRECT_URI = "com.sjos.folionote://oauth2redirect"
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val SCOPE_OPENID = "openid"
        const val SCOPE_EMAIL = "email"
        const val SCOPE_PROFILE = "profile"

        private const val TOKEN_EXPIRY_SKEW_MS = 60_000L
    }

    @Volatile
    private var pendingPkceRequest: PkceAuthRequest? = null

    fun resolveEffectiveOAuthClientId(settings: AppSettings): String {
        val fromSettings = settings.driveOAuthClientId.trim()
        if (fromSettings.isNotBlank()) return fromSettings
        return runCatching {
            context.getString(R.string.google_drive_oauth_client_id).trim()
        }.getOrDefault("")
    }

    fun buildPkceAuthorizationRequest(clientId: String): PkceAuthRequest {
        val cleanClientId = clientId.trim()
        require(cleanClientId.isNotBlank()) {
            "Configure um OAuth Client ID (Android) para iniciar a autenticação PKCE."
        }
        val codeVerifier = generateRandomUrlSafeToken(48)
        val codeChallenge = computeS256CodeChallenge(codeVerifier)
        val state = generateRandomUrlSafeToken(24)

        val scopeParam = listOf(
            SCOPE_OPENID,
            SCOPE_EMAIL,
            SCOPE_PROFILE,
            SCOPE_DRIVE_APPDATA
        ).joinToString(" ")

        val httpUrl = authPageBaseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addQueryParameter("client_id", cleanClientId)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", scopeParam)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .build()

        val uri = Uri.parse(httpUrl.toString())
        return PkceAuthRequest(
            authorizationUri = uri,
            state = state,
            codeVerifier = codeVerifier,
            clientId = cleanClientId
        ).also {
            pendingPkceRequest = it
        }
    }

    fun launchPkceBrowserFlow(activity: Activity, clientId: String): Result<Unit> = runCatching {
        val request = buildPkceAuthorizationRequest(clientId)
        val customTabs = CustomTabsIntent.Builder().build()
        runCatching {
            customTabs.launchUrl(activity, request.authorizationUri)
        }.getOrElse {
            val viewIntent = Intent(Intent.ACTION_VIEW, request.authorizationUri)
            activity.startActivity(viewIntent)
        }
    }

    suspend fun handleOAuthRedirectUri(uri: Uri): Result<GoogleAccountProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val error = uri.getQueryParameter("error")
            if (!error.isNullOrBlank()) {
                val desc = uri.getQueryParameter("error_description").orEmpty()
                throw IllegalStateException(
                    if (desc.isNotBlank()) "Autorização Google negada: $error ($desc)"
                    else "Autorização Google negada: $error"
                )
            }

            val returnedState = uri.getQueryParameter("state").orEmpty()
            val code = uri.getQueryParameter("code").orEmpty()
            val expected = pendingPkceRequest
                ?: throw IllegalStateException("Nenhuma solicitação OAuth PKCE pendente encontrada.")

            if (returnedState.isBlank() || returnedState != expected.state) {
                throw IllegalStateException("Estado OAuth inválido ou expirado (CSRF check falhou).")
            }
            if (code.isBlank()) {
                throw IllegalStateException("Código de autorização OAuth ausente no redirecionamento.")
            }

            pendingPkceRequest = null

            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", expected.clientId)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", expected.codeVerifier)
                .build()

            val tokenUrl = "${oauthBaseUrl.trimEnd('/')}/token"
            val req = Request.Builder()
                .url(tokenUrl)
                .post(formBody)
                .build()

            val client = baseHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val json = client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Falha ao trocar código OAuth (HTTP ${resp.code}): ${extractOAuthError(raw)}")
                }
                JSONObject(raw)
            }

            val accessToken = json.optString("access_token")
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Resposta OAuth sem access_token.")
            val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
            val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)

            saveAccessTokenAndLoadProfile(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = expiresIn
            ).getOrThrow()
        }
    }

    suspend fun requestPlayServicesAuthorization(activity: Activity): GooglePlayServicesAuthOutcome {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(
                    listOf(
                        Scope(SCOPE_DRIVE_APPDATA),
                        Scope(SCOPE_EMAIL),
                        Scope(SCOPE_PROFILE)
                    )
                )
                .build()

            val result = Identity.getAuthorizationClient(activity)
                .authorize(request)
                .awaitTask()

            when {
                result.hasResolution() -> GooglePlayServicesAuthOutcome.NeedsConsentResolution(result)
                !result.accessToken.isNullOrBlank() -> GooglePlayServicesAuthOutcome.Authorized(result.accessToken!!)
                else -> GooglePlayServicesAuthOutcome.Failed(
                    message = "Google Play Services não retornou token de acesso.",
                    canFallbackToPkce = true
                )
            }
        } catch (e: ApiException) {
            GooglePlayServicesAuthOutcome.Failed(
                message = "Falha na autorização Google (${e.statusCode}): ${e.localizedMessage ?: "serviço indisponível"}",
                canFallbackToPkce = true
            )
        } catch (e: Exception) {
            GooglePlayServicesAuthOutcome.Failed(
                message = e.localizedMessage ?: "Não foi possível autorizar via Google Play Services.",
                canFallbackToPkce = true
            )
        }
    }

    suspend fun completePlayServicesAuthorizationResult(
        data: Intent?,
        activity: Activity
    ): Result<GoogleAccountProfile> {
        return runCatching {
            val result = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data)
            val accessToken = result.accessToken
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Autorização Google cancelada ou sem token de acesso.")
            saveAccessTokenAndLoadProfile(
                accessToken = accessToken,
                refreshToken = null,
                expiresInSeconds = 3600L
            ).getOrThrow()
        }
    }

    suspend fun saveAccessTokenAndLoadProfile(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long = 3600L
    ): Result<GoogleAccountProfile> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "Access token não pode estar vazio." }
            val profile = fetchUserProfile(accessToken)
            val encryptedAccess = cryptoManager.encrypt(accessToken)
            val encryptedRefresh = refreshToken?.takeIf { it.isNotBlank() }?.let { cryptoManager.encrypt(it) }
            val expiresAt = System.currentTimeMillis() + (expiresInSeconds.coerceAtLeast(60L) * 1000L)

            preferencesStore.saveDriveAccountSession(
                email = profile.email,
                displayName = profile.displayName,
                photoUrl = profile.photoUrl,
                encryptedAccessToken = encryptedAccess,
                encryptedRefreshToken = encryptedRefresh,
                expiresAt = expiresAt
            )
            profile
        }
    }

    suspend fun refreshProfile(): Result<GoogleAccountProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val accessToken = getValidAccessToken().getOrThrow()
            val profile = fetchUserProfile(accessToken)
            val settings = preferencesStore.settingsFlow.first()
            preferencesStore.saveDriveAccountSession(
                email = profile.email,
                displayName = profile.displayName,
                photoUrl = profile.photoUrl,
                encryptedAccessToken = settings.driveEncryptedAccessToken,
                encryptedRefreshToken = settings.driveEncryptedRefreshToken.takeIf { it.isNotBlank() },
                expiresAt = settings.driveTokenExpiresAt
            )
            profile
        }
    }

    suspend fun getValidAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = preferencesStore.settingsFlow.first()
            val encryptedAccess = settings.driveEncryptedAccessToken
            val encryptedRefresh = settings.driveEncryptedRefreshToken
            val now = System.currentTimeMillis()

            if (encryptedAccess.isNotBlank() && settings.driveTokenExpiresAt > now + TOKEN_EXPIRY_SKEW_MS) {
                val plainAccess = cryptoManager.decrypt(encryptedAccess)
                if (plainAccess.isNotBlank()) return@runCatching plainAccess
            }

            if (encryptedRefresh.isNotBlank()) {
                val plainRefresh = cryptoManager.decrypt(encryptedRefresh)
                if (plainRefresh.isNotBlank()) {
                    return@runCatching refreshAccessToken(settings, plainRefresh)
                }
            }

            if (encryptedAccess.isNotBlank() && settings.driveTokenExpiresAt == 0L) {
                val plainAccess = cryptoManager.decrypt(encryptedAccess)
                if (plainAccess.isNotBlank()) return@runCatching plainAccess
            }

            throw IllegalStateException("Sessão Google Drive expirada ou não conectada. Reconecte sua conta nas Configurações.")
        }
    }

    suspend fun disconnectAndRevoke() = withContext(Dispatchers.IO) {
        val settings = preferencesStore.settingsFlow.first()
        val tokenToRevoke = when {
            settings.driveEncryptedRefreshToken.isNotBlank() ->
                runCatching { cryptoManager.decrypt(settings.driveEncryptedRefreshToken) }.getOrDefault("")
            settings.driveEncryptedAccessToken.isNotBlank() ->
                runCatching { cryptoManager.decrypt(settings.driveEncryptedAccessToken) }.getOrDefault("")
            else -> ""
        }

        if (tokenToRevoke.isNotBlank()) {
            runCatching {
                val revokeUrl = "${oauthBaseUrl.trimEnd('/')}/revoke"
                val body = FormBody.Builder()
                    .add("token", tokenToRevoke)
                    .build()
                val req = Request.Builder()
                    .url(revokeUrl)
                    .post(body)
                    .build()
                val client = baseHttpClient.newBuilder()
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().close()
            }
        }

        pendingPkceRequest = null
        preferencesStore.clearDriveAccountSession()
    }

    private suspend fun refreshAccessToken(settings: AppSettings, refreshToken: String): String {
        val clientId = resolveEffectiveOAuthClientId(settings)
        val formBuilder = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
        if (clientId.isNotBlank()) {
            formBuilder.add("client_id", clientId)
        }

        val tokenUrl = "${oauthBaseUrl.trimEnd('/')}/token"
        val req = Request.Builder()
            .url(tokenUrl)
            .post(formBuilder.build())
            .build()

        val client = baseHttpClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val json = client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Falha ao renovar token Google (HTTP ${resp.code}): ${extractOAuthError(raw)}")
            }
            JSONObject(raw)
        }

        val newAccess = json.optString("access_token")
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Resposta de renovação OAuth sem access_token.")
        val newRefresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

        val encryptedAccess = cryptoManager.encrypt(newAccess)
        val encryptedRefresh = newRefresh?.let { cryptoManager.encrypt(it) }
            ?: settings.driveEncryptedRefreshToken.takeIf { it.isNotBlank() }

        preferencesStore.saveDriveAccountSession(
            email = settings.driveAccountEmail ?: "",
            displayName = settings.driveAccountName,
            photoUrl = settings.driveAccountPhotoUrl,
            encryptedAccessToken = encryptedAccess,
            encryptedRefreshToken = encryptedRefresh,
            expiresAt = expiresAt
        )
        return newAccess
    }

    private fun fetchUserProfile(accessToken: String): GoogleAccountProfile {
        val client = baseHttpClient.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(userInfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Erro ao obter perfil Google (HTTP ${resp.code}): ${extractOAuthError(raw)}")
            }
            val obj = JSONObject(raw)
            val email = obj.optString("email").trim()
                .ifBlank { throw IllegalStateException("Perfil Google não retornou email.") }
            val name = obj.optString("name").trim().takeIf { it.isNotBlank() }
            val picture = obj.optString("picture").trim().takeIf { it.isNotBlank() }
            GoogleAccountProfile(
                email = email,
                displayName = name,
                photoUrl = picture
            )
        }
    }

    private fun generateRandomUrlSafeToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun computeS256CodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun extractOAuthError(raw: String): String {
        return runCatching {
            val obj = JSONObject(raw)
            val desc = obj.optString("error_description")
            val err = obj.optString("error")
            val msg = obj.optJSONObject("error")?.optString("message").orEmpty()
            when {
                desc.isNotBlank() -> desc
                msg.isNotBlank() -> msg
                err.isNotBlank() -> err
                else -> raw.take(160)
            }
        }.getOrElse { raw.take(160) }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
}