package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.core.designsystem.R

/**
 * Full-size state shown while a screen loads its content.
 *
 * @param modifier modifier applied to the whole state.
 * @param message text shown under the progress indicator and read by screen readers.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.ds_state_loading),
) {
    StateLayout(title = null, message = message, modifier = modifier) {
        CircularProgressIndicator(modifier = Modifier.size(StateIndicatorSize))
    }
}
