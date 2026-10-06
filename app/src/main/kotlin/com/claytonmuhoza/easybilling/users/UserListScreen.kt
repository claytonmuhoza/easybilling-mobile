package com.claytonmuhoza.easybilling.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.InitialsAvatar
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StateAction
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.identity.User

/** Keeps the list readable on a tablet instead of stretching its cards across the whole screen. */
private val ListMaxWidth = 600.dp

/**
 * List of the accounts wired to its view model.
 *
 * @param currentUserId identifier of the logged in user, whose account is marked.
 * @param onBack called when the user goes back.
 * @param onOpenUser called with the identifier of the account the user picks.
 * @param onNewUser called when the user asks for a new account.
 * @param viewModel holder of the list state.
 */
@Composable
fun UserListRoute(
    currentUserId: Long,
    onBack: () -> Unit,
    onOpenUser: (Long) -> Unit,
    onNewUser: () -> Unit,
    viewModel: UserListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: an account may have changed on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    UserListScreen(
        state = state,
        currentUserId = currentUserId,
        actions = UserListActions(
            onBack = onBack,
            onOpenUser = onOpenUser,
            onNewUser = onNewUser,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the list of the accounts.
 *
 * @property onBack called when the user goes back.
 * @property onOpenUser called with the identifier of the account the user picks.
 * @property onNewUser called when the user asks for a new account.
 * @property onRetry called when the user asks to read the accounts again.
 */
class UserListActions(
    val onBack: () -> Unit = {},
    val onOpenUser: (Long) -> Unit = {},
    val onNewUser: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The accounts of the device with their roles, and whether each is disabled or has a PIN to
 * change (UC-H01).
 *
 * @param state the list state.
 * @param currentUserId identifier of the logged in user, whose account is marked.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun UserListScreen(
    state: UserListUiState,
    currentUserId: Long,
    actions: UserListActions,
    modifier: Modifier = Modifier,
) {
    TitledScreen(
        title = stringResource(R.string.users_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.users_new), onClick = actions.onNewUser) },
    ) {
        when (state) {
            UserListUiState.Loading -> LoadingState()
            UserListUiState.Failed -> ErrorState(
                message = stringResource(R.string.users_load_error),
                onRetry = actions.onRetry,
            )
            is UserListUiState.Ready -> if (state.users.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.users_title),
                    action = StateAction(stringResource(R.string.users_new), actions.onNewUser),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = ListMaxWidth)
                        .fillMaxSize()
                        .padding(horizontal = Spacing.large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    contentPadding = PaddingValues(vertical = Spacing.large),
                ) {
                    items(state.users, key = { it.id.value }) { user ->
                        UserCard(user, you = user.id.value == currentUserId, onOpen = actions.onOpenUser)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserCard(user: User, you: Boolean, onOpen: (Long) -> Unit) {
    TonalCard(onClick = { onOpen(user.id.value) }, modifier = Modifier.fillMaxWidth()) {
        CardListItem(
            leadingContent = { InitialsAvatar(user.name) },
            headlineContent = {
                Text(if (you) stringResource(R.string.users_you, user.name) else user.name)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    Text(user.roles.labels())
                    UserChips(user)
                }
            },
        )
    }
}

/** Whether the account is disabled, or has a PIN its user has to change; nothing otherwise. */
@Composable
internal fun UserChips(user: User) {
    if (user.active && !user.pinTemporary) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        if (!user.active) StatusChip(StatusTone.ERROR, stringResource(R.string.users_disabled))
        if (user.pinTemporary) StatusChip(StatusTone.PENDING, stringResource(R.string.users_pin_temporary))
    }
}
