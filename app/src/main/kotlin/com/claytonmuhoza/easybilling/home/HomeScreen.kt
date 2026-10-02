package com.claytonmuhoza.easybilling.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState

/**
 * Provisional home screen: an empty shell until the first features are delivered.
 *
 * @param userName name of the logged in user.
 * @param onLogOut called when the user asks to log out.
 * @param modifier modifier applied to the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(userName: String, onLogOut: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onLogOut) { Text(stringResource(R.string.home_log_out)) }
                },
            )
        },
    ) { innerPadding ->
        EmptyState(
            title = stringResource(R.string.home_welcome, userName),
            modifier = Modifier.padding(innerPadding),
            message = stringResource(R.string.home_empty_message),
        )
    }
}
