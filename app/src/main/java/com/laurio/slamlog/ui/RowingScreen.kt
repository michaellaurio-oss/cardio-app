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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laurio.slamlog.ble.HeartRateManager.State
import com.laurio.slamlog.ble.RowerManager
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
    onRowerTest: () -> Unit,
    onTrends: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val s by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val state by vm.hr.state.collectAsState()
    val bpm by vm.hr.heartRate.collectAsState()
    val rows = sessions.filter { it.isRow }
    val rState by vm.rowMachine.state.collectAsState()
    val rName by vm.rowMachine.name.collectAsState()
    val rDevices by vm.rowMachine.devices.collectAsState()
    val rError by vm.rowMachine.error.collectAsState()
    val rData by vm.rowMachine.data.collectAsState()
    val rowerOn = rState == RowerManager.State.CONNECTED
    // Reconnect to the saved rower whenever this screen opens (the monitor must be on Connect App).
    LaunchedEffect(Unit) { vm.rowMachine.reconnect() }
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
            Label("Rower")
            Text(
                when (rState) {
                    RowerManager.State.CONNECTED -> "Connected · ${rName ?: "rower"}" +
                        (rData?.distanceM?.let { " · $it m on the monitor" } ?: "")
                    RowerManager.State.CONNECTING -> "Connecting to ${rName ?: "the rower"}…"
                    RowerManager.State.SCANNING -> "Searching… put the monitor on Connect App."
                    RowerManager.State.IDLE -> if (vm.rowMachine.hasSavedRower) "Not connected" else "No rower paired yet"
                },
                color = if (rowerOn) Mint else MaterialTheme.colorScheme.onSurface,
            )
            rError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (rState) {
                    RowerManager.State.CONNECTED, RowerManager.State.CONNECTING ->
                        OutlinedButton(onClick = { vm.rowMachine.disconnect() }) { Text("Disconnect") }
                    RowerManager.State.SCANNING ->
                        OutlinedButton(onClick = { vm.rowMachine.stopScan() }) { Text("Stop searching") }
                    RowerManager.State.IDLE -> {
                        Button(onClick = { vm.rowMachine.startScan() }) { Text("Find rower") }
                        if (vm.rowMachine.hasSavedRower) OutlinedButton(onClick = { vm.rowMachine.reconnect() }) { Text("Reconnect") }
                    }
                }
            }
            if (rState == RowerManager.State.SCANNING || (rState == RowerManager.State.IDLE && rDevices.isNotEmpty())) {
                rDevices.forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, Modifier.weight(1f))
                        TextButton(onClick = { vm.rowMachine.connect(d) }) { Text("Connect") }
                    }
                }
            }
            Text(
                when {
                    s.rowByTime && rowerOn -> "Intervals end on the clock; the rower adds your meters, pace, stroke rate and watts."
                    s.rowByTime -> "Intervals end on the clock. Connect the rower (monitor on Connect App, then Find rower " +
                        "or Reconnect) to also record meters, pace, stroke rate and watts."
                    rowerOn -> "Each interval ends by itself at ${s.rowDistanceM} m, and records your real pace, " +
                        "stroke rate and watts. Just row: the app runs the intervals and the rests."
                    else -> "On the rower's monitor choose Connect App, then Find rower (or Reconnect). Without it, " +
                        "tap INTERVAL DONE at the end of each interval."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Label("Rowing workout")
            Stepper("Intervals", "${s.rowIntervals}",
                onMinus = { set { it.copy(rowIntervals = (it.rowIntervals - 1).coerceAtLeast(1)) } },
                onPlus = { set { it.copy(rowIntervals = (it.rowIntervals + 1).coerceAtMost(30)) } })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Intervals by", Modifier.weight(1f))
                Choice("Distance", !s.rowByTime) { set { it.copy(rowByTime = false) } }
                Choice("Time", s.rowByTime) { set { it.copy(rowByTime = true) } }
            }
            if (s.rowByTime) {
                Stepper("Time each", mmss(s.rowIntervalSec),
                    onMinus = { set { it.copy(rowIntervalSec = (it.rowIntervalSec - 30).coerceAtLeast(30)) } },
                    onPlus = { set { it.copy(rowIntervalSec = (it.rowIntervalSec + 30).coerceAtMost(60 * 60)) } })
            } else {
                Stepper("Distance each", "${s.rowDistanceM} m",
                    onMinus = { set { it.copy(rowDistanceM = (it.rowDistanceM - 100).coerceAtLeast(100)) } },
                    onPlus = { set { it.copy(rowDistanceM = (it.rowDistanceM + 100).coerceAtMost(10_000)) } })
            }
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
                "The end of each interval starts the rest and the clock to ${s.rowTargetHr} bpm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("START ROWING  ·  ${s.rowIntervals} × " + (if (s.rowByTime) mmss(s.rowIntervalSec) else "${s.rowDistanceM} m"),
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        if (rows.isNotEmpty()) {
            SectionCard {
                Label("Recent rowing")
                rows.take(6).forEach { r ->
                    val target = r.sets.firstOrNull { it.targetHr > 0 }?.targetHr
                    val toTarget = target?.let { r.avgTimeToTarget(it) }
                    Column(Modifier.fillMaxWidth().clickable { onOpen(r) }.padding(vertical = 4.dp)) {
                        Text("${rowDay.format(Date(r.startMillis))} · ${r.shape}", fontWeight = FontWeight.SemiBold)
                        val pace = r.avgPace?.let { "${mmss(it.roundToInt())} /500 m" } ?: "—"
                        val rec = if (toTarget != null) " · to $target in ${mmss(toTarget.roundToInt())} avg" else ""
                        Text("$pace$rec", style = MaterialTheme.typography.bodySmall, color = Sky)
                    }
                }
                Text(
                    "Pace per 500 m, from the meters rowed and the time from GO to the end of each interval.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                Text(if (rows.isEmpty()) "History" else "History (${rows.size})")
            }
            OutlinedButton(onClick = onTrends, modifier = Modifier.weight(1f)) { Text("Trends") }
        }
        TextButton(onClick = onRowerTest, modifier = Modifier.fillMaxWidth()) {
            Text("Rower test: check what the rower sends over Bluetooth")
        }
        Spacer(Modifier.height(8.dp))
    }
}
