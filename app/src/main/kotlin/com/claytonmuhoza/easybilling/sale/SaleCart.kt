package com.claytonmuhoza.easybilling.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import com.claytonmuhoza.easybilling.core.designsystem.component.ChoiceField
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountKind
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceTotals
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PricedLine
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineProblem
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.taxes.shortLabel
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
                Text(stringResource(R.string.sale_add_items))
            }
        }
        OutlinedCard(onClick = actions.onChooseCustomer, modifier = Modifier.fillMaxWidth()) {
            ListItem(
                overlineContent = { Text(stringResource(R.string.sale_customer)) },
                headlineContent = { Text(sale.customer.details.name) },
                trailingContent = { Text(stringResource(R.string.sale_customer_change)) },
            )
        }
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
            Text(stringResource(R.string.sale_hold))
        }
    }
}

/** A line of the sale: what is sold, its total, and the buttons that change it. */
@Composable
private fun LineCard(line: PricedLine, busy: Boolean, actions: SaleActions) {
    val designation = line.item.details.designation
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            LineSummary(line)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                val less = stringResource(R.string.sale_line_less, designation)
                OutlinedButton(
                    onClick = { actions.onRemoveOne(line.item.id) },
                    modifier = Modifier.semantics { contentDescription = less },
                    enabled = !busy,
                ) { Text("−") }
                val more = stringResource(R.string.sale_line_more, designation)
                OutlinedButton(
                    onClick = { actions.onAddItem(line.item.id) },
                    modifier = Modifier.semantics { contentDescription = more },
                    enabled = !busy,
                ) { Text("+") }
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
        }
        line.amounts?.let { Text(text = amountText(it.total), style = MaterialTheme.typography.titleMedium) }
    }
    line.problems.forEach { ErrorText(it.message()) }
}

/** The line being modified: its quantity, its discount and the taxes typed on it. */
@Composable
private fun LineEditorCard(line: PricedLine, editor: LineEditor, busy: Boolean, actions: SaleActions) {
    val form = editor.form
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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

/** The totals of the sale; the PFL only when there is one. */
@Composable
private fun Totals(totals: InvoiceTotals) {
    SectionHeader(stringResource(R.string.sale_totals))
    TotalRow(stringResource(R.string.sale_total_without_vat), totals.priceWithoutVat)
    TotalRow(stringResource(R.string.sale_total_vat), totals.vat)
    if (totals.flatRateWithholding.amount.signum() != 0) {
        TotalRow(stringResource(R.string.sale_total_flat_rate), totals.flatRateWithholding)
    }
    TotalRow(stringResource(R.string.sale_total), totals.total, strong = true)
}

@Composable
private fun TotalRow(label: String, amount: Money, strong: Boolean = false) {
    val style = if (strong) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
    val weight = if (strong) FontWeight.Bold else null
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = style, fontWeight = weight)
        Text(text = amountText(amount), style = style, fontWeight = weight)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.error)
}
