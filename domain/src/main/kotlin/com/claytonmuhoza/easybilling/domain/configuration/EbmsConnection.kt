package com.claytonmuhoza.easybilling.domain.configuration

import java.net.URI
import java.net.URISyntaxException

/** The EBMS server the application talks to (UC-A04). Each one has its own address and credentials. */
enum class EbmsEnvironment {
    /** The test server of the OBR, used until the OBR accepts the tests. */
    TEST,

    /** The production server, whose address and credentials the OBR gives after the tests. */
    PRODUCTION,
}

/** Why a server address is refused. */
enum class UrlProblem {
    /** No address. */
    MISSING,

    /** The address is not `https://`: credentials must never travel in clear. */
    NOT_HTTPS,

    /** The address cannot be read. */
    MALFORMED,
}

/** Addresses of the EBMS servers. */
object EbmsServerAddress {
    /**
     * Address of the test server, as given by the links of the OBR specification (page 9). The
     * production address is not in the specification: the OBR gives it after the tests.
     */
    const val DEFAULT_TEST = "https://ebms.obr.gov.bi:9443/ebms_api/"

    /**
     * @param address an address as typed.
     * @return why the address is refused, or null if it is acceptable.
     */
    fun problemOf(address: String): UrlProblem? {
        val trimmed = address.trim()
        return when {
            trimmed.isEmpty() -> UrlProblem.MISSING
            !trimmed.startsWith(HTTPS, ignoreCase = true) -> UrlProblem.NOT_HTTPS
            hostOf(trimmed).isNullOrEmpty() -> UrlProblem.MALFORMED
            else -> null
        }
    }

    /**
     * @param address an acceptable address.
     * @return the address trimmed and ending with `/`, so service names can be appended to it.
     */
    fun normalized(address: String): String = address.trim().let { if (it.endsWith("/")) it else "$it/" }

    private fun hostOf(address: String): String? =
        try {
            URI(address).host
        } catch (_: URISyntaxException) {
            null
        }

    private const val HTTPS = "https://"
}

/**
 * The credentials the OBR gives to a taxpayer (UC-A03). The password is never shown, logged nor
 * written in clear.
 *
 * The user name is the identifier of the system: the specification sends it as `username` to
 * `login/` (page 16, "votre nom d'utilisateur système") and says the system identifier of
 * `invoice_identifier` is "celui fourni avec un mot de passe afin de se connecter" (page 13).
 *
 * @property username the identifier of the system, `username` of `login/`.
 * @property password `password` of `login/`.
 */
data class EbmsCredentials(val username: String, val password: String) {
    /** The identifier of the system, used in `invoice_identifier` and the stock movements. */
    val systemId: String get() = username

    override fun toString(): String = "EbmsCredentials(username=$username, password=••••)"
}

/**
 * A field of the EBMS credentials, with its size in the parameter table of the specification.
 *
 * @property obrName name of the parameter in the OBR specification.
 * @property maxLength longest accepted value, in characters.
 */
enum class EbmsCredentialField(val obrName: String, val maxLength: Int) {
    /**
     * Identifier of the taxpayer system, sent as user name. Two sizes of the specification apply
     * to it, 30 for `username` and 100 for `system_id`: the smaller one is applied.
     */
    USERNAME("username", 30),

    /** Password of the taxpayer system. */
    PASSWORD("password", 30),
}

/** Result of [EbmsConnectionForm.check]. */
sealed interface EbmsConnectionCheck {
    /**
     * Every rule holds.
     *
     * @property serverUrl the normalized server address.
     * @property credentials the credentials.
     */
    data class Valid(val serverUrl: String, val credentials: EbmsCredentials) : EbmsConnectionCheck

    /**
     * Some rules are broken; every problem is reported at once.
     *
     * @property problems the refused credential fields.
     * @property urlProblem why the address is refused, or null.
     */
    data class Invalid(
        val problems: Map<EbmsCredentialField, FieldProblem>,
        val urlProblem: UrlProblem?,
    ) : EbmsConnectionCheck
}

/**
 * The connection settings of one environment as typed by the user, not checked yet.
 *
 * @property serverUrl the server address.
 * @property username the identifier of the system.
 * @property password the password; empty to keep the stored one.
 */
data class EbmsConnectionForm(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    /**
     * @param field a credential field.
     * @param value its new content.
     * @return this form with [field] set to [value].
     */
    fun with(field: EbmsCredentialField, value: String): EbmsConnectionForm = when (field) {
        EbmsCredentialField.USERNAME -> copy(username = value)
        EbmsCredentialField.PASSWORD -> copy(password = value)
    }

    /**
     * Applies the rules. The address and the identifier are trimmed; the password is taken as
     * typed, since spaces may be part of it.
     *
     * @param storedPassword the password already stored for this environment, used when the
     * password field is left empty; null if there is none.
     * @return the settings, or every broken rule.
     */
    fun check(storedPassword: String?): EbmsConnectionCheck {
        val values = mapOf(
            EbmsCredentialField.USERNAME to username.trim(),
            EbmsCredentialField.PASSWORD to password.ifEmpty { storedPassword.orEmpty() },
        )
        val problems = values.mapNotNull { (field, value) -> problemOf(field, value)?.let { field to it } }.toMap()
        val urlProblem = EbmsServerAddress.problemOf(serverUrl)
        if (problems.isNotEmpty() || urlProblem != null) {
            return EbmsConnectionCheck.Invalid(problems, urlProblem)
        }
        return EbmsConnectionCheck.Valid(
            serverUrl = EbmsServerAddress.normalized(serverUrl),
            credentials = EbmsCredentials(
                username = values.getValue(EbmsCredentialField.USERNAME),
                password = values.getValue(EbmsCredentialField.PASSWORD),
            ),
        )
    }

    private fun problemOf(field: EbmsCredentialField, value: String): FieldProblem? = when {
        value.isEmpty() -> FieldProblem.Missing
        value.length > field.maxLength -> FieldProblem.TooLong(field.maxLength)
        else -> null
    }

    override fun toString(): String = "EbmsConnectionForm(serverUrl=$serverUrl, username=$username, password=••••)"
}
