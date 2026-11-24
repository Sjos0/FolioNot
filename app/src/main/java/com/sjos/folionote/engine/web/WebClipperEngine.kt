package com.sjos.folionote.engine.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ClippedWebPage(
    val requestedUrl: String,
    val finalUrl: String,
    val canonicalUrl: String,
    val title: String,
    val author: String?,
    val markdownContent: String,
    val capturedAtMillis: Long,
    val domain: String
)

class WebClipperEngine(
    private val baseHttpClient: OkHttpClient = OkHttpClient()
) {

    companion object {
        private val URL_REGEX = Pattern.compile("""https?://[^\s<>"'\)\]]+""", Pattern.CASE_INSENSITIVE)
        private const val READER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) FolioNote/1.0 Mobile Safari/537.36"
    }

    fun extractFirstUrl(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        val matcher = URL_REGEX.matcher(sharedText)
        while (matcher.find()) {
            val candidate = matcher.group()?.trimEnd('.', ',', ';', '!', '?') ?: continue
            if (validateUrl(candidate).isSuccess) return candidate
        }
        return null
    }

    fun validateUrl(rawUrl: String): Result<HttpUrl> {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Informe uma URL para clipar."))
        }
        val normalized = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
        val parsed = normalized.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("URL inválida: verifique o endereço digitado."))
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return Result.failure(IllegalArgumentException("Apenas URLs HTTP e HTTPS são suportadas."))
        }
        if (parsed.host.isBlank()) {
            return Result.failure(IllegalArgumentException("Domínio da URL ausente."))
        }
        return Result.success(parsed)
    }

    suspend fun clipUrl(rawUrl: String): Result<ClippedWebPage> = withContext(Dispatchers.IO) {
        val parsedUrl = validateUrl(rawUrl).getOrElse { return@withContext Result.failure(it) }
        val client = baseHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder()
            .url(parsedUrl)
            .header("User-Agent", READER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 402, 403 -> throw IllegalStateException(
                        "Acesso negado ou conteúdo protegido por paywall/login (HTTP ${response.code})."
                    )
                    404 -> throw IllegalStateException("Página não encontrada (HTTP 404).")
                    in 500..599 -> throw IllegalStateException("Servidor do site indisponível (HTTP ${response.code}).")
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("Erro ao baixar página (HTTP ${response.code}).")
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val bodyString = response.body?.string().orEmpty()
                val finalUrl = response.request.url.toString()
                val domain = response.request.url.host
                val capturedAt = System.currentTimeMillis()

                if (contentType.contains("text/plain") || contentType.contains("text/markdown")) {
                    val cleanText = bodyString.replace("\r\n", "\n").trim()
                    val title = cleanText.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: domain
                    return@runCatching ClippedWebPage(
                        requestedUrl = parsedUrl.toString(),
                        finalUrl = finalUrl,
                        canonicalUrl = finalUrl,
                        title = title,
                        author = null,
                        markdownContent = "# $title\n\nFonte: $finalUrl\n\n$cleanText",
                        capturedAtMillis = capturedAt,
                        domain = domain
                    )
                }

                if (contentType.isNotBlank() &&
                    !contentType.contains("html") &&
                    !contentType.contains("xml") &&
                    !contentType.contains("text/")
                ) {
                    throw IllegalStateException("Tipo de conteúdo não suportado para clipping web ($contentType). Baixe e importe como arquivo.")
                }

                parseHtmlToClippedPage(
                    html = bodyString,
                    requestedUrl = parsedUrl.toString(),
                    finalUrl = finalUrl,
                    domain = domain,
                    capturedAt = capturedAt
                )
            }
        }
    }

    internal fun parseHtmlToClippedPage(
        html: String,
        requestedUrl: String,
        finalUrl: String,
        domain: String,
        capturedAt: Long = System.currentTimeMillis()
    ): ClippedWebPage {
        val rawTitle = extractMetaContent(html, "property", "og:title")
            ?: extractTagInnerText(html, "title")
            ?: domain
        val title = decodeHtmlEntities(rawTitle).replace(Regex("\\s+"), " ").trim().ifBlank { domain }
        val author = extractMetaContent(html, "name", "author")?.let { decodeHtmlEntities(it).trim() }?.takeIf { it.isNotBlank() }
        val canonicalUrl = extractCanonicalHref(html) ?: finalUrl

        val bodyHtml = selectPrimaryContentBlock(html)
        val bodyMarkdown = htmlFragmentToMarkdown(bodyHtml)
        if (bodyMarkdown.isBlank()) {
            throw IllegalStateException("Não foi possível extrair conteúdo textual legível desta página.")
        }

        val fullMarkdown = buildString {
            append("# ").append(title).append("\n\n")
            append("URL original: ").append(canonicalUrl).append("\n")
            if (!author.isNullOrBlank()) {
                append("Autor: ").append(author).append("\n")
            }
            append("\n").append(bodyMarkdown)
        }.trim()

        return ClippedWebPage(
            requestedUrl = requestedUrl,
            finalUrl = finalUrl,
            canonicalUrl = canonicalUrl,
            title = title,
            author = author,
            markdownContent = fullMarkdown,
            capturedAtMillis = capturedAt,
            domain = domain
        )
    }

    internal fun htmlFragmentToMarkdown(rawHtml: String): String {
        var working = rawHtml
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<(script|style|noscript|svg|canvas|iframe|nav|header|footer|aside|form|button)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<(div|section|aside)[^>]+(class|id)=[\"'][^\"']*(cookie|banner|advert|promo|newsletter|sidebar|popup|modal|menu|social-share)[^\"']*[\"'][^>]*>.*?</\\1>"), " ")

        working = working
            .replace(Regex("(?is)<h1[^>]*>(.*?)</h1>"), "\n\n# $1\n\n")
            .replace(Regex("(?is)<h2[^>]*>(.*?)</h2>"), "\n\n## $1\n\n")
            .replace(Regex("(?is)<h3[^>]*>(.*?)</h3>"), "\n\n### $1\n\n")
            .replace(Regex("(?is)<h4[^>]*>(.*?)</h4>"), "\n\n#### $1\n\n")
            .replace(Regex("(?is)<blockquote[^>]*>(.*?)</blockquote>"), "\n\n> $1\n\n")
            .replace(Regex("(?is)<li[^>]*>(.*?)</li>"), "\n- $1")
            .replace(Regex("(?is)<tr[^>]*>(.*?)</tr>"), "\n$1\n")
            .replace(Regex("(?is)<(td|th)[^>]*>(.*?)</\\1>"), " | $2")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|article|section|ul|ol|pre)>"), "\n\n")
            .replace(Regex("(?is)<[^>]+>"), " ")

        val decoded = decodeHtmlEntities(working)
        return decoded
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .joinToString("\n") { line ->
                line.replace(Regex("[ \\t]+"), " ").trim()
            }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun selectPrimaryContentBlock(html: String): String {
        val article = extractBalancedTagContent(html, "article")
        if (article != null && stripAllTags(article).length >= 120) return article

        val main = extractBalancedTagContent(html, "main")
        if (main != null && stripAllTags(main).length >= 120) return main

        val body = extractBalancedTagContent(html, "body")
        return body ?: html
    }

    private fun extractBalancedTagContent(html: String, tagName: String): String? {
        val openMatcher = Pattern.compile("(?is)<$tagName\\b[^>]*>").matcher(html)
        if (!openMatcher.find()) return null
        val contentStart = openMatcher.end()
        val closeMatcher = Pattern.compile("(?is)</$tagName>").matcher(html)
        var lastEnd = -1
        while (closeMatcher.find()) {
            if (closeMatcher.start() > contentStart) {
                lastEnd = closeMatcher.start()
                if (tagName == "article" || tagName == "main") break
            }
        }
        return if (lastEnd > contentStart) html.substring(contentStart, lastEnd) else null
    }

    private fun extractTagInnerText(html: String, tagName: String): String? {
        val matcher = Pattern.compile("(?is)<$tagName\\b[^>]*>(.*?)</$tagName>").matcher(html)
        return if (matcher.find()) stripAllTags(matcher.group(1).orEmpty()).trim().takeIf { it.isNotBlank() } else null
    }

    private fun extractMetaContent(html: String, attrName: String, attrValue: String): String? {
        val p1 = Pattern.compile(
            """(?is)<meta\b[^>]*$attrName=["']$attrValue["'][^>]*content=["']([^"']+)["'][^>]*>"""
        ).matcher(html)
        if (p1.find()) return p1.group(1)
        val p2 = Pattern.compile(
            """(?is)<meta\b[^>]*content=["']([^"']+)["'][^>]*$attrName=["']$attrValue["'][^>]*>"""
        ).matcher(html)
        return if (p2.find()) p2.group(1) else null
    }

    private fun extractCanonicalHref(html: String): String? {
        val p = Pattern.compile(
            """(?is)<link\b[^>]*rel=["']canonical["'][^>]*href=["']([^"']+)["'][^>]*>"""
        ).matcher(html)
        return if (p.find()) p.group(1)?.trim()?.takeIf { it.startsWith("http") } else null
    }

    private fun stripAllTags(input: String): String =
        input.replace(Regex("(?is)<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    internal fun decodeHtmlEntities(input: String): String {
        var text = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")

        val decMatcher = Pattern.compile("&#(\\d+);").matcher(text)
        val sbDec = StringBuffer()
        while (decMatcher.find()) {
            val code = decMatcher.group(1)?.toIntOrNull()
            val replacement = if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else decMatcher.group(0)
            decMatcher.appendReplacement(sbDec, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        decMatcher.appendTail(sbDec)
        text = sbDec.toString()

        val hexMatcher = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(text)
        val sbHex = StringBuffer()
        while (hexMatcher.find()) {
            val code = hexMatcher.group(1)?.toIntOrNull(16)
            val replacement = if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else hexMatcher.group(0)
            hexMatcher.appendReplacement(sbHex, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        hexMatcher.appendTail(sbHex)
        return sbHex.toString()
    }
}
