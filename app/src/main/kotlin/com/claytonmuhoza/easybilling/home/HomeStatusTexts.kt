package com.claytonmuhoza.easybilling.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.ebms.label
import com.claytonmuhoza.easybilling.ui.dateTimeText

// What each card of the home screen says under its title.

@Composable
internal fun TaxProfileStatus.text(): String = when (this) {
    TaxProfileStatus.Loading -> "…"
    TaxProfileStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    TaxProfileStatus.ToComplete -> stringResource(R.string.home_tax_profile_to_complete)
    is TaxProfileStatus.Saved -> stringResource(R.string.home_tax_profile_version, number)
}

@Composable
internal fun EbmsStatus.text(): String = when (this) {
    EbmsStatus.Loading -> "…"
    EbmsStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    EbmsStatus.ToConfigure -> stringResource(R.string.home_ebms_to_configure)
    is EbmsStatus.Configured -> lastSuccess
        ?.let { stringResource(R.string.home_ebms_connected, environment.label(), dateTimeText(it)) }
        ?: stringResource(R.string.home_ebms_never_tested, environment.label())
}

@Composable
internal fun TaxesStatus.text(): String = when (this) {
    TaxesStatus.Loading -> "…"
    TaxesStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    is TaxesStatus.Defined -> if (active == 0) {
        stringResource(R.string.home_taxes_none)
    } else {
        pluralStringResource(R.plurals.home_taxes_active, active, active)
    }
}

@Composable
internal fun NumberingStatus.text(): String = when (this) {
    NumberingStatus.Loading -> "…"
    NumberingStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    NumberingStatus.ToDefine -> stringResource(R.string.home_numbering_to_define)
    is NumberingStatus.Defined -> if (locked) stringResource(R.string.home_numbering_locked, example) else example
}

@Composable
internal fun CatalogStatus.text(): String = when (this) {
    CatalogStatus.Loading -> "…"
    CatalogStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    is CatalogStatus.Counted -> {
        val items = if (active == 0) {
            stringResource(R.string.home_items_none)
        } else {
            pluralStringResource(R.plurals.home_items_active, active, active)
        }
        val toComplete = pluralStringResource(R.plurals.home_items_without_vat, withoutVat, withoutVat)
        if (withoutVat == 0) items else "$items · $toComplete"
    }
}

@Composable
internal fun CustomersStatus.text(): String = when (this) {
    CustomersStatus.Loading -> "…"
    CustomersStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    is CustomersStatus.Counted -> if (active == 0) {
        stringResource(R.string.home_customers_none)
    } else {
        pluralStringResource(R.plurals.home_customers_active, active, active)
    }
}

@Composable
internal fun SalesStatus.text(): String = when (this) {
    SalesStatus.Loading -> "…"
    SalesStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    is SalesStatus.Counted -> if (held == 0) {
        stringResource(R.string.home_sales_none_held)
    } else {
        pluralStringResource(R.plurals.home_sales_held, held, held)
    }
}
