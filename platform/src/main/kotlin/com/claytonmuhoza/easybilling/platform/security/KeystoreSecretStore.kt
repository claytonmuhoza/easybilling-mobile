package com.claytonmuhoza.easybilling.platform.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] that encrypts each secret with AES-GCM under a key held by the Android Keystore.
 * The key cannot be read or copied out of the device; only the encrypted values are on disk.
 *
 * @param context any context of the application.
 * @param fileName name of the preferences file holding the encrypted values.
 * @param keyAlias name of the key in the Android Keystore.
 */
class KeystoreSecretStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretStore {

    private val preferences = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(name: String): ByteArray? {
        val stored = preferences.getString(name, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE_BITS, bytes, 0, IV_SIZE_BYTES))
        return cipher.doFinal(bytes, IV_SIZE_BYTES, bytes.size - IV_SIZE_BYTES)
    }

    @Synchronized
    override fun write(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks a fresh random IV for every encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val stored = Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
        // commit, not apply: the secret must be on disk before anything is encrypted with it.
        check(preferences.edit().putString(name, stored).commit()) { "The secret could not be saved" }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as SecretKey? ?: createKey()
    }

    private fun createKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "secret_store"
        const val DEFAULT_KEY_ALIAS = "easybilling_secret_store"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
    }
}
