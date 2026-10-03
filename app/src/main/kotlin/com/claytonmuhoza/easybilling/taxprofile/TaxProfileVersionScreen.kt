package com.claytonmuhoza.easybilling.taxprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * One version of the tax profile wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the version state.
 */
@Composable
fun TaxProfileVersionRoute(onBack: () -> Unit, viewModel: TaxProfileVersionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaxProfileVersionScreen(number = viewModel.number, state = state, onBack = onBack, onRetry = viewModel::load)
}

/**
 * One saved version of the tax profile, read only.
 *
 * @param number number of the version.
 * @param state the version state.
 * @param onBack called when the user goes back.
 * @param onRetry called when the user asks to read the version again.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun TaxProfileVersionScreen(
    number: Int,
    state: TaxProfileVersionUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TitledScreen(
        title = stringResource(R.string.tax_profile_version_title, number),
        modifier = modifier,
        onBack = onBack,
    ) {
        when (state) {
            TaxProfileVersionUiState.Loading -> LoadingState()
            TaxProfileVersionUiState.Failed -> ErrorState(
                message = stringResource(R.string.tax_profile_load_error),
                onRetry = onRetry,
            )
            TaxProfileVersionUiState.NotFound -> ErrorState(
                message = stringResource(R.string.tax_profile_version_not_found),
            )
            is TaxProfileVersionUiState.Ready -> FormColumn { VersionContent(state.version, state.authorName) }
        }
    }
}

@Composable
private fun VersionContent(version: TaxProfileVersion, authorName: String?) {
    val profile = version.profile
    val texts = TaxProfileForm.from(profile).texts
    Text(
        text = stringResource(
            R.string.tax_profile_version_saved,
            dateTimeText(version.savedAt),
            authorName ?: stringResource(R.string.tax_profile_version_unknown_author),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionHeader(stringResource(R.string.tax_profile_section_identity))
    LabeledValue(stringResource(R.string.tax_profile_taxpayer_type), profile.taxpayerType.label())
    TaxProfileSections.identity.forEach { LabeledValue(it, texts) }

    SectionHeader(stringResource(R.string.tax_profile_section_address))
    TaxProfileSections.address.forEach { LabeledValue(it, texts) }

    SectionHeader(stringResource(R.string.tax_profile_section_taxes))
    LabeledValue(stringResource(R.string.tax_profile_fiscal_center), profile.fiscalCenter.code)
    LabeledValue(stringResource(R.string.tax_profile_vat), yesNo(profile.taxes.vat))
    LabeledValue(stringResource(R.string.tax_profile_consumption_tax), yesNo(profile.taxes.consumptionTax))
    LabeledValue(stringResource(R.string.tax_profile_flat_rate_withholding), yesNo(profile.taxes.flatRateWithholding))
    TaxProfileSections.activity.forEach { LabeledValue(it, texts) }
}

@Composable
private fun LabeledValue(field: TaxProfileField, texts: Map<TaxProfileField, String>) =
    LabeledValue(field.label(), texts[field].orEmpty().ifEmpty { "—" })

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) R.string.yes else R.string.no)
