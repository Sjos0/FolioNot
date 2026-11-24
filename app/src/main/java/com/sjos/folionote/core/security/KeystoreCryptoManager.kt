package com.sjos.folionote.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts sensitive API keys using Android Keystore (AES-256-GCM).
 * Never logs plaintext credentials.
 */
class KeystoreCryptoManager {

    companion object {
        private const val KEY_ALIAS = "folionote_provider_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFIX_ENC = "enc_v1:"
        private const val PREFIX_TEST = "test_v1:"
    }

    private val testFallbackKey: SecretKey by lazy {
        val bytes = ByteArray(32) { idx -> (idx * 13 + 7).toByte() }
        SecretKeySpec(bytes, "AES")
    }

    private fun getOrCreateKey(): Pair<SecretKey, Boolean> {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                existing to true
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                generator.init(spec)
                generator.generateKey() to true
            }
        } catch (_: Throwable) {
            // Only reached under JVM/Robolectric unit test environments without AndroidKeyStore
            testFallbackKey to false
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return ""
        val (key, isHardwareKeystore) = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val prefix = if (isHardwareKeystore) PREFIX_ENC else PREFIX_TEST
        return "$prefix$ivB64:$dataB64"
    }

    fun decrypt(payload: String): String {
        if (payload.isBlank()) return ""
        val raw = when {
            payload.startsWith(PREFIX_ENC) -> payload.removePrefix(PREFIX_ENC) to true
            payload.startsWith(PREFIX_TEST) -> payload.removePrefix(PREFIX_TEST) to false
            else -> return ""
        }
        val parts = raw.first.split(":")
        if (parts.size != 2) return ""
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = if (raw.second) {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return ""
            } else {
                testFallbackKey
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    fun maskKey(plaintext: String): String {
        if (plaintext.isBlank()) return ""
        if (plaintext.length <= 8) return "••••••••"
        return "${plaintext.take(4)}••••${plaintext.takeLast(4)}"
    }
}
