package com.claytonmuhoza.easybilling.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the list of the accounts. */
sealed interface UserListUiState {
    /** The accounts are being read. */
    data object Loading : UserListUiState

    /** The accounts could not be read. */
    data object Failed : UserListUiState

    /**
     * The accounts are shown.
     *
     * @property users the accounts, the active ones first, each group oldest first.
     */
    data class Ready(val users: List<User>) : UserListUiState
}

/** Lists the accounts of the device (UC-H01). */
@HiltViewModel
class UserListViewModel @Inject constructor(private val users: UserRepository) : ViewModel() {

    private val mutableState = MutableStateFlow<UserListUiState>(UserListUiState.Loading)

    /** The state to show. */
    val state: StateFlow<UserListUiState> = mutableState.asStateFlow()

    /** Reads the accounts. Called each time the screen is shown, and to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { users.findAll().sortedByDescending { it.active } }
                .fold(onSuccess = { UserListUiState.Ready(it) }, onFailure = { UserListUiState.Failed })
        }
    }
}
