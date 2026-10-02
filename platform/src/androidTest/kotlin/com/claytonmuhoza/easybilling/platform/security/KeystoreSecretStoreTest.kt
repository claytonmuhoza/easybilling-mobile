package com.claytonmuhoza.easybilling.platform.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Runs on a device or emulator: the Android Keystore does not exist on the JVM. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = KeystoreSecretStore(context, FILE_NAME, KEY_ALIAS)
    private val secret = "a database key that must stay secret".toByteArray()

    private fun storedValue(name: String): String? =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(name, null)

    @After
    fun removeTestData() {
        context.deleteSharedPreferences(FILE_NAME)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    @Test
    fun an_unknown_secret_is_null() {
        assertNull(store.read("missing"))
    }

    @Test
    fun a_secret_is_read_back() {
        store.write("database_key", secret)

        assertContentEquals(secret, store.read("database_key"))
    }

    @Test
    fun a_secret_is_read_back_by_another_instance() {
        store.write("database_key", secret)

        assertContentEquals(secret, KeystoreSecretStore(context, FILE_NAME, KEY_ALIAS).read("database_key"))
    }

    @Test
    fun a_secret_is_replaced() {
        store.write("database_key", secret)
        store.write("database_key", byteArrayOf(1, 2, 3))

        assertContentEquals(byteArrayOf(1, 2, 3), store.read("database_key"))
    }

    @Test
    fun the_secret_is_not_stored_in_clear() {
        store.write("database_key", secret)

        val stored = assertNotNull(storedValue("database_key"))
        val storedBytes = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        assertFalse(String(secret) in String(storedBytes, Charsets.ISO_8859_1))
    }

    @Test
    fun the_same_secret_written_twice_is_encrypted_differently() {
        store.write("first", secret)
        store.write("second", secret)

        assertNotEquals(storedValue("first"), storedValue("second"))
    }

    private companion object {
        const val FILE_NAME = "secret_store_test"
        const val KEY_ALIAS = "easybilling_secret_store_test"
    }
}
