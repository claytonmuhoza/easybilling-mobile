package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.identity.ApprovalActions
import com.claytonmuhoza.easybilling.identity.ApprovalContent
import com.claytonmuhoza.easybilling.identity.ApprovalError
import com.claytonmuhoza.easybilling.identity.ApprovalUiState
import com.claytonmuhoza.easybilling.identity.Approver
import com.claytonmuhoza.easybilling.login.LoginAccount
import com.claytonmuhoza.easybilling.login.SwitchError
import com.claytonmuhoza.easybilling.login.SwitchUserActions
import com.claytonmuhoza.easybilling.login.SwitchUserScreen
import com.claytonmuhoza.easybilling.login.SwitchUserUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.users.DiscountCapActions
import com.claytonmuhoza.easybilling.users.DiscountCapScreen
import com.claytonmuhoza.easybilling.users.DiscountCapUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the quick switch to another user, of the approval of a manager and of the cap of
 * the discount (UC-H03, UC-H04, UC-C03), in every [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SwitchApprovalScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val bob = LoginAccount(2, "Bob Ndayishimiye")
    private val claudine = LoginAccount(3, "Claudine Irakoze")
    private val approvers = listOf(Approver(1, "Alice Niyonzima"), Approver(3, "Claudine Irakoze"))
    /** What the approval says of a discount of 25 % for a cap of 10 %, in the language of the variant. */
    private val discountMessage: String
        @Composable get() = stringResource(R.string.approval_discount_message, "25", "10")

    @Test
    fun switchChoose() = capture("switch_user_choose") {
        SwitchUserScreen(SwitchUserUiState.Choose(listOf(bob, claudine)), SwitchUserActions())
    }

    @Test
    fun switchNone() = capture("switch_user_none") {
        SwitchUserScreen(SwitchUserUiState.Choose(emptyList()), SwitchUserActions())
    }

    @Test
    fun switchWrongPin() = capture("switch_user_wrong_pin") {
        SwitchUserScreen(SwitchUserUiState.Pin(bob, error = SwitchError.WrongPin(9)), SwitchUserActions())
    }

    @Test
    fun switchTemporaryPin() = capture("switch_user_temporary_pin") {
        SwitchUserScreen(SwitchUserUiState.Pin(bob, error = SwitchError.PinChangeRequired), SwitchUserActions())
    }

    @Test
    fun approvalChoose() = capture("approval_choose") {
        ApprovalContent(discountMessage, ApprovalUiState(approvers), ApprovalActions(), busy = false)
    }

    @Test
    fun approvalWrongPin() = capture("approval_wrong_pin") {
        ApprovalContent(
            message = discountMessage,
            state = ApprovalUiState(approvers, chosen = 3, error = ApprovalError.WrongPin(9)),
            actions = ApprovalActions(),
            busy = false,
        )
    }

    @Test
    fun approvalNone() = capture("approval_none") {
        ApprovalContent(discountMessage, ApprovalUiState(emptyList()), ApprovalActions(), busy = false)
    }

    @Test
    fun discountCapNone() = capture("discount_cap_none") {
        DiscountCapScreen(DiscountCapUiState.Editing(), DiscountCapActions())
    }

    @Test
    fun discountCapOutOfRange() = capture("discount_cap_out_of_range") {
        DiscountCapScreen(
            DiscountCapUiState.Editing(limited = true, value = "100", outOfRange = true),
            DiscountCapActions(),
        )
    }

    @Test
    fun discountCapSaved() = capture("discount_cap_saved") {
        DiscountCapScreen(
            DiscountCapUiState.Editing(limited = true, value = "12.5", saved = true),
            DiscountCapActions(),
        )
    }

    @Test
    fun discountCapFailed() = capture("discount_cap_failed") {
        DiscountCapScreen(DiscountCapUiState.Failed, DiscountCapActions())
    }

    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
