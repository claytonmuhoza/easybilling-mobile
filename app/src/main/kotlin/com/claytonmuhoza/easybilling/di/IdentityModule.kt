package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.FixedLockoutPolicy
import com.claytonmuhoza.easybilling.domain.identity.LockoutPolicy
import com.claytonmuhoza.easybilling.domain.identity.LogIn
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCodeGenerator
import com.claytonmuhoza.easybilling.domain.identity.ResetPin
import com.claytonmuhoza.easybilling.domain.identity.SecretGuard
import com.claytonmuhoza.easybilling.domain.identity.SecretHasher
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Singleton

/**
 * Builds the identity and audit parts of the domain. The domain has no injection annotation of
 * its own, so its classes are assembled here.
 */
@Module
@InstallIn(SingletonComponent::class)
object IdentityModule {

    /** Time without user action after which the session closes (internal rule). */
    private val INACTIVITY_TIMEOUT: Duration = Duration.ofMinutes(5)

    /** @return the users kept in the local database. */
    @Provides
    fun userRepository(storage: LocalStorage): UserRepository = storage.users

    /** @return the audit entries kept in the local database. */
    @Provides
    fun auditStore(storage: LocalStorage): AuditStore = storage.auditStore

    /** @return the runner of the local database transactions. */
    @Provides
    fun transactionRunner(storage: LocalStorage): TransactionRunner = storage.transactions

    /** @return the hash-chained audit log, unique so that entries are appended one at a time. */
    @Provides
    @Singleton
    fun auditLog(store: AuditStore, time: TimeProvider, transactions: TransactionRunner): AuditLog =
        ChainedAuditLog(store, time, transactions)

    /** @return the lockout rule: one minute after ten failures (internal rule). */
    @Provides
    fun lockoutPolicy(): LockoutPolicy = FixedLockoutPolicy()

    /** @return the holder of the open session, unique for the whole application. */
    @Provides
    @Singleton
    fun sessionManager(time: TimeProvider, auditLog: AuditLog): SessionManager =
        SessionManager(time, auditLog, INACTIVITY_TIMEOUT)

    /** @return the check of a PIN or recovery code, with its failure count and lock. */
    @Provides
    fun secretGuard(
        users: UserRepository,
        hasher: SecretHasher,
        policy: LockoutPolicy,
        time: TimeProvider,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): SecretGuard = SecretGuard(users, hasher, policy, time, auditLog, transactions)

    /** @return the use case creating the administrator account. */
    @Provides
    fun createAdministrator(
        users: UserRepository,
        hasher: SecretHasher,
        recoveryCodes: RecoveryCodeGenerator,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): CreateAdministrator = CreateAdministrator(users, hasher, recoveryCodes, auditLog, transactions)

    /** @return the use case logging a user in by PIN. */
    @Provides
    fun logIn(users: UserRepository, guard: SecretGuard, sessions: SessionManager): LogIn =
        LogIn(users, guard, sessions)

    /** @return the use case replacing a PIN with the recovery code. */
    @Provides
    fun resetPin(
        users: UserRepository,
        guard: SecretGuard,
        hasher: SecretHasher,
        recoveryCodes: RecoveryCodeGenerator,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): ResetPin = ResetPin(users, guard, hasher, recoveryCodes, auditLog, transactions)
}
