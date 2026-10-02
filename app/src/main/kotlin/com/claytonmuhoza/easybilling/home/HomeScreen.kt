package com.claytonmuhoza.easybilling.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState

/**
 * Provisional home screen: an empty shell until the first features are delivered.
 *
 * @param modifier modifier applied to the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        EmptyState(
            title = stringResource(R.string.home_empty_title),
            modifier = Modifier.padding(innerPadding),
            message = stringResource(R.string.home_empty_message),
        )
    }
}
