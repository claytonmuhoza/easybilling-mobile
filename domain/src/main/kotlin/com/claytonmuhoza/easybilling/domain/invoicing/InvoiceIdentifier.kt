package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The `invoice_identifier` of the OBR: `<NIF>/<system identifier>/<YYYYMMDDhhmmss>/<number>`,
 * [MAX_LENGTH] characters at most.
 */
object InvoiceIdentifier {
    /** Longest `invoice_identifier` the OBR accepts. */
    const val MAX_LENGTH = 90

    private const val SEPARATOR = "/"
    private const val SEPARATORS = 3
    private const val DATE_LENGTH = 14
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(BusinessCalendar.ZONE)

    /**
     * @param tin the NIF of the taxpayer.
     * @param systemId the system identifier given by the OBR.
     * @param issuedAt when the document is issued; written in the time of Bujumbura.
     * @param number the number of the document.
     * @return the identifier.
     * @throws IllegalArgumentException if it is longer than [MAX_LENGTH].
     */
    fun of(tin: String, systemId: String, issuedAt: Instant, number: String): String {
        val identifier = listOf(tin, systemId, DATE.format(issuedAt), number).joinToString(SEPARATOR)
        require(identifier.length <= MAX_LENGTH) { "Identifier longer than $MAX_LENGTH characters" }
        return identifier
    }

    /**
     * @param tin the NIF of the taxpayer.
     * @param systemId the system identifier given by the OBR.
     * @return how many characters are left for the number of a document.
     */
    fun roomForNumber(tin: String, systemId: String): Int =
        MAX_LENGTH - tin.length - systemId.length - DATE_LENGTH - SEPARATORS
}

/**
 * What is known of the identifiers this business will issue.
 *
 * @property tin the NIF of the saved tax profile, or null.
 * @property activeSystemId the system identifier of the active EBMS environment, or null.
 * @property roomForNumber the characters left for a number with the longest saved system
 * identifier, or null while the NIF or every system identifier is unknown.
 */
data class IdentifierContext(val tin: String?, val activeSystemId: String?, val roomForNumber: Int?) {
    /**
     * @param issuedAt when the document is issued.
     * @param number the number of the document.
     * @return the identifier sent to the active environment, or null while it cannot be built.
     */
    fun identifierOf(issuedAt: Instant, number: String): String? {
        if (tin == null || activeSystemId == null) return null
        val fits = number.length <= InvoiceIdentifier.roomForNumber(tin, activeSystemId)
        return if (fits) InvoiceIdentifier.of(tin, activeSystemId, issuedAt, number) else null
    }
}

/**
 * Reads what the identifiers are built from: the saved tax profile and system identifiers.
 *
 * @param profiles where the tax profile is kept.
 * @param ebmsSettings where the active environment is kept.
 * @param vault where the system identifiers are kept.
 */
class ReadIdentifierContext(
    private val profiles: TaxProfileRepository,
    private val ebmsSettings: EbmsSettingsRepository,
    private val vault: EbmsCredentialsVault,
) {
    /** @return what is known for now. */
    suspend operator fun invoke(): IdentifierContext {
        val tin = profiles.current()?.profile?.tin
        val systemIds = EbmsEnvironment.entries.mapNotNull { environment ->
            vault.read(environment)?.let { environment to it.systemId }
        }.toMap()
        return IdentifierContext(
            tin = tin,
            activeSystemId = systemIds[ebmsSettings.read().activeEnvironment],
            roomForNumber = tin?.let { systemIds.values.minOfOrNull { id -> InvoiceIdentifier.roomForNumber(it, id) } },
        )
    }
}
