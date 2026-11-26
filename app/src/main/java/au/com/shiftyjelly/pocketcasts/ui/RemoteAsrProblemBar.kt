package au.com.shiftyjelly.pocketcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteAsrAlert
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteSttFailureKind
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * The bar that reports remote transcription failing, in the same slot the player uses for its own
 * problems: a pass runs in the background, so the failure has to be waiting for the user rather than
 * shown in a toast they already missed.
 *
 * It says which kind of failure it was, because "the model is gone" and "the account is empty" need
 * different fixes. [onRetry] is what brings the remote engine back after it was suspended; until then
 * the on-device engine keeps making transcripts.
 */
@Composable
fun RemoteAsrProblemBar(
    alert: RemoteAsrAlert,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.theme.colors
    val message = when (alert.kind) {
        RemoteSttFailureKind.ModelUnavailable -> stringResource(LR.string.remote_asr_problem_model_unavailable)

        RemoteSttFailureKind.OutOfFunds -> stringResource(LR.string.remote_asr_problem_out_of_funds)

        RemoteSttFailureKind.Network -> stringResource(LR.string.remote_asr_problem_network)

        RemoteSttFailureKind.Api -> stringResource(
            LR.string.remote_asr_problem_api,
            alert.status?.let { "HTTP $it" } ?: stringResource(LR.string.remote_asr_problem_api_unknown_status),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.primaryUi03)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp),
    ) {
        Text(
            text = message,
            color = colors.primaryText01,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Row(
            modifier = Modifier.align(Alignment.End),
        ) {
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(LR.string.remote_asr_problem_retry),
                    color = colors.primaryInteractive01,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(LR.string.remote_asr_problem_dismiss),
                    color = colors.primaryInteractive01,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}
