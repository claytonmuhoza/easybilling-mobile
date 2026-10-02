package com.claytonmuhoza.easybilling.data.local

import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DatabasePassphraseTest {

    private class InMemorySecretStore : SecretStore {
        val secrets = mutableMapOf<String, ByteArray>()
        var writes = 0

        override fun read(name: String): ByteArray? = secrets[name]

        override fun write(name: String, value: ByteArray) {
            writes++
            secrets[name] = value
        }
    }

    private val store = InMemorySecretStore()

    @Test
    fun `the first call creates a 32 byte key and stores it`() {
        val key = DatabasePassphrase(store).get()

        assertEquals(32, key.size)
        assertContentEquals(key, store.secrets.values.single())
    }

    @Test
    fun `later calls return the stored key without writing again`() {
        val first = DatabasePassphrase(store).get()

        val second = DatabasePassphrase(store).get()

        assertContentEquals(first, second)
        assertEquals(1, store.writes)
    }

    @Test
    fun `two devices get different keys`() {
        val one = DatabasePassphrase(InMemorySecretStore()).get()
        val other = DatabasePassphrase(InMemorySecretStore()).get()

        assertFalse(one.contentEquals(other))
    }
}
