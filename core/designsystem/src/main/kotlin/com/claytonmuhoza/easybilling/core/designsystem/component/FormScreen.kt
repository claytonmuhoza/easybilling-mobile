package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.claytonmuhoza.easybilling.core.designsystem.R
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing

/** Keeps a form readable on a tablet instead of stretching its fields across the whole screen. */
private val FormMaxWidth = 480.dp

/**
 * Screen made of a title bar and a content area that stays above the keyboard. The states
 * (loading, empty, error) of a screen are shown in its content area, under the same title bar.
 *
 * @param title title shown in the top bar.
 * @param modifier modifier applied to the screen.
 * @param onBack called when the user presses the back arrow; no arrow is shown when null.
 * @param actions buttons shown at the end of the top bar.
 * @param content the content of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitledScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) BackButton(onBack) },
                actions = actions,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
            content = content,
        )
    }
}

/**
 * Screen made of a title bar and a column of fields. The column scrolls, stays above the
 * keyboard and has a limited width on large screens.
 *
 * @param title title shown in the top bar.
 * @param modifier modifier applied to the screen.
 * @param onBack called when the user presses the back arrow; no arrow is shown when null.
 * @param actions buttons shown at the end of the top bar.
 * @param content the fields and buttons of the form, laid out in a column.
 */
@Composable
fun FormScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    TitledScreen(title = title, modifier = modifier, onBack = onBack, actions = actions) {
        FormColumn(content = content)
    }
}

/**
 * Scrolling column of fields with a limited width on large screens, for use inside a
 * [TitledScreen].
 *
 * @param modifier modifier applied to the column.
 * @param content the fields and buttons, laid out in a column.
 */
@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .widthIn(max = FormMaxWidth)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        content = content,
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ds_ic_arrow_back),
            contentDescription = stringResource(R.string.ds_action_back),
        )
    }
}
