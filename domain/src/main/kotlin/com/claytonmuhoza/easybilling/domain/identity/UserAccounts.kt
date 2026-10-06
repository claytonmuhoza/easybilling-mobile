package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner

/**
 * What an administrator types about an account.
 *
 * @property name name of the user, as typed.
 * @property roles the roles ticked for the user.
 */
data class UserForm(val name: String, val roles: Set<Role>)

/** Why an account cannot be stored as typed. */
enum class UserProblem {
    /** The name is empty. */
    NAME_BLANK,

    /** The name is longer than [CreateAdministrator.MAX_NAME_LENGTH]. */
    NAME_TOO_LONG,

    /** Another account already has that name, whatever the case. */
    NAME_TAKEN,

    /** No role is ticked. */
    NO_ROLE,

    /** The change would leave no active administrator. */
    LAST_ADMINISTRATOR,
}

/** The checks shared by the creation and the change of an account. */
internal object UserRules {
    /**
     * @param form the account as typed.
     * @param others the accounts the name must differ from.
     * @return the first rule [form] breaks, or null.
     */
    fun problem(form: UserForm, others: List<User>): UserProblem? {
        val name = form.name.trim()
        return when {
            CreateAdministrator.nameProblem(name) == UserNameProblem.BLANK -> UserProblem.NAME_BLANK
            CreateAdministrator.nameProblem(name) == UserNameProblem.TOO_LONG -> UserProblem.NAME_TOO_LONG
            others.any { it.name.equals(name, ignoreCase = true) } -> UserProblem.NAME_TAKEN
            form.roles.isEmpty() -> UserProblem.NO_ROLE
            else -> null
        }
    }

    /**
     * @param changed the account as it would be stored.
     * @param all every stored account, [changed] included in its old state.
     * @return true if storing [changed] leaves no administrator who can log in.
     */
    fun leavesNoAdministrator(changed: User, all: List<User>): Boolean =
        all.none { (if (it.id == changed.id) changed else it).isActiveAdministrator }
}

/**
 * Creates the account of a manager, a cashier, a storekeeper or another administrator. The PIN
 * given here is temporary: the user replaces it at the first login. Such an account has no
 * recovery code; an administrator resets a forgotten PIN with [ResetUserPin].
 */
class CreateUser(
    private val users: UserRepository,
    private val hasher: SecretHasher,
    private val access: AccessControl,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the creation of an account. */
    sealed interface Result {
        /** The account is stored. */
        data class Created(val user: User) : Result

        /** The name or the roles break a rule. */
        data class Refused(val problem: UserProblem) : Result

        /** The temporary PIN breaks a rule. */
        data class InvalidPin(val problem: PinProblem) : Result
    }

    /**
     * @param form the name and the roles.
     * @param pin the temporary PIN.
     * @param pinConfirmation the temporary PIN typed a second time.
     * @param actor the administrator who creates the account.
     * @return the stored account, or why it was refused.
     * @throws PermissionDeniedException if [actor] does not manage the users.
     */
    suspend operator fun invoke(form: UserForm, pin: String, pinConfirmation: String, actor: UserId): Result {
        access.require(actor, Permission.MANAGE_USERS)
        val problem = UserRules.problem(form, users.findAll())
        val choice = Pin.choose(pin, pinConfirmation)
        return when {
            problem != null -> Result.Refused(problem)
            choice is PinChoice.Refused -> Result.InvalidPin(choice.problem)
            else -> created(form, (choice as PinChoice.Accepted).pin, actor)
        }
    }

    private suspend fun created(form: UserForm, pin: Pin, actor: UserId): Result {
        val newUser = NewUser(
            name = form.name.trim(),
            roles = form.roles,
            credentials = Credentials(pinHash = hasher.hash(pin.digits), recoveryHash = null),
            pinTemporary = true,
        )
        val user = transactions.run {
            users.create(newUser).also { auditLog.record(AuditEvent.UserCreated(actor, it.id, it.roles)) }
        }
        return Result.Created(user)
    }
}

/** Changes the name and the roles of an account. */
class UpdateUser(
    private val users: UserRepository,
    private val sessions: SessionManager,
    private val access: AccessControl,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the change of an account. */
    sealed interface Result {
        /** The account is stored with its new name and roles. */
        data class Updated(val user: User) : Result

        /** The name or the roles break a rule. */
        data class Refused(val problem: UserProblem) : Result

        /** There is no account with that identifier. */
        data object UnknownUser : Result
    }

    /**
     * @param id the account to change.
     * @param form its new name and roles.
     * @param actor the administrator who changes it.
     * @return the stored account, or why the change was refused.
     * @throws PermissionDeniedException if [actor] does not manage the users.
     */
    suspend operator fun invoke(id: UserId, form: UserForm, actor: UserId): Result {
        access.require(actor, Permission.MANAGE_USERS)
        val all = users.findAll()
        val existing = all.find { it.id == id } ?: return Result.UnknownUser
        val changed = existing.copy(name = form.name.trim(), roles = form.roles)
        val problem = UserRules.problem(form, all - existing)
        return when {
            problem != null -> Result.Refused(problem)
            UserRules.leavesNoAdministrator(changed, all) -> Result.Refused(UserProblem.LAST_ADMINISTRATOR)
            else -> updated(changed, actor)
        }
    }

    private suspend fun updated(changed: User, actor: UserId): Result {
        transactions.run {
            users.update(changed)
            auditLog.record(AuditEvent.UserUpdated(actor, changed.id, changed.roles))
        }
        sessions.refresh(changed)
        return Result.Updated(changed)
    }
}

/**
 * Disables an account, or enables it again. An account is never deleted: the invoices and the
 * audit log refer to it.
 */
class SetUserActive(
    private val users: UserRepository,
    private val access: AccessControl,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the change. */
    sealed interface Result {
        /** The account is stored in its new state. */
        data class Done(val user: User) : Result

        /** Nobody disables the account they are logged in with. */
        data object OwnAccount : Result

        /** The change would leave no active administrator. */
        data object LastAdministrator : Result

        /** There is no account with that identifier. */
        data object UnknownUser : Result
    }

    /**
     * @param id the account to change.
     * @param active false to disable it, true to enable it again.
     * @param actor the administrator who changes it.
     * @return the stored account, or why the change was refused.
     * @throws PermissionDeniedException if [actor] does not manage the users.
     */
    suspend operator fun invoke(id: UserId, active: Boolean, actor: UserId): Result {
        access.require(actor, Permission.MANAGE_USERS)
        val all = users.findAll()
        val existing = all.find { it.id == id } ?: return Result.UnknownUser
        val changed = existing.copy(active = active)
        return when {
            !active && id == actor -> Result.OwnAccount
            UserRules.leavesNoAdministrator(changed, all) -> Result.LastAdministrator
            else -> {
                transactions.run {
                    users.update(changed)
                    auditLog.record(AuditEvent.UserActiveChanged(actor, id, active))
                }
                Result.Done(changed)
            }
        }
    }
}

/**
 * Gives a new temporary PIN to a user who forgot theirs. The lock of the account is lifted and
 * the user chooses a PIN at the next login.
 */
class ResetUserPin(
    private val users: UserRepository,
    private val hasher: SecretHasher,
    private val access: AccessControl,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the reset. */
    sealed interface Result {
        /** The temporary PIN is stored. */
        data object Reset : Result

        /** The temporary PIN breaks a rule. */
        data class InvalidPin(val problem: PinProblem) : Result

        /** There is no account with that identifier. */
        data object UnknownUser : Result
    }

    /**
     * @param id the account whose PIN is replaced.
     * @param pin the temporary PIN.
     * @param pinConfirmation the temporary PIN typed a second time.
     * @param actor the administrator who resets it.
     * @return whether the PIN was replaced.
     * @throws PermissionDeniedException if [actor] does not manage the users.
     */
    suspend operator fun invoke(id: UserId, pin: String, pinConfirmation: String, actor: UserId): Result {
        access.require(actor, Permission.MANAGE_USERS)
        val user = users.find(id) ?: return Result.UnknownUser
        return when (val choice = Pin.choose(pin, pinConfirmation)) {
            is PinChoice.Refused -> Result.InvalidPin(choice.problem)
            is PinChoice.Accepted -> {
                val credentials = Credentials(hasher.hash(choice.pin.digits), user.credentials.recoveryHash)
                transactions.run {
                    users.update(user.copy(credentials = credentials, loginGuard = LoginGuard(), pinTemporary = true))
                    auditLog.record(AuditEvent.UserPinReset(actor, id))
                }
                Result.Reset
            }
        }
    }
}
