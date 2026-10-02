package com.claytonmuhoza.easybilling.platform.security

import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Pbkdf2SecretHasherTest {

    /** Few iterations keep the tests fast; the algorithm is the same. */
    private val hasher = Pbkdf2SecretHasher(iterations = 1_000)

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `the right secret is verified`() = runTest {
        val hash = hasher.hash("123456")

        assertTrue(hasher.verify("123456", hash))
    }

    @Test
    fun `a wrong secret is not verified`() = runTest {
        val hash = hasher.hash("123456")

        assertFalse(hasher.verify("123457", hash))
        assertFalse(hasher.verify("", hash))
    }

    @Test
    fun `a hash has a 16 byte salt, 32 derived bytes and its iteration count`() = runTest {
        val hash = hasher.hash("123456")

        assertEquals(16, hash.salt.size)
        assertEquals(32, hash.hash.size)
        assertEquals(1_000, hash.iterations)
    }

    @Test
    fun `the same secret hashed twice gives different salts and hashes`() = runTest {
        val first = hasher.hash("123456")
        val second = hasher.hash("123456")

        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.hash.contentEquals(second.hash))
    }

    @Test
    fun `the secret does not appear in what is stored`() = runTest {
        val hash = hasher.hash("123456")
        val stored = String(hash.salt + hash.hash, Charsets.ISO_8859_1)

        assertFalse("123456" in stored)
    }

    @Test
    fun `verification uses the iteration count stored with the hash`() = runTest {
        val oldHash = hasher.hash("123456")
        val strongerHasher = Pbkdf2SecretHasher(iterations = 5_000)

        assertTrue(strongerHasher.verify("123456", oldHash))
    }

    @Test
    fun `the default work factor is 100 000 iterations`() = runTest {
        assertEquals(100_000, Pbkdf2SecretHasher().hash("123456").iterations)
    }

    @Test
    fun `the derivation is standard PBKDF2 with HMAC SHA-256`() = runTest {
        // Published test vector: password "password", salt "salt", 1 iteration, 32 bytes.
        val expected = SecretHash(
            salt = "salt".toByteArray(),
            hash = hex("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"),
            iterations = 1,
        )

        assertTrue(hasher.verify("password", expected))
    }
}
