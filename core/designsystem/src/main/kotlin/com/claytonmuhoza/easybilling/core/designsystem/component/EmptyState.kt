package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.claytonmuhoza.easybilling.core.designsystem.R

/**
 * Full-size state shown when a screen has nothing to display yet.
 *
 * @param title short statement of what is missing.
 * @param modifier modifier applied to the whole state.
 * @param message explanation or hint shown under the title, or null for none.
 * @param action the way out offered to the user (for example creating the first item), or null.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: StateAction? = null,
) {
    StateLayout(title = title, message = message, modifier = modifier, action = action) {
        Icon(
            painter = painterResource(R.drawable.ds_ic_empty),
            contentDescription = null,
            modifier = Modifier.size(StateIndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
