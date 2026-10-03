package com.claytonmuhoza.easybilling.taxprofile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.FieldProblem
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.SaveTaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileChoice
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the tax profile form. */
sealed interface TaxProfileUiState {
    /** The current profile is being read. */
    data object Loading : TaxProfileUiState

    /** The current profile could not be read. */
    data object LoadFailed : TaxProfileUiState

    /**
     * The form is shown.
     *
     * @property form the profile as typed so far.
     * @property problems refused text fields of the last save.
     * @property missingChoices choices not made at the last save.
     * @property saving true while the profile is being saved.
     * @property saveFailed true if the last save could not be written.
     */
    data class Editing(
        val form: TaxProfileForm,
        val problems: Map<TaxProfileField, FieldProblem> = emptyMap(),
        val missingChoices: Set<TaxProfileChoice> = emptySet(),
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : TaxProfileUiState

    /** The profile is saved, or was already the same: the screen can close. */
    data object Done : TaxProfileUiState
}

/** Drives the entry and the change of the tax profile (UC-A02). */
@HiltViewModel
class TaxProfileViewModel @Inject constructor(
    private val profiles: TaxProfileRepository,
    private val saveTaxProfile: SaveTaxProfile,
    private val sessions: SessionManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow<TaxProfileUiState>(TaxProfileUiState.Loading)

    /** The state to show. */
    val state: StateFlow<TaxProfileUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the current profile into the form. Also used to retry. */
    fun load() {
        mutableState.value = TaxProfileUiState.Loading
        viewModelScope.launch {
            mutableState.value = storageCall { profiles.current() }.fold(
                onSuccess = { current ->
                    TaxProfileUiState.Editing(current?.let { TaxProfileForm.from(it.profile) } ?: TaxProfileForm())
                },
                onFailure = { TaxProfileUiState.LoadFailed },
            )
        }
    }

    /**
     * @param field a text field.
     * @param value its new content.
     */
    fun onTextChange(field: TaxProfileField, value: String) = edit {
        copy(form = form.with(field, value), problems = problems - field)
    }

    /** @param type the chosen kind of taxpayer. */
    fun onTaxpayerTypeChange(type: TaxpayerType) = edit {
        copy(form = form.copy(taxpayerType = type), missingChoices = missingChoices - TaxProfileChoice.TAXPAYER_TYPE)
    }

    /** @param center the chosen tax office. */
    fun onFiscalCenterChange(center: FiscalCenter) = edit {
        copy(form = form.copy(fiscalCenter = center), missingChoices = missingChoices - TaxProfileChoice.FISCAL_CENTER)
    }

    /** @param taxes the new tax registrations. */
    fun onTaxesChange(taxes: TaxRegistrations) = edit { copy(form = form.copy(taxes = taxes)) }

    /** Saves the profile as a new version, or shows what is wrong with it. */
    fun save() {
        val editing = mutableState.value as? TaxProfileUiState.Editing ?: return
        val actor = sessions.current.value?.userId
        if (editing.saving || actor == null) return
        mutableState.value = editing.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            mutableState.value = storageCall { saveTaxProfile(editing.form, actor) }.fold(
                onSuccess = { result ->
                    when (result) {
                        is SaveTaxProfile.Result.Saved, is SaveTaxProfile.Result.Unchanged -> TaxProfileUiState.Done
                        is SaveTaxProfile.Result.Invalid ->
                            editing.copy(problems = result.problems, missingChoices = result.missingChoices)
                    }
                },
                onFailure = { editing.copy(saveFailed = true) },
            )
        }
    }

    private fun edit(change: TaxProfileUiState.Editing.() -> TaxProfileUiState.Editing) {
        val editing = mutableState.value as? TaxProfileUiState.Editing ?: return
        mutableState.value = editing.change()
    }
}
