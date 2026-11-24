package com.sjos.folionote.engine.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.sjos.folionote.R
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class GoogleUserProfile(
    val email: String,
    val displayName: String?,
    val photoUrl: String?
)

sealed interface GooglePlayServicesAuthOutcome {
    data class Authorized(val accessToken: String) : GooglePlayServicesAuthOutcome
    data class NeedsConsentResolution(val result: AuthorizationResult) : GooglePlayServicesAuthOutcome
    data class Failed(val message: String, val canFallbackToPkce: Boolean) : GooglePlayServicesAuthOutcome
}

data class PkceSessionRequest(
    val authorizationUri: Uri,
    val codeVerifier: String,
    val state: String,
    val clientId: String
)

/**
 * Manages real Google Account OAuth inside the Android app:
 * 1) Google Play Services Identity AuthorizationClient (official in-app Google consent)
 * 2) OAuth 2.0 Authorization Code + PKCE (S256) fallback via Chrome Custom Tabs & deep link
 *    `com.sjos.folionote://oauth2redirect` (zero hardcoded client secret).
 *
 * Completely independent from Gemini Embedding 2 user API keys.
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
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val SCOPE_OPENID = "openid"
        const val SCOPE_EMAIL = "email"
        const val SCOPE_PROFILE = "profile"
        const val REDIRECT_URI = "com.sjos.folionote://oauth2redirect"
    }

    @Volatile
    private var pendingPkceRequest: PkceSessionRequest? = null

    fun resolveEffectiveOAuthClientId(settings: AppSettings): String {
        val userClientId = settings.driveOAuthClientId.trim()
        if (userClientId.isNotBlank()) return userClientId
        return runCatching { context.getString(R.string.google_drive_oauth_client_id).trim() }.getOrDefault("")
    }

    suspend fun requestPlayServicesAuthorization(activity: Activity): GooglePlayServicesAuthOutcome {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(
                    listOf(
                        Scope(SCOPE_DRIVE_APPDATA),
                        Scope(SCOPE_DRIVE_FILE),
                        Scope(SCOPE_OPENID),
                        Scope(SCOPE_EMAIL),
                        Scope(SCOPE_PROFILE)
                    )
                )
                .build()

            val task = Identity.getAuthorizationClient(activity).authorize(request)
            val result = suspendCancellableCoroutine { cont ->
                task.addOnSuccessListener { res ->
                    if (cont.isActive) cont.resume(res)
                }.addOnFailureListener { err ->
                    if (cont.isActive) cont.resumeWithException(err)
                }.addOnCanceledListener {
                    cont.cancel()
                }
            }

            when {
                result.hasResolution() -> GooglePlayServicesAuthOutcome.NeedsConsentResolution(result)
                !result.accessToken.isNullOrBlank() -> GooglePlayServicesAuthOutcome.Authorized(result.accessToken!!)
                else -> GooglePlayServicesAuthOutcome.Failed(
                    "O Google Play Services não retornou token de acesso.",
                    canFallbackToPkce = true
                )
            }
        } catch (t: Throwable) {
            GooglePlayServicesAuthOutcome.Failed(
                message = t.localizedMessage ?: "Falha na autorização Google Play Services.",
                canFallbackToPkce = true
            )
        }
    }

    suspend fun completePlayServicesAuthorizationResult(data: Intent?, activity: Activity): Result<GoogleUserProfile> {
        return try {
            val authResult = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
            val accessToken = authResult.accessToken
                ?: return Result.failure(IllegalStateException("Token de acesso não concedido pelo usuário."))
            saveAccessTokenAndLoadProfile(accessToken = accessToken, refreshToken = null, expiresInSeconds = 3500L)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun buildPkceAuthorizationRequest(clientId: String): PkceSessionRequest {
        val cleanClientId = clientId.trim()
        require(cleanClientId.isNotBlank()) {
            "Informe o OAuth Client ID (Android) nas configurações ou configure google_drive_oauth_client_id."
        }
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val codeVerifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val stateBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val state = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val uri = Uri.parse(authPageBaseUrl).buildUpon()
            .appendQueryParameter("client_id", cleanClientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "$SCOPE_DRIVE_APPDATA $SCOPE_DRIVE_FILE $SCOPE_OPENID $SCOPE_EMAIL $SCOPE_PROFILE")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent select_account")
            .build()

        return PkceSessionRequest(
            authorizationUri = uri,
            codeVerifier = codeVerifier,
            state = state,
            clientId = cleanClientId
        ).also { pendingPkceRequest = it }
    }

    fun launchPkceBrowserFlow(activity: Activity, clientId: String): Result<PkceSessionRequest> = runCatching {
        val req = buildPkceAuthorizationRequest(clientId)
        val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
        tabs.launchUrl(activity, req.authorizationUri)
        req
    }

    suspend fun handleOAuthRedirectUri(redirectUri: Uri): Result<GoogleUserProfile> {
        val error = redirectUri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val desc = redirectUri.getQueryParameter("error_description") ?: error
            pendingPkceRequest = null
            return Result.failure(IllegalStateException("Autorização Google cancelada/recusada: $desc"))
        }

        val code = redirectUri.getQueryParameter("code")
        val state = redirectUri.getQueryParameter("state")
        val pending = pendingPkceRequest
            ?: return Result.failure(IllegalStateException("Nenhuma sessão PKCE pendente para o redirecionamento."))

        if (state.isNullOrBlank() || state != pending.state) {
            pendingPkceRequest = null
            return Result.failure(IllegalStateException("Estado OAuth inválido (proteção CSRF)."))
        }
        if (code.isNullOrBlank()) {
            pendingPkceRequest = null
            return Result.failure(IllegalStateException("Código de autorização ausente no retorno do Google."))
        }

        pendingPkceRequest = null
        return exchangeAuthorizationCode(
            code = code,
            codeVerifier = pending.codeVerifier,
            clientId = pending.clientId
        )
    }

    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        clientId: String,
        redirectUri: String = REDIRECT_URI
    ): Result<GoogleUserProfile> = withContext(Dispatchers.IO) {
        val client = baseHttpClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .build()

        val req = Request.Builder()
            .url("${oauthBaseUrl.trimEnd('/')}/token")
            .post(form)
            .build()

        val tokenResult = runCatching {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Falha na troca de token OAuth HTTP ${resp.code}: ${raw.take(200)}")
                }
                val json = JSONObject(raw)
                val access = json.optString("access_token")
                require(access.isNotBlank()) { "Resposta OAuth sem access_token." }
                val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 3600L)
                Triple(access, refresh, expiresIn)
            }
        }

        tokenResult.fold(
            onSuccess = { (access, refresh, expiresIn) ->
                saveAccessTokenAndLoadProfile(access, refresh, expiresIn)
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun saveAccessTokenAndLoadProfile(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long = 3500L
    ): Result<GoogleUserProfile> {
        val profileRes = fetchUserInfo(accessToken)
        val profile = profileRes.getOrElse { err ->
            return Result.failure(err)
        }
        val encAccess = cryptoManager.encrypt(accessToken)
        val encRefresh = refreshToken?.takeIf { it.isNotBlank() }?.let { cryptoManager.encrypt(it) }
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds.coerceAtLeast(60L) * 1000L)
        preferencesStore.saveDriveAccountSession(
            email = profile.email,
            displayName = profile.displayName,
            photoUrl = profile.photoUrl,
            encryptedAccessToken = encAccess,
            encryptedRefreshToken = encRefresh,
            expiresAt = expiresAt
        )
        return Result.success(profile)
    }

    suspend fun fetchUserInfo(accessToken: String): Result<GoogleUserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val client = baseHttpClient.newBuilder().readTimeout(25, TimeUnit.SECONDS).build()
            val req = Request.Builder()
                .url(userInfoUrl)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Erro ao carregar perfil Google HTTP ${resp.code}: ${raw.take(180)}")
                }
                val obj = JSONObject(raw)
                val email = obj.optString("email").trim()
                require(email.isNotBlank()) { "Perfil Google sem e-mail." }
                GoogleUserProfile(
                    email = email,
                    displayName = obj.optString("name").trim().takeIf { it.isNotBlank() },
                    photoUrl = obj.optString("picture").trim().takeIf { it.isNotBlank() }
                )
            }
        }
    }

    suspend fun refreshProfile(): Result<GoogleUserProfile> {
        val token = getValidAccessToken().getOrElse { return Result.failure(it) }
        val profile = fetchUserInfo(token).getOrElse { return Result.failure(it) }
        val current = preferencesStore.settingsFlow.first()
        preferencesStore.saveDriveAccountSession(
            email = profile.email,
            displayName = profile.displayName,
            photoUrl = profile.photoUrl,
            encryptedAccessToken = current.driveEncryptedAccessToken,
            encryptedRefreshToken = null,
            expiresAt = current.driveTokenExpiresAt
        )
        return Result.success(profile)
    }

    suspend fun getValidAccessToken(): Result<String> {
        val settings = preferencesStore.settingsFlow.first()
        val access = cryptoManager.decrypt(settings.driveEncryptedAccessToken).trim()
        val refresh = cryptoManager.decrypt(settings.driveEncryptedRefreshToken).trim()
        val now = System.currentTimeMillis()
        val isStillValid = access.isNotBlank() && (settings.driveTokenExpiresAt == 0L || settings.driveTokenExpiresAt - 60_000L > now)
        if (isStillValid) return Result.success(access)

        if (refresh.isNotBlank()) {
            val clientId = resolveEffectiveOAuthClientId(settings)
            if (clientId.isNotBlank()) {
                val refreshed = refreshAccessToken(refreshToken = refresh, clientId = clientId)
                if (refreshed.isSuccess) return refreshed
            }
        }

        return if (access.isNotBlank()) {
            Result.success(access)
        } else {
            Result.failure(IllegalStateException("Sessão Google expirada ou não conectada. Reconecte sua conta nas Configurações."))
        }
    }

    suspend fun refreshAccessToken(
        refreshToken: String,
        clientId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val client = baseHttpClient.newBuilder().readTimeout(25, TimeUnit.SECONDS).build()
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder()
            .url("${oauthBaseUrl.trimEnd('/')}/token")
            .post(form)
            .build()

        val result = runCatching {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Falha ao renovar token Google HTTP ${resp.code}: ${raw.take(180)}")
                }
                val json = JSONObject(raw)
                val newAccess = json.optString("access_token")
                require(newAccess.isNotBlank()) { "Refresh sem access_token." }
                val expiresIn = json.optLong("expires_in", 3600L)
                newAccess to expiresIn
            }
        }
        result.onSuccess { (newAccess, expiresIn) ->
            val current = preferencesStore.settingsFlow.first()
            preferencesStore.saveDriveAccountSession(
                email = current.driveAccountEmail.orEmpty(),
                displayName = current.driveAccountName,
                photoUrl = current.driveAccountPhotoUrl,
                encryptedAccessToken = cryptoManager.encrypt(newAccess),
                encryptedRefreshToken = null,
                expiresAt = System.currentTimeMillis() + (expiresIn.coerceAtLeast(60L) * 1000L)
            )
        }.map { it.first }
    }

    suspend fun disconnectAndRevoke(): Result<Unit> = withContext(Dispatchers.IO) {
        val settings = preferencesStore.settingsFlow.first()
        val access = cryptoManager.decrypt(settings.driveEncryptedAccessToken).trim()
        val refresh = cryptoManager.decrypt(settings.driveEncryptedRefreshToken).trim()
        val tokenToRevoke = refresh.ifBlank { access }

        if (tokenToRevoke.isNotBlank()) {
            runCatching {
                val client = baseHttpClient.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
                val form = FormBody.Builder().add("token", tokenToRevoke).build()
                val req = Request.Builder()
                    .url("${oauthBaseUrl.trimEnd('/')}/revoke")
                    .post(form)
                    .build()
                client.newCall(req).execute().close()
            }
        }
        preferencesStore.clearDriveAccountSession()
        Result.success(Unit)
    }
}
