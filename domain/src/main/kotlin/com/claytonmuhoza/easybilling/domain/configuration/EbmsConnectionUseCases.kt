package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.SecretCheck
import com.claytonmuhoza.easybilling.domain.identity.SecretGuard
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Saves the address and the credentials of one environment (UC-A03). New credentials no longer
 * count as working until they are tested again.
 *
 * @param settings where the address is kept.
 * @param vault where the credentials are kept, encrypted.
 * @param auditLog log that records each change, without any secret.
 */
class SaveEbmsConnection(
    private val settings: EbmsSettingsRepository,
    private val vault: EbmsCredentialsVault,
    private val auditLog: AuditLog,
) {
    /** Outcome of a save. */
    sealed interface Result {
        /** The settings are saved. */
        data object Saved : Result

        /** The form breaks rules; nothing was saved. */
        data class Invalid(val problems: Map<EbmsCredentialField, FieldProblem>, val urlProblem: UrlProblem?) : Result
    }

    /**
     * @param environment the environment the settings belong to.
     * @param form the settings as typed.
     * @param actor the user who saves them.
     * @return whether they were saved.
     */
    suspend operator fun invoke(environment: EbmsEnvironment, form: EbmsConnectionForm, actor: UserId): Result =
        when (val check = form.check(vault.read(environment)?.password)) {
            is EbmsConnectionCheck.Invalid -> Result.Invalid(check.problems, check.urlProblem)
            is EbmsConnectionCheck.Valid -> {
                vault.write(environment, check.credentials)
                val current = settings.read()
                settings.write(
                    current.copy(
                        serverUrls = current.serverUrls + (environment to check.serverUrl),
                        lastSuccessfulLogins = current.lastSuccessfulLogins - environment,
                    ),
                )
                auditLog.record(AuditEvent.EbmsConnectionSaved(actor, environment))
                Result.Saved
            }
        }
}

/** Why fiscal documents prevent a change of environment. */
enum class EnvironmentRefusal {
    /** Documents wait to be sent to the current environment: they must be sent first. */
    PENDING_TRANSMISSIONS,

    /** Real invoices were issued in production: going back to test is no longer possible (UC-A04). */
    PRODUCTION_IN_USE,
}

/** Tells whether fiscal documents prevent a change of environment. */
fun interface EnvironmentLock {
    /**
     * @param from the active environment.
     * @param to the environment asked for.
     * @return why the change is refused, or null if nothing prevents it.
     */
    suspend fun refusal(from: EbmsEnvironment, to: EbmsEnvironment): EnvironmentRefusal?
}

/**
 * Chooses the environment every call goes to (UC-A04). Going to production is confirmed with the
 * PIN of the user, through the same guard as the login: a wrong PIN counts as a failed attempt.
 *
 * @param settings where the active environment is kept.
 * @param users the users, to find the PIN of the actor.
 * @param guard checks the PIN and enforces the lockout.
 * @param auditLog log that records each change.
 */
class SwitchEbmsEnvironment(
    private val settings: EbmsSettingsRepository,
    private val users: UserRepository,
    private val guard: SecretGuard,
    private val auditLog: AuditLog,
    private val lock: EnvironmentLock = EnvironmentLock { _, _ -> null },
) {
    /** Outcome of a switch. */
    sealed interface Result {
        /** The environment changed. */
        data object Switched : Result

        /** The chosen environment was already active. */
        data object Unchanged : Result

        /** The PIN is wrong; [remainingAttempts] are left before the account locks. */
        data class WrongPin(val remainingAttempts: Int) : Result

        /** The account refuses every attempt until [until]. */
        data class Locked(val until: Instant) : Result

        /** The actor is not a known user. */
        data object UnknownUser : Result

        /** Fiscal documents prevent the switch: [reason] says which. */
        data class Refused(val reason: EnvironmentRefusal) : Result
    }

    /**
     * @param target the environment to use from now on.
     * @param actor the user who asks for it.
     * @param pin the PIN typed to confirm a switch to production; ignored for test.
     * @return whether the environment changed.
     */
    suspend operator fun invoke(target: EbmsEnvironment, actor: UserId, pin: String?): Result {
        val current = settings.read()
        val locked = lock.refusal(current.activeEnvironment, target)
        val refusal = when {
            current.activeEnvironment == target -> Result.Unchanged
            locked != null -> Result.Refused(locked)
            target == EbmsEnvironment.PRODUCTION -> confirmPin(actor, pin.orEmpty())
            else -> null
        }
        return refusal ?: run {
            settings.write(current.copy(activeEnvironment = target))
            auditLog.record(AuditEvent.EbmsEnvironmentChanged(actor, current.activeEnvironment, target))
            Result.Switched
        }
    }

    /** @return why the switch is refused, or null if the PIN is right. */
    private suspend fun confirmPin(actor: UserId, pin: String): Result? {
        val user = users.find(actor) ?: return Result.UnknownUser
        return when (val check = guard.check(user, pin, user.credentials.pinHash, AuditEvent.PinRejected(actor))) {
            is SecretCheck.Accepted -> null
            is SecretCheck.Rejected -> Result.WrongPin(check.remainingAttempts)
            is SecretCheck.Locked -> Result.Locked(check.until)
        }
    }
}

/**
 * Tries a login to the active environment (UC-A05). A success is remembered: it unlocks the cash
 * register once the rest of the configuration is done (step 06).
 *
 * @param settings where the active environment, its address and its last success are kept.
 * @param vault where the credentials are kept.
 * @param authentication the login to the OBR.
 * @param time source of the time of the success.
 * @param auditLog log that records each test and its outcome.
 */
class TestEbmsConnection(
    private val settings: EbmsSettingsRepository,
    private val vault: EbmsCredentialsVault,
    private val authentication: ObrAuthentication,
    private val time: TimeProvider,
    private val auditLog: AuditLog,
) {
    /**
     * @param actor the user who asks for the test.
     * @return what the OBR answered, or [LoginOutcome.NotConfigured].
     */
    suspend operator fun invoke(actor: UserId): LoginOutcome {
        val target = settings.activeLoginTarget(vault) ?: return LoginOutcome.NotConfigured
        val outcome = authentication.login(target)
        if (outcome is LoginOutcome.Success) {
            val current = settings.read()
            val now = time.now().truncatedTo(ChronoUnit.MILLIS)
            val successes = current.lastSuccessfulLogins + (target.environment to now)
            settings.write(current.copy(lastSuccessfulLogins = successes))
        }
        auditLog.record(AuditEvent.EbmsConnectionTested(actor, target.environment, outcome.code))
        return outcome
    }
}
