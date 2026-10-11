package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import java.time.Instant

/**
 * Gives a document what it takes from the configuration when it is issued: the seller in force,
 * its number and its identifier. Shared by every type of document.
 *
 * @param profiles where the tax profile is kept.
 * @param ebmsSettings where the active EBMS environment is kept.
 * @param vault where the system identifier is kept.
 * @param readiness tells whether the configuration is complete.
 * @param allocateNumber gives the next number.
 */
class DocumentStamper(
    private val profiles: TaxProfileRepository,
    private val ebmsSettings: EbmsSettingsRepository,
    private val vault: EbmsCredentialsVault,
    private val readiness: CheckBillingReadiness,
    private val allocateNumber: AllocateInvoiceNumber,
) {
    /**
     * The seller of a document.
     *
     * @property tin the NIF of the tax profile in force.
     * @property profileVersion the version of that tax profile.
     * @property environment the active EBMS environment.
     * @property systemId the system identifier of that environment.
     */
    class Seller(
        val tin: String,
        val profileVersion: Int,
        val environment: EbmsEnvironment,
        val systemId: String,
    )

    /** Outcome of [seller]. */
    sealed interface SellerResult {
        /** The configuration is complete. */
        class Ready(val seller: Seller) : SellerResult

        /** The configuration is not complete (see [CheckBillingReadiness]). */
        class NotReady(val readiness: BillingReadiness) : SellerResult
    }

    /** Outcome of [stamp]. */
    sealed interface Stamp {
        /** [number] is consumed; [identifier] is the `invoice_identifier` it gives. */
        data class Done(val number: String, val identifier: String) : Stamp

        /** The counter reached the last number its digits allow; nothing was consumed. */
        data object NumbersExhausted : Stamp

        /**
         * The number does not fit in the 90 characters of the identifier. The number was consumed:
         * the caller leaves its transaction to give it back.
         */
        data object IdentifierTooLong : Stamp
    }

    /** @return the seller in force, or why no document can be issued. */
    suspend fun seller(): SellerResult {
        val ready = readiness()
        val environment = ebmsSettings.read().activeEnvironment
        val systemId = vault.read(environment)?.systemId
        val profile = profiles.current()
        return if (!ready.ready || profile == null || systemId == null) {
            SellerResult.NotReady(ready)
        } else {
            SellerResult.Ready(Seller(profile.profile.tin, profile.number, environment, systemId))
        }
    }

    /**
     * Takes the next number of [type]. Called inside the transaction that stores the document, the
     * number is given back if the document is not stored.
     *
     * @param type the type of the document.
     * @param seller the seller of the document.
     * @param issuedAt when the document is issued, to the second.
     * @return the number and the identifier, or why there is none.
     */
    suspend fun stamp(type: DocumentType, seller: Seller, issuedAt: Instant): Stamp =
        when (val allocation = allocateNumber(type, issuedAt)) {
            is AllocateInvoiceNumber.Result.Allocated -> stamped(allocation.number, seller, issuedAt)
            AllocateInvoiceNumber.Result.Exhausted -> Stamp.NumbersExhausted
            // The readiness already asked for a numbering.
            AllocateInvoiceNumber.Result.NotConfigured -> error("No numbering although the configuration is ready")
        }

    private fun stamped(number: String, seller: Seller, issuedAt: Instant): Stamp =
        if (number.length > InvoiceIdentifier.roomForNumber(seller.tin, seller.systemId)) {
            Stamp.IdentifierTooLong
        } else {
            Stamp.Done(number, InvoiceIdentifier.of(seller.tin, seller.systemId, issuedAt, number))
        }
}
