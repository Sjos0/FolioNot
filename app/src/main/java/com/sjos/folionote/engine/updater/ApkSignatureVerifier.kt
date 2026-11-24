package com.sjos.folionote.engine.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class InstalledAppVersionInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long
)

interface ApkVerifier {
    fun getInstalledAppInfo(): InstalledAppVersionInfo
    fun getInstalledSigningCertSha256List(): List<String>
    fun verifyDownloadedApk(
        apkFile: File,
        manifest: ReleaseManifest,
        expectedSha256: String
    ): Result<Unit>
}

class AndroidApkSignatureVerifier(
    private val context: Context,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) : ApkVerifier {

    override fun getInstalledAppInfo(): InstalledAppVersionInfo {
        val pkgName = context.packageName
        val info = getInstalledPackageInfo(flags = 0)
        val versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 1L
        val versionName = info?.versionName?.takeIf { it.isNotBlank() } ?: "1.0.0"
        return InstalledAppVersionInfo(
            packageName = pkgName,
            versionName = versionName,
            versionCode = versionCode
        )
    }

    override fun getInstalledSigningCertSha256List(): List<String> {
        val sdk = sdkIntProvider()
        val flags = if (sdk >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val pkgInfo = getInstalledPackageInfo(flags) ?: return emptyList()
        return extractCertFingerprints(pkgInfo, sdk)
    }

    override fun verifyDownloadedApk(
        apkFile: File,
        manifest: ReleaseManifest,
        expectedSha256: String
    ): Result<Unit> = runCatching {
        require(apkFile.exists() && apkFile.isFile && apkFile.length() > 0L) {
            "Arquivo APK baixado não existe ou está vazio."
        }
        if (manifest.sizeBytes > 0L) {
            require(apkFile.length() == manifest.sizeBytes) {
                "APK incompleto ou adulterado (tamanho divergente: ${apkFile.length()} vs ${manifest.sizeBytes} bytes)."
            }
        }

        val actualSha256 = computeSha256Hex(apkFile)
        val targetHash = ReleaseManifest.normalizeFingerprint(expectedSha256)
        require(actualSha256 == targetHash) {
            "Falha na verificação de integridade (SHA-256 divergente)."
        }

        val installed = getInstalledAppInfo()
        val sdk = sdkIntProvider()
        val flags = if (sdk >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        @Suppress("DEPRECATION")
        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw IllegalStateException("Arquivo APK corrompido ou inválido (não pôde ser inspecionado).")

        require(archiveInfo.packageName == UpdaterContract.EXPECTED_PACKAGE_NAME &&
            archiveInfo.packageName == installed.packageName
        ) {
            "APK rejeitado: identificador do pacote divergente (${archiveInfo.packageName})."
        }

        val apkVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        require(apkVersionCode == manifest.versionCode) {
            "APK rejeitado: versionCode ($apkVersionCode) difere do manifesto (${manifest.versionCode})."
        }
        require(apkVersionCode > installed.versionCode) {
            "Atualização ignorada: o APK não possui versão superior à instalada."
        }

        val apkFingerprints = extractCertFingerprints(archiveInfo, sdk)
        require(apkFingerprints.isNotEmpty()) {
            "APK rejeitado: certificado de assinatura ausente."
        }

        val manifestCert = ReleaseManifest.normalizeFingerprint(manifest.signingCertSha256)
        require(apkFingerprints.contains(manifestCert)) {
            "APK rejeitado: certificado do APK não corresponde ao fingerprint publicado no manifesto."
        }

        val installedCerts = getInstalledSigningCertSha256List()
        require(installedCerts.isNotEmpty()) {
            "Não foi possível verificar a assinatura atual do aplicativo instalado."
        }
        require(apkFingerprints.any { it in installedCerts }) {
            "APK rejeitado: assinado com certificado diferente da versão instalada."
        }
    }

    internal fun extractCertFingerprints(packageInfo: PackageInfo, sdkInt: Int): List<String> {
        val signatures: Array<Signature> = if (sdkInt >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners ?: emptyArray()
            } else {
                signingInfo.signingCertificateHistory ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: emptyArray()
        }
        return signatures.map { sig -> sha256Hex(sig.toByteArray()) }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(flags: Int): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, flags)
            }
        }.getOrNull()
    }

    companion object {
        fun computeSha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256Hex(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
