package com.claytonmuhoza.easybilling.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.data.local.configuration.SecretStoreCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The EBMS settings, the credentials and the log of the exchanges, on a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class EbmsStorageTest {

    private val storage = LocalStorage.inMemory(ApplicationProvider.getApplicationContext())

    private class InMemorySecretStore : SecretStore {
        val secrets = mutableMapOf<String, ByteArray>()

        override fun read(name: String): ByteArray? = secrets[name]

        override fun write(name: String, value: ByteArray) {
            secrets[name] = value
        }
    }

    private val secretStore = InMemorySecretStore()
    private val vault = SecretStoreCredentialsVault(secretStore)

    @After
    fun closeDatabase() = storage.close()

    @Test
    fun `a new device has the default settings`() = runTest {
        assertEquals(EbmsSettings(), storage.ebmsSettings.read())
    }

    @Test
    fun `the settings are read back with every field`() = runTest {
        val settings = EbmsSettings(
            activeEnvironment = EbmsEnvironment.PRODUCTION,
            serverUrls = mapOf(
                EbmsEnvironment.TEST to "https://test.example.bi/api/",
                EbmsEnvironment.PRODUCTION to "https://prod.example.bi/api/",
            ),
            lastSuccessfulLogins = mapOf(
                EbmsEnvironment.TEST to Instant.parse("2026-10-03T08:00:00.123Z"),
                EbmsEnvironment.PRODUCTION to Instant.parse("2026-10-04T09:30:00Z"),
            ),
            exchangeRetentionDays = 30,
        )

        storage.ebmsSettings.write(settings)

        assertEquals(settings, storage.ebmsSettings.read())
    }

    @Test
    fun `settings without production address nor success are read back as such`() = runTest {
        val settings = EbmsSettings(exchangeRetentionDays = 7)

        storage.ebmsSettings.write(settings)
        storage.ebmsSettings.write(settings.copy(exchangeRetentionDays = 8))

        assertEquals(settings.copy(exchangeRetentionDays = 8), storage.ebmsSettings.read())
    }

    @Test
    fun `the credentials of each environment are kept apart in the secret store`() = runTest {
        val test = EbmsCredentials("ws1", """ p"a ss é """)
        val production = EbmsCredentials("ws2", "prod-pass")

        vault.write(EbmsEnvironment.TEST, test)
        vault.write(EbmsEnvironment.PRODUCTION, production)

        assertEquals(test, vault.read(EbmsEnvironment.TEST))
        assertEquals(production, vault.read(EbmsEnvironment.PRODUCTION))
        assertEquals(2, secretStore.secrets.size)
    }

    @Test
    fun `an environment without credentials has none`() = runTest {
        assertNull(vault.read(EbmsEnvironment.PRODUCTION))
    }

    private fun exchange(startedAt: String, statusCode: Int? = 200) = ObrExchange(
        startedAt = Instant.parse(startedAt),
        environment = EbmsEnvironment.TEST,
        service = "login/",
        requestBody = """{"username":"ws1","password":"••••"}""",
        statusCode = statusCode,
        responseBody = if (statusCode == null) "" else """{"success":true}""",
        durationMillis = 230,
        failure = if (statusCode == null) "SocketTimeoutException: timeout" else "",
    )

    @Test
    fun `an exchange is read back with every field`() = runTest {
        val id = storage.exchangeLog.record(exchange("2026-10-03T08:00:00.250Z"))
        val failedId = storage.exchangeLog.record(exchange("2026-10-03T08:01:00Z", statusCode = null))

        val failed = exchange("2026-10-03T08:01:00Z", statusCode = null)

        assertEquals(exchange("2026-10-03T08:00:00.250Z").copy(id = id), storage.exchangeLog.find(id))
        assertEquals(failed.copy(id = failedId), storage.exchangeLog.find(failedId))
    }

    @Test
    fun `the recent exchanges come newest first, up to the limit`() = runTest {
        storage.exchangeLog.record(exchange("2026-10-01T08:00:00Z"))
        storage.exchangeLog.record(exchange("2026-10-03T08:00:00Z"))
        storage.exchangeLog.record(exchange("2026-10-02T08:00:00Z"))

        val recent = storage.exchangeLog.recent(limit = 2)

        assertEquals(
            listOf(Instant.parse("2026-10-03T08:00:00Z"), Instant.parse("2026-10-02T08:00:00Z")),
            recent.map { it.startedAt },
        )
    }

    @Test
    fun `the exchanges older than a moment are deleted`() = runTest {
        storage.exchangeLog.record(exchange("2026-07-01T08:00:00Z"))
        storage.exchangeLog.record(exchange("2026-10-03T08:00:00Z"))

        assertEquals(1, storage.exchangeLog.deleteBefore(Instant.parse("2026-07-05T00:00:00Z")))
        assertEquals(1, storage.exchangeLog.recent(limit = 10).size)
    }

    @Test
    fun `an unknown exchange is not found`() = runTest {
        assertNull(storage.exchangeLog.find(42))
    }
}
