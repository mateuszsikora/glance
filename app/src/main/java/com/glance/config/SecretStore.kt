package com.glance.config

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android Keystore-backed store for credentials kept in SharedPreferences.
 */
internal class SecretStore(
    private val prefs: SharedPreferences
) {

    fun get(encryptedPreferenceKey: String, legacyPreferenceKey: String): String {
        val encrypted = prefs.getString(encryptedPreferenceKey, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { decrypt(encryptedPreferenceKey, encrypted) }
                .onFailure { Log.e(TAG, "Unable to decrypt stored credential", it) }
                .getOrDefault("")
        }

        val legacy = prefs.getString(legacyPreferenceKey, null).orEmpty()
        if (legacy.isNotEmpty()) {
            put(encryptedPreferenceKey, legacyPreferenceKey, legacy)
        }
        return legacy
    }

    fun put(encryptedPreferenceKey: String, legacyPreferenceKey: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit()
                .remove(encryptedPreferenceKey)
                .remove(legacyPreferenceKey)
                .apply()
            return
        }

        val encrypted = encrypt(encryptedPreferenceKey, value)
        prefs.edit()
            .putString(encryptedPreferenceKey, encrypted)
            .remove(legacyPreferenceKey)
            .apply()
    }

    private fun encrypt(preferenceKey: String, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(preferenceKey.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        ).joinToString(SEPARATOR)
    }

    private fun decrypt(preferenceKey: String, encoded: String): String {
        val parts = encoded.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "Invalid encrypted credential format" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(preferenceKey.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val TAG = "SecretStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "glance_config_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SEPARATOR = ":"
    }
}
