package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.AccessGuard
import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.CreateUser
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.PermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.ResetUserPin
import com.claytonmuhoza.easybilling.domain.identity.SecretGuard
import com.claytonmuhoza.easybilling.domain.identity.SecretHasher
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.SetUserActive
import com.claytonmuhoza.easybilling.domain.identity.SwitchUser
import com.claytonmuhoza.easybilling.domain.identity.UpdateUser
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the accounts of the users, their roles and the check of their rights (UC-H01). */
@Module
@InstallIn(SingletonComponent::class)
object UserAccountsModule {

    /** @return the rights matrix, fixed in the code (UC-H01). */
    @Provides
    fun permissionPolicy(): PermissionPolicy = FixedPermissionPolicy

    /** @return the check of the roles of a user before a use case runs. */
    @Provides
    fun accessControl(users: UserRepository, policy: PermissionPolicy, auditLog: AuditLog): AccessControl =
        AccessGuard(users, policy, auditLog)

    /** @return the use case creating an account with a temporary PIN. */
    @Provides
    fun createUser(
        users: UserRepository,
        hasher: SecretHasher,
        access: AccessControl,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): CreateUser = CreateUser(users, hasher, access, auditLog, transactions)

    /** @return the use case changing the name and the roles of an account. */
    @Provides
    fun updateUser(
        users: UserRepository,
        sessions: SessionManager,
        access: AccessControl,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): UpdateUser = UpdateUser(users, sessions, access, auditLog, transactions)

    /** @return the use case disabling an account or enabling it again. */
    @Provides
    fun setUserActive(
        users: UserRepository,
        access: AccessControl,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): SetUserActive = SetUserActive(users, access, auditLog, transactions)

    /** @return the use case giving a new temporary PIN to an account. */
    @Provides
    fun resetUserPin(
        users: UserRepository,
        hasher: SecretHasher,
        access: AccessControl,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): ResetUserPin = ResetUserPin(users, hasher, access, auditLog, transactions)

    /** @return the use case replacing a temporary PIN at the first login. */
    @Provides
    fun changeTemporaryPin(
        users: UserRepository,
        guard: SecretGuard,
        hasher: SecretHasher,
        sessions: SessionManager,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): ChangeTemporaryPin = ChangeTemporaryPin(users, guard, hasher, sessions, auditLog, transactions)

    /** @return the use case handing the session over to another user (UC-H03). */
    @Provides
    fun switchUser(users: UserRepository, guard: SecretGuard, sessions: SessionManager): SwitchUser =
        SwitchUser(users, guard, sessions)

    /** @return the use case approving a sensitive action with the PIN of a manager (UC-H04). */
    @Provides
    fun authorizeSensitiveAction(
        users: UserRepository,
        guard: SecretGuard,
        policy: PermissionPolicy,
        auditLog: AuditLog,
    ): AuthorizeSensitiveAction = AuthorizeSensitiveAction(users, guard, policy, auditLog)
}
