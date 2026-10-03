package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** Gives the token every call to the EBMS needs (UC-D01). */
interface TokenProvider {
    /** @return a token to send now, or why there is none. */
    suspend fun token(): LoginOutcome

    /** Forgets the current token, after the OBR refused it: the next call logs in again. */
    fun invalidate()
}

/**
 * [TokenProvider] that keeps the token and logs in again only when needed. The token is valid
 * 60 seconds; it is renewed once older than 45 seconds, so a call never starts with a token about
 * to expire. Only one login runs at a time: calls made together share it.
 *
 * @param target gives where and how to log in now, or null if the connection is not configured.
 * @param authentication the login to the OBR.
 * @param time source of the age of the token.
 */
class CachingTokenProvider(
    private val target: suspend () -> LoginTarget?,
    private val authentication: ObrAuthentication,
    private val time: TimeProvider,
) : TokenProvider {

    private class Cached(val target: LoginTarget, val token: LoginOutcome.Success, val obtainedAt: Instant)

    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    override suspend fun token(): LoginOutcome = mutex.withLock {
        val current = target() ?: return@withLock LoginOutcome.NotConfigured
        val kept = cached
        if (kept != null && kept.target == current && Duration.between(kept.obtainedAt, time.now()) <= MAX_AGE) {
            return@withLock kept.token
        }
        val outcome = authentication.login(current)
        cached = (outcome as? LoginOutcome.Success)?.let { Cached(current, it, time.now()) }
        outcome
    }

    override fun invalidate() {
        cached = null
    }

    private companion object {
        /** Age after which a token is renewed; the OBR keeps it valid 60 seconds. */
        val MAX_AGE: Duration = Duration.ofSeconds(45)
    }
}
