package com.claytonmuhoza.easybilling.domain.identity

/**
 * A secret (PIN or recovery code) in the only form that is stored: derived, never in clear.
 *
 * @property salt random bytes mixed with the secret, unique to this hash.
 * @property hash the derived bytes.
 * @property iterations work factor used for the derivation, kept so it can be raised later.
 */
class SecretHash(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

/** Derives secrets into [SecretHash] and checks a typed secret against a stored hash. */
interface SecretHasher {
    /**
     * Derives a secret with a fresh salt.
     *
     * @param secret the secret in clear.
     * @return the hash to store.
     */
    suspend fun hash(secret: String): SecretHash

    /**
     * Checks a typed secret.
     *
     * @param secret the secret in clear, as typed.
     * @param expected the stored hash.
     * @return true if [secret] is the one [expected] was derived from.
     */
    suspend fun verify(secret: String, expected: SecretHash): Boolean
}
