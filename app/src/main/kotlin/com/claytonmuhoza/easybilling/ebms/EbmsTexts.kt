package com.claytonmuhoza.easybilling.ebms

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialField
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.UrlProblem
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome

/** @return the name of the environment, as shown on screen. */
@Composable
fun EbmsEnvironment.label(): String = stringResource(
    when (this) {
        EbmsEnvironment.TEST -> R.string.ebms_environment_test
        EbmsEnvironment.PRODUCTION -> R.string.ebms_environment_production
    },
)

/** @return the label of the field. The user name of the OBR is the identifier of the system. */
@Composable
fun EbmsCredentialField.label(): String = stringResource(
    when (this) {
        EbmsCredentialField.USERNAME -> R.string.ebms_field_system_id
        EbmsCredentialField.PASSWORD -> R.string.ebms_field_password
    },
)

/** @return the message explaining why the address is refused. */
@Composable
fun UrlProblem.message(): String = stringResource(
    when (this) {
        UrlProblem.MISSING -> R.string.ebms_url_missing
        UrlProblem.NOT_HTTPS -> R.string.ebms_url_not_https
        UrlProblem.MALFORMED -> R.string.ebms_url_malformed
    },
)

/** @return the message telling the user what the test of the connection gave (UC-A05). */
@Composable
fun LoginOutcome.message(): String = when (this) {
    is LoginOutcome.Success -> stringResource(R.string.ebms_outcome_success)
    LoginOutcome.NotConfigured -> stringResource(R.string.ebms_outcome_not_configured)
    LoginOutcome.WrongCredentials -> stringResource(R.string.ebms_outcome_wrong_credentials)
    is LoginOutcome.Refused -> stringResource(R.string.ebms_outcome_refused, message)
    LoginOutcome.ServerError -> stringResource(R.string.ebms_outcome_server_error)
    LoginOutcome.Unreachable -> stringResource(R.string.ebms_outcome_unreachable)
    LoginOutcome.CertificateRejected -> stringResource(R.string.ebms_outcome_certificate)
    LoginOutcome.InvalidResponse -> stringResource(R.string.ebms_outcome_invalid_response)
}
