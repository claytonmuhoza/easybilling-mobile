package com.claytonmuhoza.easybilling.access

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.navigation.EasyBillingNavHost
import kotlinx.coroutines.delay

/** How often the inactivity of the user is checked while a session is open. */
private const val INACTIVITY_CHECK_INTERVAL_MILLIS = 5_000L

/**
 * Root of the user interface: shows the section of the application the access state allows, and
 * watches the activity of the user to end an idle session.
 *
 * @param modifier modifier applied to the root.
 * @param viewModel holder of the access state.
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier, viewModel: AccessViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Coming back to the application after a long time must not show the previous session.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.checkInactivity() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Initial pass: every touch is seen here first, and left for the screens.
                        awaitPointerEvent(PointerEventPass.Initial)
                        viewModel.onUserInteraction()
                    }
                }
            },
    ) {
        when (val current = state) {
            AccessState.Loading -> LoadingState()
            AccessState.Failed -> ErrorState(
                message = stringResource(R.string.access_error_message),
                onRetry = viewModel::load,
            )
            is AccessState.Ready -> {
                if (current is AccessState.LoggedIn) {
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(INACTIVITY_CHECK_INTERVAL_MILLIS)
                            viewModel.checkInactivity()
                        }
                    }
                }
                EasyBillingNavHost(
                    access = current,
                    onSetupFinished = viewModel::onSetupFinished,
                    onLogOut = viewModel::logOut,
                )
            }
        }
    }
}
