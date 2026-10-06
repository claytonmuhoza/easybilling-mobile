package com.claytonmuhoza.easybilling.users

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone

/** Longest cap typed: `99.99`. */
private const val CAP_MAX_LENGTH = 5

/**
 * Cap of the discount wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the cap state.
 */
@Composable
fun DiscountCapRoute(onBack: () -> Unit, viewModel: DiscountCapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscountCapScreen(
        state = state,
        actions = DiscountCapActions(
            onBack = onBack,
            onLimitedChange = viewModel::onLimitedChange,
            onValueChange = viewModel::onValueChange,
            onSave = viewModel::submit,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the cap of the discount.
 *
 * @property onBack called when the user goes back.
 * @property onLimitedChange called when the user limits the discount of the cashier or not.
 * @property onValueChange called when the cap typed changes.
 * @property onSave called when the user saves the cap.
 * @property onRetry called when the user asks to read the cap again.
 */
class DiscountCapActions(
    val onBack: () -> Unit = {},
    val onLimitedChange: (Boolean) -> Unit = {},
    val onValueChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The highest discount a cashier gives alone (UC-C03, D50). Beyond it, a manager or an
 * administrator approves the discount with their PIN. No cap is the default.
 *
 * @param state the cap state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun DiscountCapScreen(state: DiscountCapUiState, actions: DiscountCapActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.discount_cap_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            DiscountCapUiState.Loading -> LoadingState()
            DiscountCapUiState.Failed -> ErrorState(
                message = stringResource(R.string.discount_cap_load_error),
                onRetry = actions.onRetry,
            )
            is DiscountCapUiState.Editing -> FormColumn {
                Text(
                    text = stringResource(R.string.discount_cap_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    label = stringResource(R.string.discount_cap_limit),
                    checked = state.limited,
                    onCheckedChange = actions.onLimitedChange,
                )
                if (state.limited) {
                    CountedTextField(
                        value = state.value,
                        onValueChange = actions.onValueChange,
                        label = stringResource(R.string.discount_cap_field),
                        maxLength = CAP_MAX_LENGTH,
                        errorMessage = stringResource(R.string.discount_cap_out_of_range).takeIf { state.outOfRange },
                        keyboardType = KeyboardType.Decimal,
                    )
                }
                if (state.saved) StatusBanner(StatusTone.SUCCESS, stringResource(R.string.discount_cap_saved))
                if (state.failed) StatusBanner(StatusTone.ERROR, stringResource(R.string.discount_cap_save_error))
                Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                    Text(stringResource(R.string.discount_cap_save))
                }
            }
        }
    }
}
