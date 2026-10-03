package com.claytonmuhoza.easybilling.domain.customer

import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.TokenProvider

/** What one call to `checkTIN/` gave. */
sealed interface TinReply {
    /** The OBR knows the TIN; [name] is its owner (`tp_name`). */
    data class Known(val name: String) : TinReply

    /** The OBR does not know the TIN. */
    data object Unknown : TinReply

    /** The OBR refused the token: a new one is needed. */
    data object TokenRefused : TinReply

    /** Another refusal, with the [message] of the OBR (`msg`). */
    data class Refused(val message: String) : TinReply

    /** 500 or another server error: to try again later. */
    data object ServerError : TinReply

    /** The server could not be reached, or did not answer in time. */
    data object Unreachable : TinReply

    /** The certificate of the server is not trusted: nothing was sent. */
    data object CertificateRejected : TinReply

    /** The server answered something that is not a response of the specification. */
    data object InvalidResponse : TinReply
}

/** The TIN register of the OBR (`checkTIN/`). */
fun interface TinLookup {
    /**
     * @param target the server to ask.
     * @param token a token given by `login/`.
     * @param tin the TIN to look for.
     * @return what the OBR answered.
     */
    suspend fun check(target: LoginTarget, token: String, tin: String): TinReply
}

/** Why a TIN could not be checked. Each one has its own message on screen. */
enum class TinCheckFailure {
    /** No address or no credentials for the active environment: nothing was sent. */
    NOT_CONFIGURED,

    /** The server could not be reached, or did not answer in time. */
    UNREACHABLE,

    /** The server failed: to try again later. */
    SERVER_ERROR,

    /** The certificate of the server is not trusted. */
    CERTIFICATE_REJECTED,

    /** The OBR refused the credentials, or refused the token twice. */
    LOGIN_REFUSED,

    /** The server answered something that is not a response of the specification. */
    INVALID_RESPONSE,

    /** The OBR refused the request; its message is kept. */
    REFUSED,
}

/** Result of [VerifyTin]. */
sealed interface TinCheck {
    /** The OBR knows the TIN; [name] is its owner. */
    data class Known(val name: String) : TinCheck

    /** The OBR does not know the TIN. */
    data object Unknown : TinCheck

    /**
     * The TIN could not be checked.
     *
     * @property failure why.
     * @property message the message of the OBR, when it gave one.
     */
    data class Failed(val failure: TinCheckFailure, val message: String = "") : TinCheck
}

/**
 * Checks a TIN with the OBR (UC-B06). A refused token is renewed and the check is sent again, once.
 *
 * @param target gives the server to ask now, or null if the connection is not configured.
 * @param tokens gives the token of each call.
 * @param lookup the TIN register of the OBR.
 */
class VerifyTin(
    private val target: suspend () -> LoginTarget?,
    private val tokens: TokenProvider,
    private val lookup: TinLookup,
) {
    /**
     * @param tin the TIN to check.
     * @return whether the OBR knows it, or why it could not be checked.
     */
    suspend operator fun invoke(tin: String): TinCheck {
        val server = target() ?: return TinCheck.Failed(TinCheckFailure.NOT_CONFIGURED)
        return attempt(server, tin) ?: attempt(server, tin) ?: TinCheck.Failed(TinCheckFailure.LOGIN_REFUSED)
    }

    /** @return what one call gave, or null when the OBR refused the token, which is then forgotten. */
    private suspend fun attempt(server: LoginTarget, tin: String): TinCheck? =
        when (val outcome = tokens.token()) {
            is LoginOutcome.Success -> when (val reply = lookup.check(server, outcome.token, tin)) {
                TinReply.TokenRefused -> null.also { tokens.invalidate() }
                else -> checkOf(reply)
            }
            else -> TinCheck.Failed(failureOf(outcome))
        }

    private fun failureOf(outcome: LoginOutcome): TinCheckFailure = when (outcome) {
        LoginOutcome.NotConfigured -> TinCheckFailure.NOT_CONFIGURED
        LoginOutcome.Unreachable -> TinCheckFailure.UNREACHABLE
        LoginOutcome.ServerError -> TinCheckFailure.SERVER_ERROR
        LoginOutcome.CertificateRejected -> TinCheckFailure.CERTIFICATE_REJECTED
        LoginOutcome.InvalidResponse -> TinCheckFailure.INVALID_RESPONSE
        LoginOutcome.WrongCredentials, is LoginOutcome.Refused, is LoginOutcome.Success -> TinCheckFailure.LOGIN_REFUSED
    }

    private fun checkOf(reply: TinReply): TinCheck = when (reply) {
        is TinReply.Known -> TinCheck.Known(reply.name)
        TinReply.Unknown -> TinCheck.Unknown
        is TinReply.Refused -> TinCheck.Failed(TinCheckFailure.REFUSED, reply.message)
        TinReply.ServerError -> TinCheck.Failed(TinCheckFailure.SERVER_ERROR)
        TinReply.Unreachable -> TinCheck.Failed(TinCheckFailure.UNREACHABLE)
        TinReply.CertificateRejected -> TinCheck.Failed(TinCheckFailure.CERTIFICATE_REJECTED)
        TinReply.InvalidResponse -> TinCheck.Failed(TinCheckFailure.INVALID_RESPONSE)
        TinReply.TokenRefused -> TinCheck.Failed(TinCheckFailure.LOGIN_REFUSED)
    }
}
