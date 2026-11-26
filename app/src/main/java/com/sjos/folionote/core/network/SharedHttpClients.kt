package com.sjos.folionote.core.network

import com.sjos.folionote.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RequestTimeoutSpec(
    val connectTimeoutSeconds: Int? = null,
    val readTimeoutSeconds: Int? = null,
    val writeTimeoutSeconds: Int? = null
)

data class UrlAllowlistPolicy(
    val allowedHostSuffixes: Set<String>,
    val allowLocalhostForTests: Boolean = false
)

class UpdaterSecurityException(message: String) : IOException(message)

object SharedHttpClients {

    private val timeoutInterceptor = Interceptor { chain ->
        val spec = chain.request().tag(RequestTimeoutSpec::class.java)
        var scopedChain = chain
        if (spec != null) {
            spec.connectTimeoutSeconds?.let {
                scopedChain = scopedChain.withConnectTimeout(it.coerceIn(1, 300), TimeUnit.SECONDS)
            }
            spec.readTimeoutSeconds?.let {
                scopedChain = scopedChain.withReadTimeout(it.coerceIn(1, 300), TimeUnit.SECONDS)
            }
            spec.writeTimeoutSeconds?.let {
                scopedChain = scopedChain.withWriteTimeout(it.coerceIn(1, 300), TimeUnit.SECONDS)
            }
        }
        scopedChain.proceed(scopedChain.request())
    }

    private val urlPolicyNetworkInterceptor = Interceptor { chain ->
        val request = chain.request()
        val policy = request.tag(UrlAllowlistPolicy::class.java)
        if (policy != null) {
            validateUrlAgainstPolicy(request.url, policy)
        }
        chain.proceed(request)
    }

    internal fun validateUrlAgainstPolicy(url: HttpUrl, policy: UrlAllowlistPolicy) {
        val host = url.host.lowercase()
        val isHttps = url.scheme.equals("https", ignoreCase = true)
        val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        val isLocalAllowed = policy.allowLocalhostForTests &&
            url.scheme.equals("http", ignoreCase = true) &&
            isLoopback

        if (!isHttps && !isLocalAllowed) {
            throw UpdaterSecurityException(
                "Conexão recusada: apenas URLs HTTPS são permitidas (${url.host})."
            )
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw UpdaterSecurityException(
                "URL recusada por conter credenciais embutidas (${url.host})."
            )
        }
        if (!isLocalAllowed) {
            val hostAllowed = policy.allowedHostSuffixes.any { suffix ->
                val clean = suffix.lowercase()
                host == clean || host.endsWith(".$clean")
            }
            if (!hostAllowed) {
                throw UpdaterSecurityException(
                    "Host não autorizado para atualização ($host)."
                )
            }
        }
    }

    internal fun validateResponseRedirectChain(
        response: Response,
        policy: UrlAllowlistPolicy
    ) {
        var current: Response? = response
        while (current != null) {
            validateUrlAgainstPolicy(current.request.url, policy)
            current = current.priorResponse
        }
    }

    val defaultClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("x-api-key")
            redactHeader("api-key")
            redactHeader("x-goog-api-key")
            redactHeader("X-Goog-Api-Key")
            redactHeader("proxy-authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
        }
        val dispatcher = Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 5
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(timeoutInterceptor)
            .addInterceptor(logging)
            .addNetworkInterceptor(urlPolicyNetworkInterceptor)
            .build()
    }
}
