package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import java.math.BigDecimal
import java.time.LocalDate

/** A percentage, short to write in the tests. */
internal fun percent(rate: String, base: PercentageBase) = TaxComputation.Percentage(BigDecimal(rate), base)

/** An amount per unit. */
internal fun perUnit(amount: String) = TaxComputation.PerUnit(BigDecimal(amount))

/** The percentage in force on [date]. */
internal fun TaxDefinition.rateAt(date: LocalDate): BigDecimal = (computationAt(date) as TaxComputation.Percentage).rate
