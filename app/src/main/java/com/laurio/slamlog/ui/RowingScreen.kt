package com.laurio.slamlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laurio.slamlog.ble.HeartRateManager.State
import com.laurio.slamlog.data.Session
import com.laurio.slamlog.workout.WorkoutViewModel
import com.laurio.slamlog.workout.mmss
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val rowDay = SimpleDateFormat("EEE, MMM d", Locale.US)

/** Rowing machine setup: the same intervals you set on the rower's monitor. */
@Composable
fun RowingScreen(
    vm: WorkoutViewModel,
    onStart: () -> Unit,
    onOpen: (Session) -> Unit,
    onHistory: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val s by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val state by vm.hr.state.collectAsState()
    val bpm by vm.hr.heartRate.collectAsState()
    val rows = sessions.filter { it.isRow }
    fun set(change: (com.laurio.slamlog.data.UserSettings) -> com.laurio.slamlog.data.UserSettings) = vm.updateSettings(change(s))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ROWING", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        val current = bpm
        Text(
            if (state == State.CONNECTED && current != null) "Heart rate $current bpm"
            else "Heart rate isn't connected. Go Back to connect your strap.",
            color = if (state == State.CONNECTED) Mint else MaterialTheme.colorScheme.error,
        )

        SectionCard {
            Label("Rowing workout")
            Stepper("Intervals", "${s.rowIntervals}",
                onMinus = { set { it.copy(rowIntervals = (it.rowIntervals - 1).coerceAtLeast(1)) } },
                onPlus = { set { it.copy(rowIntervals = (it.rowIntervals + 1).coerceAtMost(30)) } })
            Stepper("Distance each", "${s.rowDistanceM} m",
                onMinus = { set { it.copy(rowDistanceM = (it.rowDistanceM - 100).coerceAtLeast(100)) } },
                onPlus = { set { it.copy(rowDistanceM = (it.rowDistanceM + 100).coerceAtMost(10_000)) } })
            Stepper("Resistance", "${s.rowResistance}",
                onMinus = { set { it.copy(rowResistance = (it.rowResistance - 1).coerceAtLeast(1)) } },
                onPlus = { set { it.copy(rowResistance = (it.rowResistance + 1).coerceAtMost(20)) } })
            Stepper("Recovery target", "${s.rowTargetHr} bpm",
                onMinus = { set { it.copy(rowTargetHr = (it.rowTargetHr - 5).coerceAtLeast(80)) } },
                onPlus = { set { it.copy(rowTargetHr = (it.rowTargetHr + 5).coerceAtMost(200)) } })
            SwitchRow(
                "Rest until HR ≤ ${s.rowTargetHr}",
                "Rest lasts the minimum below, or until you're down to ${s.rowTargetHr} (then 5 s to go), whichever is longer.",
                s.rowRestUntilTarget,
            ) { v -> set { it.copy(rowRestUntilTarget = v) } }
            if (s.rowRestUntilTarget) {
                Stepper("Minimum rest", mmss(s.rowMinRestSec),
                    onMinus = { set { it.copy(rowMinRestSec = (it.rowMinRestSec - 15).coerceAtLeast(15)) } },
                    onPlus = { set { it.copy(rowMinRestSec = (it.rowMinRestSec + 15).coerceAtMost(600)) } })
            } else {
                Stepper("Rest", mmss(s.rowRestSec),
                    onMinus = { set { it.copy(rowRestSec = (it.rowRestSec - 15).coerceAtLeast(15)) } },
                    onPlus = { set { it.copy(rowRestSec = (it.rowRestSec + 15).coerceAtMost(600)) } })
            }
            Text(
                "Set the same intervals on the rower. Tap INTERVAL DONE on the phone when each one finishes: " +
                    "that starts the rest and the clock to ${s.rowTargetHr} bpm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("START ROWING  ·  ${s.rowIntervals} × ${s.rowDistanceM} m", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        if (rows.isNotEmpty()) {
            SectionCard {
                Label("Recent rowing")
                rows.take(6).forEach { r ->
                    val target = r.sets.firstOrNull { it.targetHr > 0 }?.targetHr
                    val toTarget = target?.let { r.avgTimeToTarget(it) }
                    Column(Modifier.fillMaxWidth().clickable { onOpen(r) }.padding(vertical = 4.dp)) {
                        Text("${rowDay.format(Date(r.startMillis))} · ${r.shape}", fontWeight = FontWeight.SemiBold)
                        val pace = r.avgPace500?.let { "${mmss(it.roundToInt())} /500 m" } ?: "—"
                        val rec = if (toTarget != null) " · to $target in ${mmss(toTarget.roundToInt())} avg" else ""
                        Text("$pace$rec", style = MaterialTheme.typography.bodySmall, color = Sky)
                    }
                }
                Text(
                    "Pace is worked out from the distance and the time between GO and INTERVAL DONE.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("History") }
        Spacer(Modifier.height(8.dp))
    }
}
