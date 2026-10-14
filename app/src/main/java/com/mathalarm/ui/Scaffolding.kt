package com.mathalarm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mathalarm.R
import com.mathalarm.domain.Diagnostics

/**
 * The small set of pieces every Module 3 screen is built from.
 *
 * Deliberately plain: no `TopAppBar`, no icon pack, no navigation library. A header `Row` and a
 * scrolling `Column` need no experimental opt-in and no dependency beyond the Compose BOM that is
 * already here, and BUILD_SPECS §4 asks for a working configuration surface rather than a design
 * system.
 *
 * Like every other file in `ui/`, this layer holds **no audio object of any kind** — no
 * `MediaPlayer`, `AudioManager`, `AudioAttributes`, `Vibrator`, `Ringtone`, `WakeLock` or
 * `AlarmRinger`, and it never writes a stream volume (BUILD_SPECS §2's structural rule). Where a
 * screen needs to *report* on the alarm stream it asks
 * [com.mathalarm.alarm.AlarmStreamStatus] through
 * [com.mathalarm.system.SystemState], in the package that owns the audio.
 */

/** Header + scrolling body. [onBack] null means this is a root screen. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

/**
 * Red / amber / green. Never the only signal: every row that carries one also carries the words,
 * because a colour-only status is no status at all for a colour-blind user at 6am.
 */
@Composable
fun StatusDot(status: Diagnostics.Status, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = statusColor(status),
        modifier = modifier.size(12.dp),
        content = {},
    )
}

@Composable
fun statusColor(status: Diagnostics.Status): Color = when (status) {
    Diagnostics.Status.OK -> Color(0xFF1B873B)
    Diagnostics.Status.WARN -> Color(0xFFB26A00)
    Diagnostics.Status.FAIL -> MaterialTheme.colorScheme.error
}

@Composable
fun VSpace(height: Int) = Spacer(Modifier.height(height.dp))
