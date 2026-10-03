package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.testing.FakeObrAuthentication
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import com.claytonmuhoza.easybilling.domain.testing.InMemoryCredentialsVault
import com.claytonmuhoza.easybilling.domain.testing.InMemoryEbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Saving the connection, switching the environment and testing the connection (UC-A03 to A05). */
class EbmsConnectionUseCasesTest {

    private val identity = IdentityFixture()
    private val settings = InMemoryEbmsSettingsRepository()
    private val vault = InMemoryCredentialsVault()
    private val authentication = FakeObrAuthentication()

    private val save = SaveEbmsConnection(settings, vault, identity.audit)
    private val switch = SwitchEbmsEnvironment(settings, identity.users, identity.guard, identity.audit)
    private val test = TestEbmsConnection(settings, vault, authentication, identity.time, identity.audit)

    private val alice = UserId(1)
    private val testCredentials = EbmsCredentials("ws1", "test-pass")
    private val form = EbmsConnectionForm(
        serverUrl = EbmsServerAddress.DEFAULT_TEST,
        username = "ws1",
        password = "test-pass",
    )

    @Test
    fun `a new device uses the test environment and the test address of the specification`() = runTest {
        val initial = settings.read()

        assertEquals(EbmsEnvironment.TEST, initial.activeEnvironment)
        assertEquals(EbmsServerAddress.DEFAULT_TEST, initial.serverUrl(EbmsEnvironment.TEST))
        assertNull(initial.serverUrl(EbmsEnvironment.PRODUCTION))
        assertEquals(90, initial.exchangeRetentionDays)
    }

    @Test
    fun `saving stores the address and the credentials of one environment`() = runTest {
        assertIs<SaveEbmsConnection.Result.Saved>(save(EbmsEnvironment.TEST, form, alice))

        assertEquals(testCredentials, vault.read(EbmsEnvironment.TEST))
        assertNull(vault.read(EbmsEnvironment.PRODUCTION))
        assertEquals(EbmsServerAddress.DEFAULT_TEST, settings.read().serverUrl(EbmsEnvironment.TEST))
    }

    @Test
    fun `the two environments keep their own credentials`() = runTest {
        save(EbmsEnvironment.TEST, form, alice)
        val production = form.copy(
            serverUrl = "https://ebms.example.bi/api/",
            username = "prod",
            password = "prod-pass",
        )

        save(EbmsEnvironment.PRODUCTION, production, alice)

        assertEquals(testCredentials, vault.read(EbmsEnvironment.TEST))
        assertEquals(EbmsCredentials("prod", "prod-pass"), vault.read(EbmsEnvironment.PRODUCTION))
        assertEquals("https://ebms.example.bi/api/", settings.read().serverUrl(EbmsEnvironment.PRODUCTION))
    }

    @Test
    fun `an empty password keeps the stored one`() = runTest {
        save(EbmsEnvironment.TEST, form, alice)

        save(EbmsEnvironment.TEST, form.copy(username = "ws2", password = ""), alice)

        assertEquals(EbmsCredentials("ws2", "test-pass"), vault.read(EbmsEnvironment.TEST))
    }

    @Test
    fun `an empty password is missing when none is stored`() = runTest {
        val result = assertIs<SaveEbmsConnection.Result.Invalid>(
            save(EbmsEnvironment.TEST, form.copy(password = ""), alice),
        )

        assertEquals(mapOf(EbmsCredentialField.PASSWORD to FieldProblem.Missing), result.problems)
        assertNull(vault.read(EbmsEnvironment.TEST))
    }

    @Test
    fun `saving is audited without any secret`() = runTest {
        save(EbmsEnvironment.TEST, form, alice)

        val event = identity.audit.events.single()
        assertEquals(AuditEvent.EbmsConnectionSaved(alice, EbmsEnvironment.TEST), event)
        assertEquals("environment=TEST", event.details)
        assertFalse("test-pass" in event.details)
    }

    @Test
    fun `new credentials must be tested again before they count as working`() = runTest {
        save(EbmsEnvironment.TEST, form, alice)
        test(alice)

        save(EbmsEnvironment.TEST, form.copy(password = "new-pass"), alice)

        assertNull(settings.read().lastSuccessfulLogin(EbmsEnvironment.TEST))
    }

    @Test
    fun `switching to production requires the PIN of the user`() = runTest {
        val alice = identity.administrator().user

        val result = switch(EbmsEnvironment.PRODUCTION, alice.id, IdentityFixture.PIN)

        assertEquals(SwitchEbmsEnvironment.Result.Switched, result)
        assertEquals(EbmsEnvironment.PRODUCTION, settings.read().activeEnvironment)
        assertEquals(
            AuditEvent.EbmsEnvironmentChanged(alice.id, EbmsEnvironment.TEST, EbmsEnvironment.PRODUCTION),
            identity.audit.events.last(),
        )
        assertEquals("from=TEST;to=PRODUCTION", identity.audit.events.last().details)
    }

    @Test
    fun `a wrong PIN keeps the test environment and counts as a failed attempt`() = runTest {
        val alice = identity.administrator().user

        val result = switch(EbmsEnvironment.PRODUCTION, alice.id, IdentityFixture.WRONG_PIN)

        assertEquals(SwitchEbmsEnvironment.Result.WrongPin(remainingAttempts = 9), result)
        assertEquals(EbmsEnvironment.TEST, settings.read().activeEnvironment)
        assertEquals(1, identity.users.find(alice.id)?.loginGuard?.failedAttempts)
    }

    @Test
    fun `a locked account cannot switch to production`() = runTest {
        val alice = identity.administrator().user
        repeat(10) { switch(EbmsEnvironment.PRODUCTION, alice.id, IdentityFixture.WRONG_PIN) }

        val result = switch(EbmsEnvironment.PRODUCTION, alice.id, IdentityFixture.PIN)

        assertIs<SwitchEbmsEnvironment.Result.Locked>(result)
        assertEquals(EbmsEnvironment.TEST, settings.read().activeEnvironment)
    }

    @Test
    fun `going back to test needs no PIN`() = runTest {
        val alice = identity.administrator().user
        switch(EbmsEnvironment.PRODUCTION, alice.id, IdentityFixture.PIN)

        assertEquals(SwitchEbmsEnvironment.Result.Switched, switch(EbmsEnvironment.TEST, alice.id, pin = null))
        assertEquals(EbmsEnvironment.TEST, settings.read().activeEnvironment)
    }

    @Test
    fun `choosing the active environment changes nothing`() = runTest {
        val alice = identity.administrator().user
        val eventsBefore = identity.audit.events.size

        assertEquals(SwitchEbmsEnvironment.Result.Unchanged, switch(EbmsEnvironment.TEST, alice.id, pin = null))
        assertEquals(eventsBefore, identity.audit.events.size)
    }

    @Test
    fun `a connection cannot be tested before it is configured`() = runTest {
        assertEquals(LoginOutcome.NotConfigured, test(alice))
        assertEquals(emptyList(), authentication.calls)
    }

    @Test
    fun `the test logs in to the active environment with its own address and credentials`() = runTest {
        save(EbmsEnvironment.TEST, form, alice)

        test(alice)

        assertEquals(
            listOf(LoginTarget(EbmsEnvironment.TEST, EbmsServerAddress.DEFAULT_TEST, testCredentials)),
            authentication.calls,
        )
    }

    @Test
    fun `a successful test is remembered and audited`() = runTest {
        save(EbmsEnvironment.TEST, form, alice)

        assertIs<LoginOutcome.Success>(test(alice))

        assertEquals(Instant.parse("2026-10-02T08:00:00Z"), settings.read().lastSuccessfulLogin(EbmsEnvironment.TEST))
        assertEquals(
            AuditEvent.EbmsConnectionTested(alice, EbmsEnvironment.TEST, outcome = "SUCCESS"),
            identity.audit.events.last(),
        )
        assertEquals("environment=TEST;outcome=SUCCESS", identity.audit.events.last().details)
    }

    @Test
    fun `each failure is returned and audited, and is not remembered as a success`() = runTest {
        save(EbmsEnvironment.TEST, form, alice)
        val failures = listOf(
            LoginOutcome.WrongCredentials to "WRONG_CREDENTIALS",
            LoginOutcome.Refused("Veuillez fournir un mot de passe.") to "REFUSED",
            LoginOutcome.ServerError to "SERVER_ERROR",
            LoginOutcome.Unreachable to "UNREACHABLE",
            LoginOutcome.CertificateRejected to "CERTIFICATE_REJECTED",
            LoginOutcome.InvalidResponse to "INVALID_RESPONSE",
        )

        for ((outcome, code) in failures) {
            authentication.outcome = outcome

            assertEquals(outcome, test(alice))
            assertEquals("environment=TEST;outcome=$code", identity.audit.events.last().details)
        }
        assertNull(settings.read().lastSuccessfulLogin(EbmsEnvironment.TEST))
    }
}
