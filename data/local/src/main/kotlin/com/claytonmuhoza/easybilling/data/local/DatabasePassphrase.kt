package com.claytonmuhoza.easybilling.data.local

import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import java.security.SecureRandom

/**
 * The key that encrypts the local database: random, created on first use, and kept in the
 * [SecretStore] so it never exists in clear on disk.
 *
 * @param secretStore where the key is kept.
 */
class DatabasePassphrase(private val secretStore: SecretStore) {

    /**
     * Blocks while the device decrypts the key: never call it from the main thread.
     *
     * @return the key of the database, created and stored if this is the first call on the device.
     */
    @Synchronized
    fun get(): ByteArray =
        secretStore.read(SECRET_NAME) ?: ByteArray(KEY_SIZE_BYTES).also { key ->
            SecureRandom().nextBytes(key)
            secretStore.write(SECRET_NAME, key)
        }

    private companion object {
        const val SECRET_NAME = "database_key"
        const val KEY_SIZE_BYTES = 32
    }
}
