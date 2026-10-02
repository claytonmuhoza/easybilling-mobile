package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.FixedLockoutPolicy
import com.claytonmuhoza.easybilling.domain.identity.LogIn
import com.claytonmuhoza.easybilling.domain.identity.ResetPin
import com.claytonmuhoza.easybilling.domain.identity.SecretGuard
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import java.time.Duration
import kotlin.test.assertIs

/** The identity use cases wired on in-memory fakes. */
class IdentityFixture {
    val time = MutableTimeProvider()
    val users = InMemoryUserRepository()
    val audit = RecordingAuditLog()
    val sessions = SessionManager(time, audit, Duration.ofMinutes(5))

    private val hasher = FakeSecretHasher()
    private val codes = SequentialRecoveryCodes()
    private val guard = SecretGuard(users, hasher, FixedLockoutPolicy(), time, audit, DirectTransactionRunner)

    val createAdministrator = CreateAdministrator(users, hasher, codes, audit, DirectTransactionRunner)
    val logIn = LogIn(users, guard, sessions)
    val resetPin = ResetPin(users, guard, hasher, codes, audit, DirectTransactionRunner)

    /** Creates the administrator "Alice" and fails the test if that is refused. */
    suspend fun administrator(pin: String = PIN): CreateAdministrator.Result.Created =
        assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", pin, pin))

    companion object {
        const val PIN = "123456"
        const val WRONG_PIN = "654321"
    }
}
