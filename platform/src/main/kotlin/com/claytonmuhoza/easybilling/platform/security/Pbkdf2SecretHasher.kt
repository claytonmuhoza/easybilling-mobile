package com.claytonmuhoza.easybilling.platform.security

import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.SecretHasher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * [SecretHasher] based on PBKDF2 with HMAC SHA-256, from the cryptography of the platform.
 *
 * @param iterations work factor of new hashes. Existing hashes are verified with their own.
 * @param dispatcher where the derivation runs: it is CPU heavy and must stay off the main thread.
 */
class Pbkdf2SecretHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SecretHasher {

    private val random = SecureRandom()

    override suspend fun hash(secret: String): SecretHash = withContext(dispatcher) {
        val salt = ByteArray(SALT_SIZE_BYTES).also(random::nextBytes)
        SecretHash(salt, derive(secret, salt, iterations), iterations)
    }

    override suspend fun verify(secret: String, expected: SecretHash): Boolean = withContext(dispatcher) {
        // Constant-time comparison: the time taken says nothing about how close the guess was.
        MessageDigest.isEqual(derive(secret, expected.salt, expected.iterations), expected.hash)
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, HASH_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS = 100_000
        const val SALT_SIZE_BYTES = 16
        const val HASH_SIZE_BITS = 256
    }
}
