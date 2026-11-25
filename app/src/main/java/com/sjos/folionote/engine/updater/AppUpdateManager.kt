package com.sjos.folionote.engine.updater

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Coordinates in-app GitHub Release checks, cancellable streaming APK download to private storage,
 * SHA-256 + signing certificate verification, API 24-25 / API 26+ unknown-sources permission flow,
 * and handoff to Android's native PackageInstaller via FileProvider.
 */
class AppUpdateManager(
    private val context: Context,
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
    private val releaseService: GitHubReleaseService,
    private val apkVerifier: ApkVerifier = AndroidApkSignatureVerifier(context),
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val canRequestInstallsApi26Provider: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
    },
    private val legacyUnknownSourcesEnabledProvider: () -> Boolean = {
        @Suppress("DEPRECATION")
        runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }.getOrDefault(true)
    },
    private val apkContentUriProvider: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    },
    private val availableBytesProvider: (File) -> Long = { dir ->
        runCatching {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    },
    private val allowLocalhostForTests: Boolean = false
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeDownloadJob: Job? = null

    @Volatile
    private var activeDownloadCall: Call? = null

    private fun updatesDir(): File = File(context.filesDir, "updates").apply { mkdirs() }
    private fun partialApkFile(): File = File(updatesDir(), "FolioNote-update.apk.part")
    private fun verifiedApkFile(versionCode: Long): File = File(updatesDir(), "FolioNote-update-$versionCode.apk")

    fun getInstalledVersionInfo(): InstalledAppVersionInfo = apkVerifier.getInstalledAppInfo()

    fun checkForUpdatesAutomatically() {
        scope.launch {
            if (releaseService.isAutomaticCheckDue()) {
                checkForUpdates()
            }
        }
    }

    fun checkForUpdates() {
        val currentState = _uiState.value
        if (currentState is UpdateUiState.Checking ||
            currentState is UpdateUiState.Downloading ||
            currentState is UpdateUiState.VerifyingIntegrity
        ) {
            return
        }

        _uiState.value = UpdateUiState.Checking
        scope.launch {
            val installed = apkVerifier.getInstalledAppInfo()
            val result = releaseService.checkForUpdates(installed)
            result.onSuccess { outcome ->
                _uiState.value = when (outcome) {
                    is ReleaseCheckOutcome.UpToDate -> UpdateUiState.UpToDate(
                        installedVersionName = outcome.installedVersionName,
                        installedVersionCode = outcome.installedVersionCode,
                        checkedAtMs = System.currentTimeMillis()
                    )
                    is ReleaseCheckOutcome.UpdateAvailable -> UpdateUiState.UpdateAvailable(
                        update = outcome.update,
                        installedVersionName = outcome.installedVersionName,
                        installedVersionCode = outcome.installedVersionCode
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) return@onFailure
                _uiState.value = UpdateUiState.Failed(
                    message = err.message ?: "Não foi possível verificar atualizações."
                )
            }
        }
    }

    fun startOrResumeDownload(update: AvailableReleaseUpdate) {
        if (activeDownloadJob?.isActive == true) return

        val finalApk = verifiedApkFile(update.manifest.versionCode)
        if (finalApk.exists() && apkVerifier.verifyDownloadedApk(finalApk, update.manifest, update.expectedSha256).isSuccess) {
            transitionAfterVerification(update, finalApk)
            return
        }

        activeDownloadJob = scope.launch {
            downloadAndVerifyInternal(update)
        }
    }

    fun cancelDownload() {
        activeDownloadCall?.let { runCatching { it.cancel() } }
        activeDownloadCall = null
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        cleanupPartialFile()

        val currentUpdate = extractUpdateFromState(_uiState.value)
        _uiState.value = UpdateUiState.Cancelled(
            message = "Download cancelado e arquivo temporário removido.",
            update = currentUpdate
        )
    }

    fun dismissOrResetToIdle() {
        if (activeDownloadJob?.isActive == true) {
            cancelDownload()
        }
        _uiState.value = UpdateUiState.Idle
    }

    fun canRequestInstallPackages(): Boolean {
        return if (sdkIntProvider() >= Build.VERSION_CODES.O) {
            canRequestInstallsApi26Provider()
        } else {
            legacyUnknownSourcesEnabledProvider()
        }
    }

    // sdkIntProvider defaults to Build.VERSION.SDK_INT; injection exists only for compatibility tests.
    @SuppressLint("NewApi")
    fun createUnknownSourcesSettingsIntent(): Intent {
        return if (sdkIntProvider() >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun createPackageInstallerIntent(apkFile: File): Intent {
        val uri = apkContentUriProvider(apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchSystemInstaller(activity: Activity): Result<Unit> {
        val state = _uiState.value
        val (update, apkFile) = when (state) {
            is UpdateUiState.ReadyToInstall -> state.update to state.apkFile
            is UpdateUiState.AwaitingInstallPermission -> state.update to state.apkFile
            else -> return Result.failure(IllegalStateException("Nenhum APK validado pronto para instalar."))
        }

        if (!canRequestInstallPackages()) {
            _uiState.value = UpdateUiState.AwaitingInstallPermission(
                update = update,
                apkFile = apkFile,
                isLegacyPreOreo = sdkIntProvider() < Build.VERSION_CODES.O
            )
            return Result.failure(IllegalStateException("Permissão de instalação ainda não concedida."))
        }

        if (!apkFile.exists()) {
            _uiState.value = UpdateUiState.Failed(
                message = "Arquivo APK não encontrado. Baixe a atualização novamente.",
                update = update
            )
            return Result.failure(IllegalStateException("Arquivo APK não encontrado."))
        }

        return try {
            val installIntent = createPackageInstallerIntent(apkFile)
            _uiState.value = UpdateUiState.AwaitingSystemInstaller(update, apkFile)
            activity.startActivity(installIntent)
            Result.success(Unit)
        } catch (anf: ActivityNotFoundException) {
            _uiState.value = UpdateUiState.Failed(
                message = "Nenhum instalador de pacotes encontrado neste dispositivo.",
                update = update
            )
            Result.failure(anf)
        } catch (sec: SecurityException) {
            _uiState.value = UpdateUiState.Failed(
                message = "O Android recusou o acesso seguro ao APK para instalação.",
                update = update
            )
            Result.failure(sec)
        } catch (t: Throwable) {
            _uiState.value = UpdateUiState.Failed(
                message = t.localizedMessage ?: "Falha ao abrir o instalador do Android.",
                update = update
            )
            Result.failure(t)
        }
    }

    fun refreshStateAfterExternalReturn() {
        when (val state = _uiState.value) {
            is UpdateUiState.AwaitingInstallPermission -> {
                if (canRequestInstallPackages()) {
                    _uiState.value = UpdateUiState.ReadyToInstall(state.update, state.apkFile)
                }
            }
            is UpdateUiState.AwaitingSystemInstaller -> {
                val current = apkVerifier.getInstalledAppInfo()
                if (current.versionCode >= state.update.manifest.versionCode) {
                    cleanupAllUpdateFiles()
                    _uiState.value = UpdateUiState.Installed(
                        versionName = current.versionName,
                        versionCode = current.versionCode
                    )
                } else if (state.apkFile.exists()) {
                    _uiState.value = UpdateUiState.ReadyToInstall(state.update, state.apkFile)
                } else {
                    _uiState.value = UpdateUiState.Cancelled(
                        message = "A instalação não foi concluída no instalador do sistema.",
                        update = state.update
                    )
                }
            }
            else -> Unit
        }
    }

    internal suspend fun downloadAndVerifyInternal(update: AvailableReleaseUpdate) {
        val downloadUrl = update.apkAsset.browserDownloadUrl
        val lower = downloadUrl.lowercase()
        val isHttps = lower.startsWith("https://")
        val isLocalTest = allowLocalhostForTests &&
            (lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost"))
        if (!isHttps && !isLocalTest) {
            _uiState.value = UpdateUiState.Failed(
                message = "Download recusado: apenas URLs HTTPS são permitidas.",
                update = update
            )
            return
        }

        val declaredSize = update.manifest.sizeBytes
        if (declaredSize <= 0L || declaredSize > UpdaterContract.MAX_APK_BYTES) {
            _uiState.value = UpdateUiState.Failed(
                message = "Tamanho de APK declarado inválido (${declaredSize} bytes).",
                update = update
            )
            return
        }

        val freeBytes = availableBytesProvider(updatesDir())
        if (freeBytes < declaredSize + (16L * 1024L * 1024L)) {
            _uiState.value = UpdateUiState.Failed(
                message = "Espaço livre insuficiente no aparelho para baixar e verificar a atualização.",
                update = update
            )
            return
        }

        _uiState.value = UpdateUiState.PreparingDownload(update)

        val partFile = partialApkFile()
        val existingBytes = if (partFile.exists()) partFile.length().coerceAtMost(declaredSize) else 0L
        val resumeOffset = if (existingBytes in 1 until declaredSize) existingBytes else 0L
        if (resumeOffset == 0L && partFile.exists()) {
            runCatching { partFile.delete() }
        }

        try {
            val client = baseHttpClient.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val reqBuilder = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "FolioNote-Android/${apkVerifier.getInstalledAppInfo().versionName}")
            if (resumeOffset > 0L) {
                reqBuilder.header("Range", "bytes=$resumeOffset-")
            }

            val call = client.newCall(reqBuilder.build())
            activeDownloadCall = call

            call.execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    cleanupPartialFile()
                    _uiState.value = UpdateUiState.Failed(
                        message = "Erro HTTP ${resp.code} ao baixar a atualização.",
                        update = update
                    )
                    return
                }

                val isResumed = resp.code == 206 && resumeOffset > 0L
                val appendMode = isResumed
                var bytesWritten = if (isResumed) resumeOffset else 0L
                val expectedTotal = declaredSize

                val bodyStream = resp.body?.byteStream()
                    ?: throw IOException("Resposta de download vazia.")

                FileOutputStream(partFile, appendMode).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var lastEmitAt = 0L
                    _uiState.value = UpdateUiState.Downloading(
                        update = update,
                        bytesDownloaded = bytesWritten,
                        totalBytes = expectedTotal,
                        percent = ((bytesWritten * 100L) / expectedTotal).toInt().coerceIn(0, 100)
                    )

                    while (true) {
                        coroutineContext.ensureActive()
                        val read = bodyStream.read(buffer)
                        if (read <= 0) break
                        if (bytesWritten + read > UpdaterContract.MAX_APK_BYTES ||
                            bytesWritten + read > expectedTotal
                        ) {
                            cleanupPartialFile()
                            throw IllegalStateException("Download excedeu o tamanho declarado no manifesto.")
                        }
                        output.write(buffer, 0, read)
                        bytesWritten += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitAt >= 120L || bytesWritten == expectedTotal) {
                            lastEmitAt = now
                            _uiState.value = UpdateUiState.Downloading(
                                update = update,
                                bytesDownloaded = bytesWritten,
                                totalBytes = expectedTotal,
                                percent = ((bytesWritten * 100L) / expectedTotal).toInt().coerceIn(0, 100)
                            )
                        }
                    }
                    output.flush()
                }
            }

            coroutineContext.ensureActive()
            _uiState.value = UpdateUiState.VerifyingIntegrity(update)

            val finalFile = verifiedApkFile(update.manifest.versionCode)
            updatesDir().listFiles()?.forEach { f ->
                if (f != partFile && f != finalFile && f.name.endsWith(".apk")) {
                    runCatching { f.delete() }
                }
            }
            if (finalFile.exists()) runCatching { finalFile.delete() }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                runCatching { partFile.delete() }
            }

            val verification = apkVerifier.verifyDownloadedApk(
                apkFile = finalFile,
                manifest = update.manifest,
                expectedSha256 = update.expectedSha256
            )
            if (verification.isFailure) {
                runCatching { finalFile.delete() }
                cleanupPartialFile()
                _uiState.value = UpdateUiState.Failed(
                    message = verification.exceptionOrNull()?.message
                        ?: "O APK baixado falhou na verificação de segurança.",
                    update = update
                )
                return
            }

            transitionAfterVerification(update, finalFile)
        } catch (ce: CancellationException) {
            cleanupPartialFile()
            _uiState.value = UpdateUiState.Cancelled(
                message = "Download cancelado e arquivo parcial removido.",
                update = update
            )
        } catch (io: IOException) {
            val savedBytes = if (partFile.exists()) partFile.length() else 0L
            if (savedBytes in 1 until declaredSize) {
                _uiState.value = UpdateUiState.Paused(
                    update = update,
                    bytesDownloaded = savedBytes,
                    totalBytes = declaredSize,
                    reason = "Conexão interrompida durante o download."
                )
            } else {
                cleanupPartialFile()
                _uiState.value = UpdateUiState.Failed(
                    message = "Falha de rede durante o download da atualização.",
                    update = update
                )
            }
        } catch (t: Throwable) {
            cleanupPartialFile()
            _uiState.value = UpdateUiState.Failed(
                message = t.message ?: "Erro inesperado ao baixar atualização.",
                update = update
            )
        } finally {
            activeDownloadCall = null
        }
    }

    private fun transitionAfterVerification(update: AvailableReleaseUpdate, finalFile: File) {
        if (canRequestInstallPackages()) {
            _uiState.value = UpdateUiState.ReadyToInstall(update, finalFile)
        } else {
            _uiState.value = UpdateUiState.AwaitingInstallPermission(
                update = update,
                apkFile = finalFile,
                isLegacyPreOreo = sdkIntProvider() < Build.VERSION_CODES.O
            )
        }
    }

    private fun cleanupPartialFile() {
        runCatching { partialApkFile().delete() }
    }

    private fun cleanupAllUpdateFiles() {
        runCatching { updatesDir().deleteRecursively() }
    }

    private fun extractUpdateFromState(state: UpdateUiState): AvailableReleaseUpdate? = when (state) {
        is UpdateUiState.UpdateAvailable -> state.update
        is UpdateUiState.PreparingDownload -> state.update
        is UpdateUiState.Downloading -> state.update
        is UpdateUiState.Paused -> state.update
        is UpdateUiState.VerifyingIntegrity -> state.update
        is UpdateUiState.AwaitingInstallPermission -> state.update
        is UpdateUiState.ReadyToInstall -> state.update
        is UpdateUiState.AwaitingSystemInstaller -> state.update
        is UpdateUiState.Cancelled -> state.update
        is UpdateUiState.Failed -> state.update
        else -> null
    }
}
