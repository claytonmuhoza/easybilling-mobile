package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.TEMPORARY_PIN
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserAccountsTest {

    private val fixture = IdentityFixture()

    private val cashier = UserForm("Bob", setOf(Role.CASHIER))

    private suspend fun admin(): UserId = fixture.administrator().user.id

    @Test
    fun `an administrator creates an account with a temporary PIN and no recovery code`() = runTest {
        val admin = admin()

        val form = UserForm("  Bob ", setOf(Role.CASHIER, Role.STOREKEEPER))
        val result = fixture.createUser(form, TEMPORARY_PIN, TEMPORARY_PIN, admin)

        val user = assertIs<CreateUser.Result.Created>(result).user
        assertEquals("Bob", user.name)
        assertEquals(setOf(Role.CASHIER, Role.STOREKEEPER), user.roles)
        assertTrue(user.active)
        assertTrue(user.pinTemporary)
        assertNull(user.credentials.recoveryHash)
        assertEquals(user, fixture.users.find(user.id))
        assertEquals(
            AuditEvent.UserCreated(admin, user.id, setOf(Role.CASHIER, Role.STOREKEEPER)),
            fixture.audit.events.last(),
        )
    }

    @Test
    fun `an account needs a name, of fifty characters at most`() = runTest {
        val admin = admin()

        assertEquals(
            CreateUser.Result.Refused(UserProblem.NAME_BLANK),
            fixture.createUser(UserForm("  ", setOf(Role.CASHIER)), PIN, PIN, admin),
        )
        assertEquals(
            CreateUser.Result.Refused(UserProblem.NAME_TOO_LONG),
            fixture.createUser(UserForm("a".repeat(51), setOf(Role.CASHIER)), PIN, PIN, admin),
        )
        assertEquals(1, fixture.users.count())
    }

    @Test
    fun `two accounts cannot have the same name, whatever the case`() = runTest {
        val admin = admin()

        assertEquals(
            CreateUser.Result.Refused(UserProblem.NAME_TAKEN),
            fixture.createUser(UserForm(" alice", setOf(Role.CASHIER)), PIN, PIN, admin),
        )
    }

    @Test
    fun `an account needs at least one role`() = runTest {
        assertEquals(
            CreateUser.Result.Refused(UserProblem.NO_ROLE),
            fixture.createUser(UserForm("Bob", emptySet()), PIN, PIN, admin()),
        )
    }

    @Test
    fun `the temporary PIN follows the PIN rule`() = runTest {
        assertEquals(
            CreateUser.Result.InvalidPin(PinProblem.TOO_SHORT),
            fixture.createUser(cashier, "123", "123", admin()),
        )
    }

    @Test
    fun `only someone who manages the users creates an account`() = runTest {
        val bob = fixture.user(cashier, admin())

        assertFailsWith<PermissionDeniedException> {
            fixture.createUser(UserForm("Carol", setOf(Role.CASHIER)), PIN, PIN, bob.id)
        }
        assertEquals(2, fixture.users.count())
    }

    @Test
    fun `the name and the roles of an account are changed`() = runTest {
        val admin = admin()
        val bob = fixture.user(cashier, admin)

        val result = fixture.updateUser(bob.id, UserForm("Robert", setOf(Role.MANAGER)), admin)

        val updated = assertIs<UpdateUser.Result.Updated>(result).user
        assertEquals("Robert", updated.name)
        assertEquals(setOf(Role.MANAGER), updated.roles)
        assertEquals(updated, fixture.users.find(bob.id))
        assertEquals(AuditEvent.UserUpdated(admin, bob.id, setOf(Role.MANAGER)), fixture.audit.events.last())
    }

    @Test
    fun `an account keeps its own name when it is changed`() = runTest {
        val admin = admin()
        val bob = fixture.user(cashier, admin)

        assertIs<UpdateUser.Result.Updated>(fixture.updateUser(bob.id, UserForm("BOB", setOf(Role.CASHIER)), admin))
        assertEquals(
            UpdateUser.Result.Refused(UserProblem.NAME_TAKEN),
            fixture.updateUser(bob.id, UserForm("Alice", setOf(Role.CASHIER)), admin),
        )
    }

    @Test
    fun `the last active administrator keeps the administrator role`() = runTest {
        val admin = admin()

        assertEquals(
            UpdateUser.Result.Refused(UserProblem.LAST_ADMINISTRATOR),
            fixture.updateUser(admin, UserForm("Alice", setOf(Role.MANAGER)), admin),
        )
    }

    @Test
    fun `an administrator may leave the role once there is another one`() = runTest {
        val admin = admin()
        fixture.user(UserForm("Bob", setOf(Role.ADMINISTRATOR)), admin)

        assertIs<UpdateUser.Result.Updated>(fixture.updateUser(admin, UserForm("Alice", setOf(Role.MANAGER)), admin))
    }

    @Test
    fun `changing the roles of the logged in user changes the session`() = runTest {
        val admin = admin()
        fixture.logIn(admin, PIN)

        fixture.updateUser(admin, UserForm("Alicia", setOf(Role.ADMINISTRATOR, Role.CASHIER)), admin)

        assertEquals(
            Session(admin, "Alicia", setOf(Role.ADMINISTRATOR, Role.CASHIER)),
            fixture.sessions.current.value,
        )
    }

    @Test
    fun `changing an unknown account is refused`() = runTest {
        assertEquals(UpdateUser.Result.UnknownUser, fixture.updateUser(UserId(99), cashier, admin()))
    }

    @Test
    fun `an account is disabled and enabled again`() = runTest {
        val admin = admin()
        val bob = fixture.user(cashier, admin)

        val disabled = assertIs<SetUserActive.Result.Done>(fixture.setUserActive(bob.id, false, admin)).user
        assertFalse(disabled.active)
        assertEquals(AuditEvent.UserActiveChanged(admin, bob.id, false), fixture.audit.events.last())

        val enabled = assertIs<SetUserActive.Result.Done>(fixture.setUserActive(bob.id, true, admin)).user
        assertTrue(enabled.active)
        assertEquals(2, fixture.users.count())
    }

    @Test
    fun `nobody disables their own account`() = runTest {
        val admin = admin()
        fixture.user(UserForm("Bob", setOf(Role.ADMINISTRATOR)), admin)

        assertEquals(SetUserActive.Result.OwnAccount, fixture.setUserActive(admin, false, admin))
    }

    @Test
    fun `a disabled administrator does not count as an administrator`() = runTest {
        val admin = admin()
        val bob = fixture.user(UserForm("Bob", setOf(Role.ADMINISTRATOR)), admin)
        fixture.setUserActive(bob.id, false, admin)

        assertEquals(
            UpdateUser.Result.Refused(UserProblem.LAST_ADMINISTRATOR),
            fixture.updateUser(admin, UserForm("Alice", setOf(Role.MANAGER)), admin),
        )
    }

    @Test
    fun `an administrator gives a new temporary PIN to an account`() = runTest {
        val admin = admin()
        val bob = fixture.user(cashier, admin)
        fixture.changeTemporaryPin(bob.id, TEMPORARY_PIN, PIN, PIN)
        fixture.logIn(bob.id, "000000")

        val result = fixture.resetUserPin(bob.id, "777777", "777777", admin)

        assertEquals(ResetUserPin.Result.Reset, result)
        val stored = fixture.users.find(bob.id)!!
        assertTrue(stored.pinTemporary)
        assertEquals(LoginGuard(), stored.loginGuard)
        assertEquals(LogIn.Result.PinChangeRequired, fixture.logIn(bob.id, "777777"))
        assertEquals(AuditEvent.UserPinReset(admin, bob.id), fixture.audit.events.last())
    }

    @Test
    fun `a new temporary PIN follows the PIN rule`() = runTest {
        val admin = admin()
        val bob = fixture.user(cashier, admin)

        assertEquals(
            ResetUserPin.Result.InvalidPin(PinProblem.MISMATCH),
            fixture.resetUserPin(bob.id, "777777", "777778", admin),
        )
        assertEquals(ResetUserPin.Result.UnknownUser, fixture.resetUserPin(UserId(99), "777777", "777777", admin))
    }

    @Test
    fun `a manager cannot change, disable or reset an account`() = runTest {
        val admin = admin()
        val manager = fixture.user(UserForm("Marie", setOf(Role.MANAGER)), admin)
        val bob = fixture.user(cashier, admin)

        assertFailsWith<PermissionDeniedException> { fixture.updateUser(bob.id, cashier, manager.id) }
        assertFailsWith<PermissionDeniedException> { fixture.setUserActive(bob.id, false, manager.id) }
        assertFailsWith<PermissionDeniedException> { fixture.resetUserPin(bob.id, PIN, PIN, manager.id) }
    }
}
