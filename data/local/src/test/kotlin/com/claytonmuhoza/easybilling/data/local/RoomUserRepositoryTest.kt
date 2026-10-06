package com.claytonmuhoza.easybilling.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.LoginGuard
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The user repository on a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class RoomUserRepositoryTest {

    private val storage = LocalStorage.inMemory(ApplicationProvider.getApplicationContext())
    private val users = storage.users

    private val alice = NewUser(
        name = "Alice",
        roles = setOf(Role.ADMINISTRATOR),
        credentials = Credentials(
            pinHash = SecretHash(salt = byteArrayOf(1, 2, 3), hash = byteArrayOf(4, 5, 6), iterations = 100_000),
            recoveryHash = SecretHash(salt = byteArrayOf(7, 8), hash = byteArrayOf(9, 10), iterations = 200_000),
        ),
    )

    @After
    fun closeDatabase() = storage.close()

    @Test
    fun `a new database has no user`() = runTest {
        assertEquals(0, users.count())
        assertEquals(emptyList(), users.findAll())
    }

    @Test
    fun `a created user is read back with every field`() = runTest {
        val created = users.create(alice)

        val read = assertNotNull(users.find(created.id))
        assertEquals("Alice", read.name)
        assertEquals(setOf(Role.ADMINISTRATOR), read.roles)
        assertContentEquals(byteArrayOf(1, 2, 3), read.credentials.pinHash.salt)
        assertContentEquals(byteArrayOf(4, 5, 6), read.credentials.pinHash.hash)
        assertEquals(100_000, read.credentials.pinHash.iterations)
        val recovery = assertNotNull(read.credentials.recoveryHash)
        assertContentEquals(byteArrayOf(7, 8), recovery.salt)
        assertContentEquals(byteArrayOf(9, 10), recovery.hash)
        assertEquals(200_000, recovery.iterations)
        assertEquals(LoginGuard(), read.loginGuard)
    }

    @Test
    fun `each created user gets its own identifier`() = runTest {
        val first = users.create(alice)
        val second = users.create(alice.copy(name = "Bob"))

        assertEquals(2, users.count())
        assertEquals(listOf(first.id, second.id), users.findAll().map { it.id })
        assertEquals(listOf("Alice", "Bob"), users.findAll().map { it.name })
    }

    @Test
    fun `an unknown user is not found`() = runTest {
        assertNull(users.find(UserId(42)))
    }

    @Test
    fun `the login guard is updated`() = runTest {
        val created = users.create(alice)
        val lockEnd = Instant.parse("2026-10-02T08:01:00.250Z")

        users.update(created.copy(loginGuard = LoginGuard(failedAttempts = 3, lockedUntil = lockEnd)))

        assertEquals(LoginGuard(failedAttempts = 3, lockedUntil = lockEnd), users.find(created.id)?.loginGuard)
    }

    @Test
    fun `the credentials are updated`() = runTest {
        val created = users.create(alice)
        val newCredentials = Credentials(
            pinHash = SecretHash(byteArrayOf(11), byteArrayOf(12), 1),
            recoveryHash = SecretHash(byteArrayOf(13), byteArrayOf(14), 2),
        )

        users.update(created.copy(credentials = newCredentials))

        val read = assertNotNull(users.find(created.id))
        assertContentEquals(byteArrayOf(12), read.credentials.pinHash.hash)
        assertContentEquals(byteArrayOf(14), read.credentials.recoveryHash?.hash)
    }

    @Test
    fun `a new user is active and keeps its own PIN`() = runTest {
        val read = assertNotNull(users.find(users.create(alice).id))

        assertTrue(read.active)
        assertFalse(read.pinTemporary)
    }

    @Test
    fun `several roles are read back`() = runTest {
        val created = users.create(alice.copy(name = "Bob", roles = setOf(Role.STOREKEEPER, Role.CASHIER)))

        assertEquals(setOf(Role.CASHIER, Role.STOREKEEPER), created.roles)
        assertEquals(setOf(Role.CASHIER, Role.STOREKEEPER), users.find(created.id)?.roles)
    }

    @Test
    fun `an account without a recovery code and with a temporary PIN is read back`() = runTest {
        val bob = NewUser(
            name = "Bob",
            roles = setOf(Role.CASHIER),
            credentials = Credentials(pinHash = alice.credentials.pinHash, recoveryHash = null),
            pinTemporary = true,
        )

        val created = users.create(bob)

        assertTrue(created.pinTemporary)
        val read = assertNotNull(users.find(created.id))
        assertNull(read.credentials.recoveryHash)
        assertTrue(read.pinTemporary)
    }

    @Test
    fun `the roles, the state and the temporary PIN are updated`() = runTest {
        val created = users.create(alice)

        users.update(created.copy(roles = setOf(Role.MANAGER), active = false, pinTemporary = true))

        val read = assertNotNull(users.find(created.id))
        assertEquals(setOf(Role.MANAGER), read.roles)
        assertFalse(read.active)
        assertTrue(read.pinTemporary)
    }
}
