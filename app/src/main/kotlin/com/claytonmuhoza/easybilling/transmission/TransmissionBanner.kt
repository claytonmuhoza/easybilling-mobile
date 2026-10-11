package com.claytonmuhoza.easybilling.transmission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.core.designsystem.theme.palette
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionLevel
import java.time.Duration

private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_TWO_DAYS = 48L

/**
 * The state of the transmission wired to its view model.
 *
 * @param compact true where the banner must not take room: it then shows only when something
 * waits, was refused or the network is down.
 * @param onOpen called when the user touches the banner to see the transmission.
 * @param modifier modifier applied to the banner.
 * @param viewModel holder of the transmission state.
 */
@Composable
fun TransmissionBannerRoute(
    compact: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransmissionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    (state as? TransmissionUiState.Ready)?.let { TransmissionBanner(it, compact, onOpen, modifier) }
}

/**
 * The state of the transmission in one glance (UC-D03): network, documents waiting and since
 * when, refused invoices. Green, then orange after an hour of waiting, red after a day or as soon
 * as an invoice is refused; always with an icon and words.
 *
 * @param state the transmission state.
 * @param compact true to show a single line, and nothing at all when everything is fine.
 * @param onOpen called when the user touches the banner.
 * @param modifier modifier applied to the banner.
 */
@Composable
fun TransmissionBanner(
    state: TransmissionUiState.Ready,
    compact: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = state.tone()
    if (compact && tone == StatusTone.SUCCESS) return
    val palette = tone.palette()
    TonalCard(modifier = modifier.fillMaxWidth(), onClick = onOpen, container = palette.container) {
        Row(
            modifier = Modifier.padding(
                horizontal = Spacing.medium,
                vertical = if (compact) Spacing.small else Spacing.medium,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            AppIconImage(state.icon(), contentDescription = null, tint = palette.content)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.headline(),
                    color = palette.content,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!compact) {
                    Text(text = state.detail(), color = palette.content, style = MaterialTheme.typography.bodySmall)
                }
            }
            AppIconImage(AppIcon.CHEVRON_RIGHT, contentDescription = null, tint = palette.content)
        }
    }
}

/** @return the colour of the state: the level of the transmission, and at least orange without network. */
internal fun TransmissionUiState.Ready.tone(): StatusTone = when (status.level) {
    TransmissionLevel.ALERT -> StatusTone.ERROR
    TransmissionLevel.WARNING -> StatusTone.PENDING
    TransmissionLevel.OK -> if (online && status.queue.isEmpty()) StatusTone.SUCCESS else StatusTone.PENDING
}

private fun TransmissionUiState.Ready.icon(): AppIcon = when {
    !online -> AppIcon.CLOUD_OFF
    status.rejected.isNotEmpty() || status.stockToResolve.isNotEmpty() -> AppIcon.ERROR
    status.queue.isNotEmpty() -> AppIcon.SCHEDULE
    else -> AppIcon.CLOUD_DONE
}

/** @return what matters most: a refusal, then the lack of network, then what waits. */
@Composable
internal fun TransmissionUiState.Ready.headline(): String = when {
    status.rejected.isNotEmpty() ->
        pluralStringResource(R.plurals.transmission_rejected, status.rejected.size, status.rejected.size)
    status.stockToResolve.isNotEmpty() -> pluralStringResource(
        R.plurals.transmission_stock_to_resolve,
        status.stockToResolve.size,
        status.stockToResolve.size,
    )
    !online -> stringResource(R.string.transmission_offline)
    status.queue.isNotEmpty() ->
        pluralStringResource(R.plurals.transmission_waiting, status.queue.size, status.queue.size)
    else -> stringResource(R.string.transmission_synchronised)
}

@Composable
private fun TransmissionUiState.Ready.detail(): String {
    val waiting = pluralStringResource(R.plurals.transmission_waiting, status.queue.size, status.queue.size)
    val oldest = status.oldestWait
    return when {
        oldest == null -> stringResource(R.string.transmission_nothing_waiting)
        status.rejected.isNotEmpty() || !online ->
            stringResource(R.string.transmission_waiting_since, waiting, waitText(oldest))
        else -> stringResource(R.string.transmission_oldest, waitText(oldest))
    }
}

/** @return a wait in a few letters: `less than a minute`, `14 min`, `5 h`, `3 d`. */
@Composable
internal fun waitText(wait: Duration): String = when {
    wait.toMinutes() < 1 -> stringResource(R.string.wait_under_a_minute)
    wait.toMinutes() < MINUTES_PER_HOUR -> stringResource(R.string.wait_minutes, wait.toMinutes())
    wait.toHours() < HOURS_PER_TWO_DAYS -> stringResource(R.string.wait_hours, wait.toHours())
    else -> stringResource(R.string.wait_days, wait.toDays())
}
