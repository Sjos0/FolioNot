package com.sjos.folionote.engine.updater

import android.os.Build
import com.sjos.folionote.core.preferences.AppPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubReleaseService(
    private val baseHttpClient: OkHttpClient = OkHttpClient(),
    private val preferencesStore: AppPreferencesStore? = null,
    private val latestReleaseUrl: String = UpdaterContract.LATEST_RELEASE_ENDPOINT,
    private val deviceSdkProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val allowLocalhostForTests: Boolean = false,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun checkForUpdates(installedInfo: InstalledAppVersionInfo): Result<ReleaseCheckOutcome> =
        withContext(Dispatchers.IO) {
            try {
                val cached = preferencesStore?.getReleaseCheckCache()
                val client = baseHttpClient.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val reqBuilder = Request.Builder()
                    .url(latestReleaseUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", UpdaterContract.GITHUB_API_VERSION)
                    .header("User-Agent", "FolioNote-Android/${installedInfo.versionName}")

                if (!cached?.etag.isNullOrBlank() && !cached?.cachedJson.isNullOrBlank()) {
                    reqBuilder.header("If-None-Match", cached!!.etag!!)
                }

                val payload: GitHubReleasePayload = client.newCall(reqBuilder.build()).execute().use { resp ->
                    when (resp.code) {
                        304 -> {
                            val cachedBody = cached?.cachedJson
                                ?: throw IllegalStateException("Cache ETag vazio ao consultar GitHub.")
                            parseGitHubReleaseJson(cachedBody, cached.etag).getOrThrow()
                        }
                        404 -> throw IllegalStateException(
                            "Nenhuma versão publicada encontrada no repositório oficial (${UpdaterContract.GITHUB_OWNER}/${UpdaterContract.GITHUB_REPO})."
                        )
                        403, 429 -> {
                            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                            val suffix = if (retryAfter != null) " Tente novamente em ${retryAfter}s." else " Aguarde alguns minutos."
                            throw IllegalStateException("Limite de consultas à API do GitHub atingido (HTTP ${resp.code}).$suffix")
                        }
                        in 500..599 -> throw IllegalStateException(
                            "Servidor do GitHub temporariamente indisponível (HTTP ${resp.code})."
                        )
                        !in 200..299 -> throw IllegalStateException(
                            "Resposta inesperada do GitHub ao buscar atualizações (HTTP ${resp.code})."
                        )
                        else -> {
                            val body = resp.body?.string().orEmpty()
                            val newEtag = resp.header("ETag")
                            val parsed = parseGitHubReleaseJson(body, newEtag).getOrThrow()
                            preferencesStore?.saveReleaseCheckCache(newEtag, body, nowProvider())
                            parsed
                        }
                    }
                }

                if (payload.isDraft || payload.isPrerelease) {
                    return@withContext Result.success(
                        ReleaseCheckOutcome.UpToDate(
                            installedVersionName = installedInfo.versionName,
                            installedVersionCode = installedInfo.versionCode,
                            latestVersionName = null,
                            latestVersionCode = null
                        )
                    )
                }

                val manifestAsset = payload.assets.firstOrNull {
                    it.name.equals(UpdaterContract.MANIFEST_ASSET_NAME, ignoreCase = true)
                } ?: throw IllegalStateException(
                    "Release ${payload.tagName} sem manifesto de verificação (${UpdaterContract.MANIFEST_ASSET_NAME})."
                )

                validateAssetDownloadUrl(manifestAsset.browserDownloadUrl)
                val manifestRaw = fetchTextAsset(client, manifestAsset.browserDownloadUrl, installedInfo.versionName)
                val manifest = ReleaseManifest.parseAndValidate(manifestRaw).getOrThrow()

                val apkAsset = resolveApkAsset(payload.assets, manifest)
                validateAssetDownloadUrl(apkAsset.browserDownloadUrl)

                if (apkAsset.sizeBytes > 0L && apkAsset.sizeBytes != manifest.sizeBytes) {
                    throw IllegalStateException(
                        "Tamanho do APK no GitHub (${apkAsset.sizeBytes} bytes) diverge do manifesto (${manifest.sizeBytes} bytes)."
                    )
                }

                val githubDigest = apkAsset.normalizedDigestSha256
                if (githubDigest != null && githubDigest != manifest.sha256) {
                    throw IllegalStateException(
                        "Digest SHA-256 do asset no GitHub diverge do manifesto oficial."
                    )
                }
                val expectedSha256 = githubDigest ?: manifest.sha256

                val deviceSdk = deviceSdkProvider()
                if (manifest.minSdk > deviceSdk) {
                    throw IllegalStateException(
                        "A versão ${manifest.versionName} exige Android API ${manifest.minSdk} (dispositivo atual: API $deviceSdk)."
                    )
                }

                if (manifest.versionCode <= installedInfo.versionCode) {
                    return@withContext Result.success(
                        ReleaseCheckOutcome.UpToDate(
                            installedVersionName = installedInfo.versionName,
                            installedVersionCode = installedInfo.versionCode,
                            latestVersionName = manifest.versionName,
                            latestVersionCode = manifest.versionCode
                        )
                    )
                }

                Result.success(
                    ReleaseCheckOutcome.UpdateAvailable(
                        update = AvailableReleaseUpdate(
                            tagName = payload.tagName,
                            title = payload.releaseName.ifBlank { "FolioNote ${payload.tagName}" },
                            releaseNotes = payload.bodyMarkdown.trim(),
                            releaseHtmlUrl = payload.htmlUrl,
                            publishedAt = payload.publishedAt,
                            manifest = manifest,
                            apkAsset = apkAsset,
                            expectedSha256 = expectedSha256
                        ),
                        installedVersionName = installedInfo.versionName,
                        installedVersionCode = installedInfo.versionCode
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (io: IOException) {
                Result.failure(
                    IOException("Sem conexão com a internet para verificar atualizações no GitHub.", io)
                )
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    internal fun parseGitHubReleaseJson(rawJson: String, etag: String? = null): Result<GitHubReleasePayload> =
        runCatching {
            val trimmed = rawJson.trim()
            require(trimmed.startsWith("{")) { "Resposta do GitHub não é um JSON válido de release." }
            val obj = JSONObject(trimmed)
            val tagName = obj.optString("tag_name").trim()
            require(tagName.isNotBlank()) { "Release do GitHub sem tag_name." }

            val assetsJson = obj.optJSONArray("assets") ?: JSONArray()
            val assets = mutableListOf<GitHubReleaseAsset>()
            for (i in 0 until assetsJson.length()) {
                val item = assetsJson.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("browser_download_url").trim()
                if (name.isNotBlank() && url.isNotBlank()) {
                    assets.add(
                        GitHubReleaseAsset(
                            name = name,
                            sizeBytes = item.optLong("size", 0L),
                            browserDownloadUrl = url,
                            contentType = item.optString("content_type").trim(),
                            digest = item.optString("digest").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }

            GitHubReleasePayload(
                tagName = tagName,
                releaseName = obj.optString("name").trim(),
                bodyMarkdown = obj.optString("body").orEmpty(),
                htmlUrl = obj.optString("html_url").trim(),
                publishedAt = obj.optString("published_at").trim(),
                isDraft = obj.optBoolean("draft", false),
                isPrerelease = obj.optBoolean("prerelease", false),
                assets = assets,
                etag = etag
            )
        }

    private fun resolveApkAsset(
        assets: List<GitHubReleaseAsset>,
        manifest: ReleaseManifest
    ): GitHubReleaseAsset {
        val matched = assets.firstOrNull {
            it.name == manifest.apkFileName &&
                !it.name.contains("debug", ignoreCase = true)
        } ?: throw IllegalStateException(
            "APK de produção esperado (${manifest.apkFileName}) não encontrado nos assets deste release."
        )
        require(matched.sizeBytes <= UpdaterContract.MAX_APK_BYTES) {
            "Tamanho do APK excede o limite de segurança permitido."
        }
        return matched
    }

    private fun validateAssetDownloadUrl(url: String) {
        val lower = url.lowercase()
        val isHttps = lower.startsWith("https://")
        val isAllowedLocalhost = allowLocalhostForTests &&
            (lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost"))
        require(isHttps || isAllowedLocalhost) {
            "URL do asset rejeitada por exigir HTTPS ($url)."
        }
    }

    private fun fetchTextAsset(client: OkHttpClient, url: String, versionName: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "FolioNote-Android/$versionName")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Falha ao baixar manifesto do release (HTTP ${resp.code}).")
            }
            resp.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Manifesto do release vazio.")
        }
    }
}
