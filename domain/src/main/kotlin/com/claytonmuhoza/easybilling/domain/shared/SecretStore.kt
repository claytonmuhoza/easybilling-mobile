package com.claytonmuhoza.easybilling.domain.shared

/**
 * Storage for small secrets, encrypted on the device with a key that never leaves it.
 *
 * Calls block while the device encrypts or decrypts: never call them from the main thread.
 */
interface SecretStore {
    /**
     * Reads a secret.
     *
     * @param name the name the secret was written under.
     * @return the secret, or null if none was written under that name.
     */
    fun read(name: String): ByteArray?

    /**
     * Writes a secret, replacing any previous value.
     *
     * @param name the name to store the secret under.
     * @param value the secret.
     */
    fun write(name: String, value: ByteArray)
}
