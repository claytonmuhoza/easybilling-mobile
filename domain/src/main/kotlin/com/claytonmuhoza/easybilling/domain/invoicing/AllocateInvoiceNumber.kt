package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.Instant

/**
 * Gives the next number of a type of document (UC-A06): numbers follow each other without gap nor
 * duplicate. The first number locks the numbering.
 *
 * Called inside the transaction that stores the document, the number is given back if the
 * document is not stored.
 *
 * @param numbering where the numbering and its counters are kept.
 * @param time source of the time the numbering is locked at.
 * @param transactions makes reading and moving the counter a single write.
 */
class AllocateInvoiceNumber(
    private val numbering: NumberingRepository,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    /** Outcome of an allocation. */
    sealed interface Result {
        /** [number] is consumed. */
        data class Allocated(val number: String) : Result

        /** No numbering was saved yet; nothing was consumed. */
        data object NotConfigured : Result

        /** The counter reached the last number its digits allow; nothing was consumed. */
        data object Exhausted : Result
    }

    /**
     * @param type the type of the document.
     * @param issuedAt when the document is issued; its year in Bujumbura chooses the counter.
     * @return the number, or why none was given.
     */
    suspend operator fun invoke(type: DocumentType, issuedAt: Instant): Result = transactions.run {
        val settings = numbering.settings() ?: return@run Result.NotConfigured
        val year = issuedAt.atZone(BusinessCalendar.ZONE).year
        val period = settings.reset.periodOf(year)
        val counter = numbering.lastCounter(type, period) + 1
        if (counter > settings.maxCounter) return@run Result.Exhausted

        numbering.setLastCounter(type, period, counter)
        if (!settings.locked) numbering.save(settings.copy(lockedAt = time.now()))
        Result.Allocated(settings.render(type, year, counter))
    }
}
