package com.claytonmuhoza.easybilling.domain.customer

import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsServerAddress
import com.claytonmuhoza.easybilling.domain.testing.FakeTinLookup
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.TokenProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checking a TIN with the OBR (UC-B06): token, single retry after a refused token, failures. */
class VerifyTinTest {

    private var target: LoginTarget? = LoginTarget(
        EbmsEnvironment.TEST,
        EbmsServerAddress.DEFAULT_TEST,
        EbmsCredentials("ws1", "pass"),
    )
    private var logins = 0
    private var invalidations = 0
    private var login: () -> LoginOutcome = { LoginOutcome.Success("token-${++logins}") }
    private val tokens = object : TokenProvider {
        override suspend fun token(): LoginOutcome = login()

        override fun invalidate() {
            invalidations++
        }
    }
    private val lookup = FakeTinLookup()
    private val verify = VerifyTin({ target }, tokens, lookup)

    @Test
    fun `a TIN known by the OBR gives the name of its owner`() = runTest {
        lookup.known["4000202020"] = "NDIKUMANA Jean Marie"

        assertEquals(TinCheck.Known("NDIKUMANA Jean Marie"), verify("4000202020"))
        assertEquals(listOf("token-1" to "4000202020"), lookup.calls)
    }

    @Test
    fun `a TIN the OBR does not know is unknown`() = runTest {
        assertEquals(TinCheck.Unknown, verify("999"))
    }

    @Test
    fun `nothing is sent while the connection is not configured`() = runTest {
        target = null

        assertEquals(TinCheck.Failed(TinCheckFailure.NOT_CONFIGURED), verify("4000202020"))
        assertEquals(0, logins)
        assertEquals(emptyList(), lookup.calls)
    }

    @Test
    fun `a failed login tells why the TIN could not be checked`() = runTest {
        val expected = mapOf(
            LoginOutcome.NotConfigured to TinCheckFailure.NOT_CONFIGURED,
            LoginOutcome.Unreachable to TinCheckFailure.UNREACHABLE,
            LoginOutcome.ServerError to TinCheckFailure.SERVER_ERROR,
            LoginOutcome.CertificateRejected to TinCheckFailure.CERTIFICATE_REJECTED,
            LoginOutcome.WrongCredentials to TinCheckFailure.LOGIN_REFUSED,
            LoginOutcome.Refused("msg") to TinCheckFailure.LOGIN_REFUSED,
            LoginOutcome.InvalidResponse to TinCheckFailure.INVALID_RESPONSE,
        )
        for ((outcome, failure) in expected) {
            login = { outcome }
            assertEquals(TinCheck.Failed(failure), verify("4000202020"), "$outcome")
        }
        assertEquals(emptyList(), lookup.calls)
    }

    @Test
    fun `a refused token is renewed and the check is sent again once`() = runTest {
        lookup.known["4000202020"] = "NDIKUMANA Jean Marie"
        lookup.forced += TinReply.TokenRefused

        assertEquals(TinCheck.Known("NDIKUMANA Jean Marie"), verify("4000202020"))
        assertEquals(1, invalidations)
        assertEquals(listOf("token-1" to "4000202020", "token-2" to "4000202020"), lookup.calls)
    }

    @Test
    fun `a token refused twice is not tried a third time`() = runTest {
        lookup.forced += listOf(TinReply.TokenRefused, TinReply.TokenRefused)

        assertEquals(TinCheck.Failed(TinCheckFailure.LOGIN_REFUSED), verify("4000202020"))
        assertEquals(2, lookup.calls.size)
        assertEquals(2, invalidations)
    }

    @Test
    fun `the failures of the call are kept apart`() = runTest {
        val expected = mapOf(
            TinReply.Unreachable to TinCheck.Failed(TinCheckFailure.UNREACHABLE),
            TinReply.ServerError to TinCheck.Failed(TinCheckFailure.SERVER_ERROR),
            TinReply.CertificateRejected to TinCheck.Failed(TinCheckFailure.CERTIFICATE_REJECTED),
            TinReply.InvalidResponse to TinCheck.Failed(TinCheckFailure.INVALID_RESPONSE),
            TinReply.Refused("Veuillez fournir le NIF du contribuable.") to
                TinCheck.Failed(TinCheckFailure.REFUSED, "Veuillez fournir le NIF du contribuable."),
        )
        for ((reply, check) in expected) {
            lookup.forced += reply
            assertEquals(check, verify("4000202020"), "$reply")
        }
    }
}
