package com.claytonmuhoza.easybilling.recovery

/**
 * What the user can do on the new PIN screen.
 *
 * @property onRecoveryCodeChange called when the recovery code changes.
 * @property onPinChange called when the new PIN changes.
 * @property onConfirmationChange called when the PIN confirmation changes.
 * @property onNext called when the user submits the step shown.
 * @property onBack called when the user returns to the previous step, or leaves from the first one.
 */
class ResetPinActions(
    val onRecoveryCodeChange: (String) -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onConfirmationChange: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
)
