package com.claytonmuhoza.easybilling.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.ChoiceField
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.IconTile
import com.claytonmuhoza.easybilling.core.designsystem.component.QuantityStepper
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountKind
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceTotals
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PricedLine
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineProblem
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.taxes.shortLabel
import com.claytonmuhoza.easybilling.stock.quantityText
import com.claytonmuhoza.easybilling.ui.MoneyText
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.decimalText

/**
 * The sale: its customer, its lines, its total, its payment and the buttons that end it.
 *
 * @param state the sale state.
 * @param actions what the user can do.
 * @param narrow whether the catalogue is on another part of the screen, reached by a button.
 * @param modifier modifier applied to the pane.
 */
@Composable
internal fun SaleCart(state: SaleUiState.Ready, actions: SaleActions, narrow: Boolean, modifier: Modifier = Modifier) {
    val sale = state.sale
    FormColumn(modifier = modifier) {
        if (narrow) {
            OutlinedButton(onClick = { actions.onShowPane(SalePane.CATALOGUE) }, modifier = Modifier.fillMaxWidth()) {
                AppIconImage(AppIcon.ADD, contentDescription = null)
                Text(stringResource(R.string.sale_add_items), modifier = Modifier.padding(start = Spacing.small))
            }
        }
        CustomerCard(sale.customer, actions.onChooseCustomer)
        if (sale.lines.isEmpty()) {
            Text(
                text = stringResource(R.string.sale_cart_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sale.lines.forEach { line ->
            val editor = state.editor?.takeIf { it.itemId == line.item.id }
            if (editor == null) {
                LineCard(line, state.busy, actions)
            } else {
                LineEditorCard(line, editor, state.busy, actions)
            }
        }
        Totals(sale.totals)
        ChoiceField(
            label = stringResource(R.string.sale_payment),
            options = PaymentType.entries.map { it to it.label() },
            selected = sale.draft.paymentType,
            onSelect = actions.onPaymentTypeChange,
        )
        sale.problems.forEach { ErrorText(it.message()) }
        state.refusal?.let { ErrorText(stringResource(REFUSAL_MESSAGES.getValue(it))) }
        if (state.failed) ErrorText(stringResource(R.string.sale_failed))
        Button(
            onClick = actions.onIssue,
            modifier = Modifier.fillMaxWidth(),
            enabled = sale.issuable && !state.busy,
        ) {
            Text(stringResource(R.string.sale_validate))
        }
        OutlinedButton(
            onClick = actions.onHold,
            modifier = Modifier.fillMaxWidth(),
            enabled = sale.lines.isNotEmpty() && !state.busy,
        ) {
            AppIconImage(AppIcon.PAUSE, contentDescription = null)
            Text(stringResource(R.string.sale_hold), modifier = Modifier.padding(start = Spacing.small))
        }
    }
}

/** The customer of the sale, and the way to choose another one. */
@Composable
private fun CustomerCard(customer: Customer, onChoose: () -> Unit) {
    TonalCard(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
        CardListItem(
            leadingContent = { IconTile(if (customer.counter) AppIcon.STORE else AppIcon.PERSON) },
            overlineContent = { Text(stringResource(R.string.sale_customer)) },
            headlineContent = { Text(customer.details.name) },
            trailingContent = {
                Text(
                    text = stringResource(R.string.sale_customer_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

/** A line of the sale: what is sold, its total, and the buttons that change it. */
@Composable
private fun LineCard(line: PricedLine, busy: Boolean, actions: SaleActions) {
    val designation = line.item.details.designation
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            LineSummary(line)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                QuantityStepper(
                    quantity = decimalText(line.line.quantity.value),
                    onLess = { actions.onRemoveOne(line.item.id) },
                    onMore = { actions.onAddItem(line.item.id) },
                    lessDescription = stringResource(R.string.sale_line_less, designation),
                    moreDescription = stringResource(R.string.sale_line_more, designation),
                    enabled = !busy,
                )
                val modify = stringResource(R.string.sale_line_modify, designation)
                TextButton(
                    onClick = { actions.onOpenLine(line.item.id) },
                    modifier = Modifier.semantics { contentDescription = modify },
                ) { Text(stringResource(R.string.sale_line_edit)) }
            }
        }
    }
}

/** What a line sells, at which price, and what prevents it from being issued. */
@Composable
private fun LineSummary(line: PricedLine) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = line.item.details.designation, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.sale_line_summary,
                    decimalText(line.line.quantity.value),
                    amountText(line.netUnitPrice),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            line.line.discount?.let { Text(text = it.label(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // An item kept in stock says what the sale may take of it (UC-F09).
            line.available?.let { available ->
                Text(
                    text = stringResource(R.string.stock_in_stock, quantityText(available, line.item.details.unit)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        line.amounts?.let {
            MoneyText(it.total, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    line.problems.forEach { ErrorText(it.message()) }
}

/** The line being modified: its quantity, its discount and the taxes typed on it. */
@Composable
private fun LineEditorCard(line: PricedLine, editor: LineEditor, busy: Boolean, actions: SaleActions) {
    val form = editor.form
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            LineSummary(line)
            NumberField(
                value = form.quantity,
                label = stringResource(R.string.sale_line_quantity),
                error = editor.message(SaleLineProblem.QUANTITY_INVALID),
                onChange = { quantity -> actions.onLineFormChange { copy(quantity = quantity) } },
            )
            val none: List<Pair<DiscountKind?, String>> = listOf(null to stringResource(R.string.sale_discount_none))
            ChoiceField(
                label = stringResource(R.string.sale_line_discount),
                options = none + listOf(
                    DiscountKind.PERCENT to stringResource(R.string.sale_discount_percent),
                    DiscountKind.PER_UNIT to stringResource(R.string.sale_discount_amount),
                ),
                selected = form.discountKind,
                onSelect = { kind -> actions.onLineFormChange { copy(discountKind = kind) } },
            )
            if (form.discountKind != null) {
                NumberField(
                    value = form.discountValue,
                    label = stringResource(
                        if (form.discountKind == DiscountKind.PERCENT) {
                            R.string.sale_line_discount_value_percent
                        } else {
                            R.string.sale_line_discount_value_amount
                        },
                    ),
                    error = editor.message(SaleLineProblem.DISCOUNT_INVALID),
                    onChange = { value -> actions.onLineFormChange { copy(discountValue = value) } },
                )
            }
            FreeTaxFields(line.freeTaxes, editor, actions)
            Button(onClick = actions.onApplyLine, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(stringResource(R.string.sale_line_apply))
            }
            OutlinedButton(
                onClick = { actions.onRemoveLine(line.item.id) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.sale_line_remove))
            }
        }
    }
}

/** One field per tax of the item whose amount is typed when selling. */
@Composable
private fun FreeTaxFields(kinds: Set<TaxKind>, editor: LineEditor, actions: SaleActions) {
    kinds.forEachIndexed { index, kind ->
        NumberField(
            value = editor.form.freeAmounts[kind].orEmpty(),
            label = stringResource(R.string.sale_line_free_amount, kind.shortLabel()),
            // One message for every tax amount, shown under the last field.
            error = editor.message(SaleLineProblem.FREE_AMOUNT_INVALID).takeIf { index == kinds.size - 1 },
            onChange = { amount -> actions.onLineFormChange { copy(freeAmounts = freeAmounts + (kind to amount)) } },
        )
    }
}

/** @return the message of [problem] when the line being modified breaks it, or null. */
@Composable
private fun LineEditor.message(problem: SaleLineProblem): String? =
    if (problem in problems) stringResource(LINE_PROBLEM_MESSAGES.getValue(problem)) else null

@Composable
private fun NumberField(value: String, label: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        singleLine = true,
    )
}

/** The totals of the sale, in the order of the formula of the OBR; the PFL only when there is one. */
@Composable
private fun Totals(totals: InvoiceTotals) {
    TonalCard(modifier = Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            TotalRow(stringResource(R.string.sale_total_without_vat), totals.priceWithoutVat)
            TotalRow(stringResource(R.string.sale_total_vat), totals.vat)
            if (totals.flatRateWithholding.amount.signum() != 0) {
                TotalRow(stringResource(R.string.sale_total_flat_rate), totals.flatRateWithholding)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sale_total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MoneyText(
                    money = totals.total,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: Money) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.error)
}
