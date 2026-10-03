package com.claytonmuhoza.easybilling.data.local

import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import java.math.BigDecimal

/** A percentage, short to write in the tests. */
internal fun percent(rate: String, base: PercentageBase) = TaxComputation.Percentage(BigDecimal(rate), base)

/** An amount per unit. */
internal fun perUnit(amount: String) = TaxComputation.PerUnit(BigDecimal(amount))
