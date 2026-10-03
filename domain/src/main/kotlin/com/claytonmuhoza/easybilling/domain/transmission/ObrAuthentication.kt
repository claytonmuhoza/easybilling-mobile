package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment

/**
 * Where and how to log in.
 *
 * @property environment the environment.
 * @property serverUrl its address, ending with `/`.
 * @property credentials its credentials.
 */
data class LoginTarget(val environment: EbmsEnvironment, val serverUrl: String, val credentials: EbmsCredentials)

/**
 * What a login to the EBMS (`login/`) gave. Each failure has its own message on screen (UC-A05).
 *
 * @property code stable name of the outcome, stored in the audit log.
 */
sealed class LoginOutcome(val code: String) {
    /** The OBR gave [token], valid 60 seconds. */
    class Success(val token: String) : LoginOutcome("SUCCESS") {
        override fun equals(other: Any?): Boolean = other is Success && token == other.token

        override fun hashCode(): Int = token.hashCode()

        override fun toString(): String = "Success(token=••••)"
    }

    /** No address or no credentials for the active environment: nothing was sent. */
    data object NotConfigured : LoginOutcome("NOT_CONFIGURED")

    /** 401: the user name or the password is wrong. */
    data object WrongCredentials : LoginOutcome("WRONG_CREDENTIALS")

    /** 400 or another refusal, with the [message] of the OBR (`msg`). */
    data class Refused(val message: String) : LoginOutcome("REFUSED")

    /** 500 or another server error: to try again later. */
    data object ServerError : LoginOutcome("SERVER_ERROR")

    /** The server could not be reached, or did not answer in time. */
    data object Unreachable : LoginOutcome("UNREACHABLE")

    /** The certificate of the server is not trusted: nothing was sent. */
    data object CertificateRejected : LoginOutcome("CERTIFICATE_REJECTED")

    /** The server answered something that is not a response of the specification. */
    data object InvalidResponse : LoginOutcome("INVALID_RESPONSE")
}

/** The login to the EBMS. */
fun interface ObrAuthentication {
    /**
     * @param target where and how to log in.
     * @return the token, or why there is none.
     */
    suspend fun login(target: LoginTarget): LoginOutcome
}
