package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsServerAddress
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** The EBMS token (UC-D01): valid 60 seconds, renewed after 45, one login at a time. */
class CachingTokenProviderTest {

    private val time = MutableTimeProvider()
    private var target: LoginTarget? = LoginTarget(
        EbmsEnvironment.TEST,
        EbmsServerAddress.DEFAULT_TEST,
        EbmsCredentials("ws1", "pass"),
    )
    private var logins = 0
    private var nextOutcome: (Int) -> LoginOutcome = { LoginOutcome.Success("token-$it") }
    private val authentication = ObrAuthentication { logins++; nextOutcome(logins) }
    private val provider = CachingTokenProvider({ target }, authentication, time)

    @Test
    fun `the first call logs in`() = runTest {
        assertEquals(LoginOutcome.Success("token-1"), provider.token())
        assertEquals(1, logins)
    }

    @Test
    fun `the token is reused up to 45 seconds`() = runTest {
        provider.token()

        time.advance(Duration.ofSeconds(44))
        assertEquals(LoginOutcome.Success("token-1"), provider.token())
        time.advance(Duration.ofSeconds(1))
        assertEquals(LoginOutcome.Success("token-1"), provider.token())

        assertEquals(1, logins)
    }

    @Test
    fun `a token older than 45 seconds is renewed`() = runTest {
        provider.token()

        time.advance(Duration.ofSeconds(46))

        assertEquals(LoginOutcome.Success("token-2"), provider.token())
        assertEquals(2, logins)
    }

    @Test
    fun `a refused token is renewed at the next call`() = runTest {
        provider.token()

        provider.invalidate()

        assertEquals(LoginOutcome.Success("token-2"), provider.token())
    }

    @Test
    fun `a failure is not kept and the next call tries again`() = runTest {
        nextOutcome = { if (it == 1) LoginOutcome.Unreachable else LoginOutcome.Success("token-$it") }

        assertEquals(LoginOutcome.Unreachable, provider.token())
        assertEquals(LoginOutcome.Success("token-2"), provider.token())
    }

    @Test
    fun `without connection settings nothing is called`() = runTest {
        target = null

        assertEquals(LoginOutcome.NotConfigured, provider.token())
        assertEquals(0, logins)
    }

    @Test
    fun `other settings get their own token`() = runTest {
        provider.token()

        target = target?.copy(environment = EbmsEnvironment.PRODUCTION, serverUrl = "https://ebms.example.bi/api/")

        assertEquals(LoginOutcome.Success("token-2"), provider.token())
    }

    @Test
    fun `calls made together share a single login`() = runTest {
        val answer = CompletableDeferred<LoginOutcome>()
        var slowLogins = 0
        val slow = CachingTokenProvider({ target }, { slowLogins++; answer.await() }, time)

        val calls = List(5) { async { slow.token() } }
        yield()
        answer.complete(LoginOutcome.Success("shared"))

        assertEquals(List(5) { LoginOutcome.Success("shared") }, calls.awaitAll())
        assertEquals(1, slowLogins)
    }
}
