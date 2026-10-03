package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import java.time.Instant

/**
 * The settings of the connection to the EBMS that are not secret.
 *
 * @property activeEnvironment the environment every call goes to.
 * @property serverUrls the address of each configured environment.
 * @property lastSuccessfulLogins when a login last succeeded with the current credentials of each
 * environment. The cash register stays locked until one succeeds (step 06).
 * @property exchangeRetentionDays how many days the exchanges with the OBR are kept (internal rule).
 */
data class EbmsSettings(
    val activeEnvironment: EbmsEnvironment = EbmsEnvironment.TEST,
    val serverUrls: Map<EbmsEnvironment, String> = mapOf(EbmsEnvironment.TEST to EbmsServerAddress.DEFAULT_TEST),
    val lastSuccessfulLogins: Map<EbmsEnvironment, Instant> = emptyMap(),
    val exchangeRetentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    /**
     * @param environment an environment.
     * @return its address, or null if none was given.
     */
    fun serverUrl(environment: EbmsEnvironment): String? = serverUrls[environment]

    /**
     * @param environment an environment.
     * @return when a login last succeeded with its current credentials, or null.
     */
    fun lastSuccessfulLogin(environment: EbmsEnvironment): Instant? = lastSuccessfulLogins[environment]

    /** Limits of the settings. */
    companion object {
        /** Days the exchanges are kept unless the administrator chooses otherwise. */
        const val DEFAULT_RETENTION_DAYS = 90

        /** Shortest retention accepted. */
        const val MIN_RETENTION_DAYS = 1

        /** Longest retention accepted: ten years. */
        const val MAX_RETENTION_DAYS = 3650
    }
}

/** Storage of the [EbmsSettings]. */
interface EbmsSettingsRepository {
    /** @return the settings, or the defaults if none were ever saved. */
    suspend fun read(): EbmsSettings

    /** @param settings the new settings. */
    suspend fun write(settings: EbmsSettings)
}

/** Storage of the EBMS credentials, encrypted with a key that never leaves the device. */
interface EbmsCredentialsVault {
    /**
     * @param environment an environment.
     * @return its credentials, or null if none were saved.
     */
    suspend fun read(environment: EbmsEnvironment): EbmsCredentials?

    /**
     * @param environment an environment.
     * @param credentials its new credentials.
     */
    suspend fun write(environment: EbmsEnvironment, credentials: EbmsCredentials)
}

/**
 * @param vault where the credentials are kept.
 * @return where and how to log in for the active environment, or null if it is not configured.
 */
suspend fun EbmsSettingsRepository.activeLoginTarget(vault: EbmsCredentialsVault): LoginTarget? {
    val settings = read()
    val environment = settings.activeEnvironment
    val serverUrl = settings.serverUrl(environment)
    val credentials = vault.read(environment)
    return if (serverUrl != null && credentials != null) LoginTarget(environment, serverUrl, credentials) else null
}
