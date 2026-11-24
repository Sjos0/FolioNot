package com.sjos.folionote.engine.drive

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.sjos.folionote.R
import com.sjos.folionote.core.preferences.AppPreferencesStore
import com.sjos.folionote.core.preferences.AppSettings
import com.sjos.folionote.core.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GoogleAccountProfile(
    val email: String,
    val displayName: String? = null,
    val photoUrl: String? = null
)

sealed class CredentialAuthResult {
    data class Authenticated(val profile: GoogleAccountProfile, val idToken: String) : CredentialAuthResult()
    data object Cancelled : CredentialAuthResult()
    data class Unavailable(val reason: String) : CredentialAuthResult()
    data class Failed(val message: String) : CredentialAuthResult()
}

sealed class DriveAuthResult {
    data class Authorized(
        val accessToken: String,
        val expiresInSeconds: Long = 3600L
    ) : DriveAuthResult()

    data class NeedsResolution(val intentSenderRequest: IntentSenderRequest) : DriveAuthResult()
    data object Cancelled : DriveAuthResult()
    data class NetworkError(val exception: IOException) : DriveAuthResult()
    data class Unavailable(val reason: String) : DriveAuthResult()
    data class Failed(val message: String) : DriveAuthResult()
}

sealed class GoogleConnectOutcome {
    data class Connected(val profile: GoogleAccountProfile) : GoogleConnectOutcome()
    data class NeedsConsentResolution(val intentSenderRequest: IntentSenderRequest) : GoogleConnectOutcome()
    data object Cancelled : GoogleConnectOutcome()
    data class Failed(val message: String) : GoogleConnectOutcome()
}

sealed class DriveAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConnected(message: String = "Nenhuma conta Google conectada.") : DriveAuthException(message)
    class ReauthRequired(
        message: String = "Autorização Google expirada ou revogada. Reconecte sua conta.",
        val resolutionIntent: IntentSenderRequest? = null
    ) : DriveAuthException(message)
}

interface GoogleCredentialAuthenticator {
    suspend fun authenticate(activity: Activity, serverClientId: String): CredentialAuthResult
    suspend fun clearSession()
}

interface DriveAuthorizationGateway {
    suspend fun authorize(
        context: Context,
        scopes: List<String>,
        accountEmail: String?
    ): DriveAuthResult

    fun extractAuthorizationFromIntent(context: Context, data: Intent?): DriveAuthResult

    suspend fun revokeAccess(context: Context, scopes: List<String>, accountEmail: String?)
}

class CredentialManagerGoogleAuthenticator(
    private val appContext: Context
) : GoogleCredentialAuthenticator {

    override suspend fun authenticate(activity: Activity, serverClientId: String): CredentialAuthResult {
        if (serverClientId.isBlank()) {
            return CredentialAuthResult.Unavailable("Web Client ID do desenvolvedor não configurado.")
        }
        val credentialManager = CredentialManager.create(activity)
        val nonce = generateNonce()

        // Try authorized/returning accounts first via GetGoogleIdOption, then explicit Sign-In button sheet.
        val authorizedOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .setServerClientId(serverClientId)
            .setNonce(nonce)
            .build()

        val firstAttempt = executeCredentialRequest(
            credentialManager = credentialManager,
            activity = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(authorizedOption).build()
        )
        if (firstAttempt !is CredentialAuthResult.Unavailable) {
            return firstAttempt
        }

        val signInButtonOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(generateNonce())
            .build()

        return executeCredentialRequest(
            credentialManager = credentialManager,
            activity = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(signInButtonOption).build()
        )
    }

    override suspend fun clearSession() {
        runCatching {
            CredentialManager.create(appContext)
                .clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private suspend fun executeCredentialRequest(
        credentialManager: CredentialManager,
        activity: Activity,
        request: GetCredentialRequest
    ): CredentialAuthResult {
        return try {
            val response = credentialManager.getCredential(
                context = activity,
                request = request
            )
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleId = GoogleIdTokenCredential.createFrom(credential.data)
                val email = googleId.id.trim()
                if (email.isBlank()) {
                    CredentialAuthResult.Failed("Credencial Google retornada sem e-mail.")
                } else {
                    CredentialAuthResult.Authenticated(
                        profile = GoogleAccountProfile(
                            email = email,
                            displayName = googleId.displayName?.trim()?.takeIf { it.isNotBlank() },
                            photoUrl = googleId.profilePictureUri?.toString()?.takeIf { it.isNotBlank() }
                        ),
                        idToken = googleId.idToken
                    )
                }
            } else {
                CredentialAuthResult.Failed("Tipo de credencial inesperado retornado pelo Credential Manager.")
            }
        } catch (_: GetCredentialCancellationException) {
            CredentialAuthResult.Cancelled
        } catch (e: NoCredentialException) {
            CredentialAuthResult.Unavailable(e.localizedMessage ?: "Nenhuma conta Google disponível no dispositivo.")
        } catch (e: GoogleIdTokenParsingException) {
            CredentialAuthResult.Failed(e.localizedMessage ?: "Falha ao processar credencial Google.")
        } catch (e: GetCredentialException) {
            CredentialAuthResult.Unavailable(e.localizedMessage ?: "Credential Manager indisponível.")
        } catch (e: Exception) {
            CredentialAuthResult.Failed(e.localizedMessage ?: "Erro inesperado ao autenticar conta Google.")
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

class PlayServicesDriveAuthorizationGateway : DriveAuthorizationGateway {

    override suspend fun authorize(
        context: Context,
        scopes: List<String>,
        accountEmail: String?
    ): DriveAuthResult {
        return try {
            val builder = AuthorizationRequest.builder()
                .setRequestedScopes(scopes.map { Scope(it) })
            if (!accountEmail.isNullOrBlank()) {
                builder.setAccount(Account(accountEmail, "com.google"))
            }
            val result = Identity.getAuthorizationClient(context)
                .authorize(builder.build())
                .awaitTask()

            when {
                result.hasResolution() -> {
                    val pending = result.pendingIntent
                    if (pending != null) {
                        DriveAuthResult.NeedsResolution(
                            IntentSenderRequest.Builder(pending.intentSender).build()
                        )
                    } else {
                        DriveAuthResult.Failed("Consentimento do Google Drive indisponível.")
                    }
                }
                !result.accessToken.isNullOrBlank() -> {
                    DriveAuthResult.Authorized(accessToken = result.accessToken!!, expiresInSeconds = 3600L)
                }
                else -> {
                    DriveAuthResult.Failed("Google Play Services não retornou token de acesso.")
                }
            }
        } catch (e: ApiException) {
            when (e.statusCode) {
                CommonStatusCodes.CANCELED -> DriveAuthResult.Cancelled
                CommonStatusCodes.NETWORK_ERROR -> DriveAuthResult.NetworkError(
                    IOException("Erro de rede ao comunicar com Google Play Services.", e)
                )
                CommonStatusCodes.API_NOT_CONNECTED,
                CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED,
                CommonStatusCodes.DEVELOPER_ERROR -> DriveAuthResult.Unavailable(
                    "Serviço de autorização Google indisponível (${e.statusCode})."
                )
                else -> DriveAuthResult.Failed(
                    "Falha na autorização Google (${e.statusCode}): ${e.localizedMessage ?: "erro desconhecido"}"
                )
            }
        } catch (e: IOException) {
            DriveAuthResult.NetworkError(e)
        } catch (e: Exception) {
            DriveAuthResult.Unavailable(
                e.localizedMessage ?: "Não foi possível autorizar acesso ao Google Drive."
            )
        }
    }

    override fun extractAuthorizationFromIntent(context: Context, data: Intent?): DriveAuthResult {
        if (data == null) return DriveAuthResult.Cancelled
        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            val token = result.accessToken?.takeIf { it.isNotBlank() }
            if (token != null) {
                DriveAuthResult.Authorized(accessToken = token, expiresInSeconds = 3600L)
            } else {
                DriveAuthResult.Cancelled
            }
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.CANCELED) {
                DriveAuthResult.Cancelled
            } else {
                DriveAuthResult.Failed("Autorização Google falhou (${e.statusCode}).")
            }
        } catch (e: Exception) {
            DriveAuthResult.Failed(e.localizedMessage ?: "Falha ao concluir autorização Google.")
        }
    }

    override suspend fun revokeAccess(context: Context, scopes: List<String>, accountEmail: String?) {
        runCatching {
            Identity.getSignInClient(context)
                .signOut()
                .awaitTask()
        }
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

/**
 * Manages native Google Account sign-in (Jetpack Credential Manager) and Google Drive
 * authorization (Google Identity AuthorizationClient), with Keystore-encrypted token storage,
 * silent re-authorization on expiry, controlled WorkManager pause on revocation, and scope isolation.
 */
class GoogleDriveAuthManager(
    private val context: Context,
    private val preferencesStore: AppPreferencesStore,
    private val cryptoManager: KeystoreCryptoManager,
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
    private val oauthBaseUrl: String = "https://oauth2.googleapis.com",
    private val userInfoUrl: String = "https://www.googleapis.com/oauth2/v3/userinfo",
    private val credentialAuthenticator: GoogleCredentialAuthenticator = CredentialManagerGoogleAuthenticator(context),
    private val authorizationGateway: DriveAuthorizationGateway = PlayServicesDriveAuthorizationGateway(),
    private val serverClientIdProvider: () -> String = {
        runCatching { context.getString(R.string.google_web_oauth_client_id).trim() }.getOrDefault("")
    },
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        const val SCOPE_OPENID = "openid"
        const val SCOPE_EMAIL = "email"
        const val SCOPE_PROFILE = "profile"

        val BACKUP_SCOPES = listOf(
            SCOPE_DRIVE_APPDATA,
            SCOPE_OPENID,
            SCOPE_EMAIL,
            SCOPE_PROFILE
        )

        val IMPORT_SCOPES = listOf(SCOPE_DRIVE_READONLY)

        const val TOKEN_EXPIRY_SKEW_MS = 60_000L
    }

    @Volatile
    private var pendingCredentialProfile: GoogleAccountProfile? = null

    suspend fun connectGoogleAccount(
        activity: Activity,
        switchAccount: Boolean = false
    ): GoogleConnectOutcome {
        if (switchAccount) {
            credentialAuthenticator.clearSession()
        }
        pendingCredentialProfile = null

        val serverClientId = serverClientIdProvider().trim()
        if (serverClientId.isNotBlank()) {
            when (val credResult = credentialAuthenticator.authenticate(activity, serverClientId)) {
                is CredentialAuthResult.Authenticated -> {
                    pendingCredentialProfile = credResult.profile
                }
                CredentialAuthResult.Cancelled -> return GoogleConnectOutcome.Cancelled
                is CredentialAuthResult.Failed -> return GoogleConnectOutcome.Failed(credResult.message)
                is CredentialAuthResult.Unavailable -> {
                    // Proceed directly to AuthorizationClient (uses device Google accounts + package/SHA-1).
                }
            }
        }

        val targetEmail = pendingCredentialProfile?.email
        return when (val authRes = authorizationGateway.authorize(activity, BACKUP_SCOPES, targetEmail)) {
            is DriveAuthResult.Authorized -> {
                val saved = saveAccessTokenAndLoadProfile(
                    accessToken = authRes.accessToken,
                    refreshToken = null,
                    expiresInSeconds = authRes.expiresInSeconds,
                    fallbackProfile = pendingCredentialProfile
                )
                pendingCredentialProfile = null
                saved.fold(
                    onSuccess = { GoogleConnectOutcome.Connected(it) },
                    onFailure = { GoogleConnectOutcome.Failed(it.message ?: "Erro ao carregar perfil Google.") }
                )
            }
            is DriveAuthResult.NeedsResolution -> {
                GoogleConnectOutcome.NeedsConsentResolution(authRes.intentSenderRequest)
            }
            DriveAuthResult.Cancelled -> {
                pendingCredentialProfile = null
                GoogleConnectOutcome.Cancelled
            }
            is DriveAuthResult.NetworkError -> {
                pendingCredentialProfile = null
                GoogleConnectOutcome.Failed("Sem conexão com a internet para conectar sua conta Google.")
            }
            is DriveAuthResult.Unavailable -> {
                pendingCredentialProfile = null
                GoogleConnectOutcome.Failed(authRes.reason)
            }
            is DriveAuthResult.Failed -> {
                pendingCredentialProfile = null
                GoogleConnectOutcome.Failed(authRes.message)
            }
        }
    }

    suspend fun completeAuthorizationResult(
        activity: Activity,
        data: Intent?
    ): Result<GoogleAccountProfile> {
        val extracted = authorizationGateway.extractAuthorizationFromIntent(activity, data)
        val fallback = pendingCredentialProfile
        pendingCredentialProfile = null

        return when (extracted) {
            is DriveAuthResult.Authorized -> {
                saveAccessTokenAndLoadProfile(
                    accessToken = extracted.accessToken,
                    refreshToken = null,
                    expiresInSeconds = extracted.expiresInSeconds,
                    fallbackProfile = fallback
                )
            }
            DriveAuthResult.Cancelled -> {
                Result.failure(IllegalStateException("Autorização Google cancelada pelo usuário."))
            }
            is DriveAuthResult.NetworkError -> Result.failure(extracted.exception)
            is DriveAuthResult.Unavailable -> Result.failure(IllegalStateException(extracted.reason))
            is DriveAuthResult.Failed -> Result.failure(IllegalStateException(extracted.message))
            is DriveAuthResult.NeedsResolution -> Result.failure(
                DriveAuthException.ReauthRequired("Consentimento adicional necessário.", extracted.intentSenderRequest)
            )
        }
    }

    /**
     * Requests incremental read-only scope (`drive.readonly`) on demand for importing
     * user-selected documents from Google Drive into a notebook. Never requested during backup setup.
     */
    suspend fun authorizeDriveFileImport(activity: Activity): DriveAuthResult {
        val currentEmail = preferencesStore.settingsFlow.first().driveAccountEmail
        return authorizationGateway.authorize(activity, IMPORT_SCOPES, currentEmail)
    }

    fun extractDriveImportAuthorizationResult(activity: Activity, data: Intent?): DriveAuthResult {
        return authorizationGateway.extractAuthorizationFromIntent(activity, data)
    }

    suspend fun saveAccessTokenAndLoadProfile(
        accessToken: String,
        refreshToken: String? = null,
        expiresInSeconds: Long = 3600L,
        fallbackProfile: GoogleAccountProfile? = null
    ): Result<GoogleAccountProfile> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "Access token não pode estar vazio." }
            val profile = runCatching { fetchUserProfile(accessToken) }.getOrElse { err ->
                if (fallbackProfile != null && fallbackProfile.email.isNotBlank()) {
                    fallbackProfile
                } else {
                    throw err
                }
            }

            val mergedProfile = GoogleAccountProfile(
                email = profile.email,
                displayName = profile.displayName ?: fallbackProfile?.displayName,
                photoUrl = profile.photoUrl ?: fallbackProfile?.photoUrl
            )

            val encryptedAccess = cryptoManager.encrypt(accessToken)
            val encryptedRefresh = refreshToken?.takeIf { it.isNotBlank() }?.let { cryptoManager.encrypt(it) }
            val expiresAt = nowProvider() + (expiresInSeconds.coerceAtLeast(60L) * 1000L)

            preferencesStore.saveDriveAccountSession(
                email = mergedProfile.email,
                displayName = mergedProfile.displayName,
                photoUrl = mergedProfile.photoUrl,
                encryptedAccessToken = encryptedAccess,
                encryptedRefreshToken = encryptedRefresh,
                expiresAt = expiresAt
            )
            mergedProfile
        }
    }

    suspend fun refreshProfile(): Result<GoogleAccountProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val accessToken = getValidAccessToken().getOrThrow()
            val profile = fetchUserProfile(accessToken)
            // Always read the latest settings AFTER getValidAccessToken() so renewed credentials are preserved.
            val latestSettings = preferencesStore.settingsFlow.first()
            preferencesStore.saveDriveAccountSession(
                email = profile.email,
                displayName = profile.displayName,
                photoUrl = profile.photoUrl,
                encryptedAccessToken = latestSettings.driveEncryptedAccessToken,
                encryptedRefreshToken = latestSettings.driveEncryptedRefreshToken.takeIf { it.isNotBlank() },
                expiresAt = latestSettings.driveTokenExpiresAt
            )
            profile
        }
    }

    /**
     * Returns a non-expired access token.
     * - Never returns an expired, blank, or undecryptable token.
     * - When expired (or forceRefresh=true), attempts silent renewal via AuthorizationClient first,
     *   preserving existing credentials, or flags `driveNeedsReauth` if user interaction is required.
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val settings = preferencesStore.settingsFlow.first()
        if (!settings.isGoogleDriveConnected) {
            return@withContext Result.failure(DriveAuthException.NotConnected())
        }

        val now = nowProvider()
        val encryptedAccess = settings.driveEncryptedAccessToken
        val hasUnexpiredTimestamp = settings.driveTokenExpiresAt > now + TOKEN_EXPIRY_SKEW_MS

        if (!forceRefresh && !settings.driveNeedsReauth && encryptedAccess.isNotBlank() && hasUnexpiredTimestamp) {
            val plainAccess = cryptoManager.decrypt(encryptedAccess)
            if (plainAccess.isNotBlank()) {
                return@withContext Result.success(plainAccess)
            }
        }

        // Token is missing, corrupted, expired, or forceRefresh requested -> attempt silent renewal.
        when (val silent = authorizationGateway.authorize(context, BACKUP_SCOPES, settings.driveAccountEmail)) {
            is DriveAuthResult.Authorized -> {
                val encryptedNew = cryptoManager.encrypt(silent.accessToken)
                val newExpiresAt = nowProvider() + (silent.expiresInSeconds.coerceAtLeast(60L) * 1000L)
                preferencesStore.saveDriveAccountSession(
                    email = settings.driveAccountEmail ?: "",
                    displayName = settings.driveAccountName,
                    photoUrl = settings.driveAccountPhotoUrl,
                    encryptedAccessToken = encryptedNew,
                    encryptedRefreshToken = settings.driveEncryptedRefreshToken.takeIf { it.isNotBlank() },
                    expiresAt = newExpiresAt
                )
                return@withContext Result.success(silent.accessToken)
            }
            is DriveAuthResult.NetworkError -> {
                return@withContext Result.failure(silent.exception)
            }
            is DriveAuthResult.NeedsResolution -> {
                // Only fall back to OAuth refresh token if explicitly present in encrypted store.
                val refreshAttempt = tryRefreshWithStoredRefreshToken(settings)
                if (refreshAttempt != null) return@withContext refreshAttempt

                val msg = "Autorização do Google Drive expirada ou revogada. Reconecte sua conta nas Configurações."
                preferencesStore.markDriveNeedsReauth(msg)
                return@withContext Result.failure(
                    DriveAuthException.ReauthRequired(msg, silent.intentSenderRequest)
                )
            }
            is DriveAuthResult.Unavailable -> {
                val refreshAttempt = tryRefreshWithStoredRefreshToken(settings)
                if (refreshAttempt != null) return@withContext refreshAttempt

                val msg = "Não foi possível renovar a autorização Google silenciosamente (${silent.reason}). Reconecte sua conta."
                preferencesStore.markDriveNeedsReauth(msg)
                return@withContext Result.failure(DriveAuthException.ReauthRequired(msg))
            }
            DriveAuthResult.Cancelled,
            is DriveAuthResult.Failed -> {
                val reason = (silent as? DriveAuthResult.Failed)?.message
                    ?: "Autorização Google cancelada ou expirada."
                val refreshAttempt = tryRefreshWithStoredRefreshToken(settings)
                if (refreshAttempt != null) return@withContext refreshAttempt

                preferencesStore.markDriveNeedsReauth(reason)
                return@withContext Result.failure(DriveAuthException.ReauthRequired(reason))
            }
        }
    }

    private suspend fun tryRefreshWithStoredRefreshToken(settings: AppSettings): Result<String>? {
        val encRefresh = settings.driveEncryptedRefreshToken
        if (encRefresh.isBlank()) return null
        val plainRefresh = cryptoManager.decrypt(encRefresh)
        if (plainRefresh.isBlank()) return null

        val serverClientId = serverClientIdProvider().trim()
        val formBuilder = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", plainRefresh)
        if (serverClientId.isNotBlank()) {
            formBuilder.add("client_id", serverClientId)
        }

        val req = Request.Builder()
            .url("${oauthBaseUrl.trimEnd('/')}/token")
            .post(formBuilder.build())
            .build()

        val client = baseHttpClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = extractOAuthError(raw)
                    if (resp.code == 400 || resp.code == 401) {
                        preferencesStore.markDriveNeedsReauth("Sessão Google revogada ($errMsg). Reconecte sua conta.")
                        return Result.failure(DriveAuthException.ReauthRequired(errMsg))
                    }
                    return Result.failure(IllegalStateException("Falha ao renovar sessão Google (HTTP ${resp.code}): $errMsg"))
                }
                val json = JSONObject(raw)
                val newAccess = json.optString("access_token").takeIf { it.isNotBlank() }
                    ?: return Result.failure(IllegalStateException("Resposta OAuth sem access_token."))
                val rotatedRefresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)

                val encryptedAccess = cryptoManager.encrypt(newAccess)
                val preservedEncryptedRefresh = rotatedRefresh?.let { cryptoManager.encrypt(it) }
                    ?: settings.driveEncryptedRefreshToken

                preferencesStore.saveDriveAccountSession(
                    email = settings.driveAccountEmail ?: "",
                    displayName = settings.driveAccountName,
                    photoUrl = settings.driveAccountPhotoUrl,
                    encryptedAccessToken = encryptedAccess,
                    encryptedRefreshToken = preservedEncryptedRefresh,
                    expiresAt = nowProvider() + (expiresIn * 1000L)
                )
                Result.success(newAccess)
            }
        } catch (io: IOException) {
            Result.failure(io)
        } catch (e: Exception) {
            Result.failure(e)
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
                val body = FormBody.Builder()
                    .add("token", tokenToRevoke)
                    .build()
                val req = Request.Builder()
                    .url("${oauthBaseUrl.trimEnd('/')}/revoke")
                    .post(body)
                    .build()
                baseHttpClient.newBuilder()
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .newCall(req)
                    .execute()
                    .close()
            }
        }

        authorizationGateway.revokeAccess(
            context = context,
            scopes = BACKUP_SCOPES + IMPORT_SCOPES,
            accountEmail = settings.driveAccountEmail
        )
        credentialAuthenticator.clearSession()
        pendingCredentialProfile = null
        preferencesStore.clearDriveAccountSession()
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
                .ifBlank { throw IllegalStateException("Perfil Google não retornou e-mail.") }
            val name = obj.optString("name").trim().takeIf { it.isNotBlank() }
            val picture = obj.optString("picture").trim().takeIf { it.isNotBlank() }
            GoogleAccountProfile(
                email = email,
                displayName = name,
                photoUrl = picture
            )
        }
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
}