package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider

/** Something to configure before the first invoice (end of UC-A01). */
enum class ReadinessStep {
    /** The tax profile is saved. */
    TAX_PROFILE,

    /** A login to the active EBMS environment succeeded with the saved credentials. */
    EBMS_CONNECTION,

    /** A VAT is in force; only asked from a taxpayer registered for the VAT. */
    VAT,

    /** The numbering of the documents is saved. */
    NUMBERING,
}

/**
 * Where the configuration stands.
 *
 * @property steps the steps that apply to this business, in order, and whether each one is done.
 */
data class BillingReadiness(val steps: Map<ReadinessStep, Boolean>) {
    /** Whether invoices can be issued: the cash register stays locked until then. */
    val ready: Boolean get() = steps.values.all { it }
}

/**
 * Tells whether the business is ready to issue invoices (end of UC-A01).
 *
 * @param profiles where the tax profile is kept.
 * @param ebmsSettings where the outcome of the logins is kept.
 * @param taxes where the taxes are kept.
 * @param numbering where the numbering is kept.
 * @param time source of the current day.
 */
class CheckBillingReadiness(
    private val profiles: TaxProfileRepository,
    private val ebmsSettings: EbmsSettingsRepository,
    private val taxes: TaxRepository,
    private val numbering: NumberingRepository,
    private val time: TimeProvider,
) {
    /** @return every step that applies and whether it is done. */
    suspend operator fun invoke(): BillingReadiness {
        val profile = profiles.current()?.profile
        val settings = ebmsSettings.read()
        val steps = linkedMapOf(
            ReadinessStep.TAX_PROFILE to (profile != null),
            ReadinessStep.EBMS_CONNECTION to (settings.lastSuccessfulLogin(settings.activeEnvironment) != null),
        )
        if (profile?.taxes?.vat == true) steps[ReadinessStep.VAT] = vatInForce()
        steps[ReadinessStep.NUMBERING] = numbering.settings() != null
        return BillingReadiness(steps)
    }

    private suspend fun vatInForce(): Boolean {
        val today = BusinessCalendar.today(time)
        return taxes.all().any { it.kind == TaxKind.VAT && !it.archived && it.computationAt(today) != null }
    }
}
