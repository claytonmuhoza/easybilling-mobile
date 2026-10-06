package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.identity.AccessGuard
import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.CreateUser
import com.claytonmuhoza.easybilling.domain.identity.FixedLockoutPolicy
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.LogIn
import com.claytonmuhoza.easybilling.domain.identity.ResetPin
import com.claytonmuhoza.easybilling.domain.identity.ResetUserPin
import com.claytonmuhoza.easybilling.domain.identity.SecretGuard
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.SetUserActive
import com.claytonmuhoza.easybilling.domain.identity.SwitchUser
import com.claytonmuhoza.easybilling.domain.identity.UpdateUser
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserForm
import com.claytonmuhoza.easybilling.domain.identity.UserId
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
    val guard = SecretGuard(users, hasher, FixedLockoutPolicy(), time, audit, DirectTransactionRunner)
    val access = AccessGuard(users, FixedPermissionPolicy, audit)

    val createAdministrator = CreateAdministrator(users, hasher, codes, audit, DirectTransactionRunner)
    val logIn = LogIn(users, guard, sessions)
    val resetPin = ResetPin(users, guard, hasher, codes, audit, DirectTransactionRunner)
    val createUser = CreateUser(users, hasher, access, audit, DirectTransactionRunner)
    val updateUser = UpdateUser(users, sessions, access, audit, DirectTransactionRunner)
    val setUserActive = SetUserActive(users, access, audit, DirectTransactionRunner)
    val resetUserPin = ResetUserPin(users, hasher, access, audit, DirectTransactionRunner)
    val changeTemporaryPin = ChangeTemporaryPin(users, guard, hasher, sessions, audit, DirectTransactionRunner)
    val switchUser = SwitchUser(users, guard, sessions)
    val authorizeSensitiveAction = AuthorizeSensitiveAction(users, guard, FixedPermissionPolicy, audit)

    /** Asks [approver] to approve [action] of [requester] with [pin]. */
    suspend fun authorize(requester: UserId, approver: UserId, pin: String, action: SensitiveAction) =
        authorizeSensitiveAction(requester, approver, pin, action)

    /** Creates the administrator "Alice" and fails the test if that is refused. */
    suspend fun administrator(pin: String = PIN): CreateAdministrator.Result.Created =
        assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", pin, pin))

    /**
     * Creates an account with the temporary PIN [TEMPORARY_PIN] and fails the test if that is
     * refused.
     */
    suspend fun user(form: UserForm, actor: UserId): User =
        assertIs<CreateUser.Result.Created>(createUser(form, TEMPORARY_PIN, TEMPORARY_PIN, actor)).user

    companion object {
        const val PIN = "123456"
        const val WRONG_PIN = "654321"
        const val TEMPORARY_PIN = "111111"
    }
}
