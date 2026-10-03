package com.claytonmuhoza.easybilling.testing

import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import java.math.BigDecimal
import java.time.LocalDate

/** An amount per unit, short to write in the tests. */
internal fun perUnit(amount: String) = TaxComputation.PerUnit(BigDecimal(amount))

/** The percentage in force on [date]. */
internal fun TaxDefinition.rateAt(date: LocalDate): BigDecimal = (computationAt(date) as TaxComputation.Percentage).rate
