package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.core.designsystem.R

/**
 * Full-size state shown when a screen failed to load its content.
 *
 * @param message what went wrong, in words the user can act on.
 * @param modifier modifier applied to the whole state.
 * @param title heading of the error.
 * @param onRetry called when the user asks to try again; no retry button is shown when null.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.ds_state_error_title),
    onRetry: (() -> Unit)? = null,
) {
    val retryLabel = stringResource(R.string.ds_state_retry)
    StateLayout(
        title = title,
        message = message,
        modifier = modifier,
        action = onRetry?.let { StateAction(retryLabel, it) },
    ) {
        Icon(
            painter = painterResource(R.drawable.ds_ic_error),
            contentDescription = null,
            modifier = Modifier.size(StateIndicatorSize),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
