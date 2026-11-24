package com.sjos.folionote.engine.updater

import org.json.JSONObject
import java.io.File

object UpdaterContract {
    const val GITHUB_OWNER = "Sjos0"
    const val GITHUB_REPO = "FolioNot"
    const val LATEST_RELEASE_ENDPOINT = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val GITHUB_API_VERSION = "2022-11-28"
    const val EXPECTED_PACKAGE_NAME = "com.sjos.folionote"
    const val MANIFEST_ASSET_NAME = "release-manifest.json"
    const val MAX_APK_BYTES = 300L * 1024L * 1024L
    val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
}

data class GitHubReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val browserDownloadUrl: String,
    val contentType: String = "",
    val digest: String? = null
) {
    val normalizedDigestSha256: String?
        get() {
            val raw = digest?.trim().orEmpty()
            if (raw.isBlank()) return null
            val cleaned = if (raw.startsWith("sha256:", ignoreCase = true)) {
                raw.substringAfter(':').trim()
            } else {
                raw
            }
            return cleaned.lowercase().takeIf { UpdaterContract.SHA256_REGEX.matches(it) }
        }
}

data class GitHubReleasePayload(
    val tagName: String,
    val releaseName: String,
    val bodyMarkdown: String,
    val htmlUrl: String,
    val publishedAt: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val assets: List<GitHubReleaseAsset>,
    val etag: String? = null
)

data class ReleaseManifest(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val apkFileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertSha256: String,
    val tag: String,
    val releaseUrl: String
) {
    companion object {
        fun normalizeFingerprint(raw: String): String {
            return raw.replace(":", "").replace(" ", "").trim().lowercase()
        }

        fun parseAndValidate(
            rawJson: String,
            expectedPackageName: String = UpdaterContract.EXPECTED_PACKAGE_NAME
        ): Result<ReleaseManifest> = runCatching {
            val obj = JSONObject(rawJson)
            val pkg = obj.optString("packageName").trim()
            require(pkg == expectedPackageName) {
                "Manifesto rejeitado: pacote incompatível ($pkg)."
            }
            val versionName = obj.optString("versionName").trim()
            require(versionName.isNotBlank()) { "Manifesto inválido: versionName ausente." }

            val versionCode = obj.optLong("versionCode", -1L)
            require(versionCode > 0L) { "Manifesto inválido: versionCode inválido." }

            val minSdk = obj.optInt("minSdk", 0)
            require(minSdk >= 21) { "Manifesto inválido: minSdk não informado ou inválido." }

            val apkName = obj.optString("apkFileName").trim()
            require(apkName.isNotBlank() && apkName.endsWith(".apk", ignoreCase = true)) {
                "Manifesto inválido: nome do APK inválido."
            }
            require(!apkName.contains("debug", ignoreCase = true)) {
                "Manifesto rejeitado: APK de debug não é permitido em atualizações."
            }

            val sizeBytes = obj.optLong("sizeBytes", -1L)
            require(sizeBytes in 1L..UpdaterContract.MAX_APK_BYTES) {
                "Manifesto inválido: tamanho do APK fora do intervalo seguro."
            }

            val sha256 = obj.optString("sha256").trim().lowercase()
            require(UpdaterContract.SHA256_REGEX.matches(sha256)) {
                "Manifesto inválido: SHA-256 do APK ausente ou malformado."
            }

            val certSha256 = normalizeFingerprint(obj.optString("signingCertSha256"))
            require(UpdaterContract.SHA256_REGEX.matches(certSha256)) {
                "Manifesto inválido: fingerprint do certificado de assinatura ausente ou inválido."
            }

            val tag = obj.optString("tag").trim()
            val releaseUrl = obj.optString("releaseUrl").trim()

            ReleaseManifest(
                packageName = pkg,
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                apkFileName = apkName,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                signingCertSha256 = certSha256,
                tag = tag,
                releaseUrl = releaseUrl
            )
        }
    }
}

data class AvailableReleaseUpdate(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val releaseHtmlUrl: String,
    val publishedAt: String,
    val manifest: ReleaseManifest,
    val apkAsset: GitHubReleaseAsset,
    val expectedSha256: String
)

sealed class ReleaseCheckOutcome {
    data class UpToDate(
        val installedVersionName: String,
        val installedVersionCode: Long,
        val latestVersionName: String?,
        val latestVersionCode: Long?
    ) : ReleaseCheckOutcome()

    data class UpdateAvailable(
        val update: AvailableReleaseUpdate,
        val installedVersionName: String,
        val installedVersionCode: Long
    ) : ReleaseCheckOutcome()
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(
        val installedVersionName: String,
        val installedVersionCode: Long,
        val checkedAtMs: Long
    ) : UpdateUiState

    data class UpdateAvailable(
        val update: AvailableReleaseUpdate,
        val installedVersionName: String,
        val installedVersionCode: Long
    ) : UpdateUiState

    data class PreparingDownload(
        val update: AvailableReleaseUpdate
    ) : UpdateUiState

    data class Downloading(
        val update: AvailableReleaseUpdate,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int
    ) : UpdateUiState

    data class Paused(
        val update: AvailableReleaseUpdate,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val reason: String
    ) : UpdateUiState

    data class VerifyingIntegrity(
        val update: AvailableReleaseUpdate
    ) : UpdateUiState

    data class AwaitingInstallPermission(
        val update: AvailableReleaseUpdate,
        val apkFile: File,
        val isLegacyPreOreo: Boolean
    ) : UpdateUiState

    data class ReadyToInstall(
        val update: AvailableReleaseUpdate,
        val apkFile: File
    ) : UpdateUiState

    data class AwaitingSystemInstaller(
        val update: AvailableReleaseUpdate,
        val apkFile: File
    ) : UpdateUiState

    data class Installed(
        val versionName: String,
        val versionCode: Long
    ) : UpdateUiState

    data class Cancelled(
        val message: String,
        val update: AvailableReleaseUpdate? = null
    ) : UpdateUiState

    data class Failed(
        val message: String,
        val update: AvailableReleaseUpdate? = null
    ) : UpdateUiState
}
