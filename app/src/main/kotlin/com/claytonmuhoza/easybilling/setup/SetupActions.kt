package com.claytonmuhoza.easybilling.setup

/**
 * What the user can do in the first launch assistant.
 *
 * @property onNameChange called when the name changes.
 * @property onPinChange called when the PIN changes.
 * @property onConfirmationChange called when the PIN confirmation changes.
 * @property onNext called when the user submits the step shown.
 * @property onBack called when the user returns to the previous step.
 */
class SetupActions(
    val onNameChange: (String) -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onConfirmationChange: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
)
