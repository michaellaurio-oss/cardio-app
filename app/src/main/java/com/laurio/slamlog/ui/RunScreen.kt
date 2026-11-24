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

private val runDay = SimpleDateFormat("EEE, MMM d", Locale.US)

/** Walk/Run on a treadmill: run intervals with a walk between them until heart rate recovers. */
@Composable
fun RunScreen(
    vm: WorkoutViewModel,
    onStart: () -> Unit,
    onOpen: (Session) -> Unit,
    onHistory: () -> Unit,
    onTreadmillTest: () -> Unit,
    onTrends: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val s by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val state by vm.hr.state.collectAsState()
    val bpm by vm.hr.heartRate.collectAsState()
    val runs = sessions.filter { it.isRun }
    val tm = vm.treadmill
    val tState by tm.state.collectAsState()
    val tName by tm.name.collectAsState()
    val tDevices by tm.devices.collectAsState()
    val tError by tm.error.collectAsState()
    val tData by tm.data.collectAsState()
    val tmOn = tState == RowerManager.State.CONNECTED
    // Reconnect to the saved treadmill whenever this screen opens.
    LaunchedEffect(Unit) { tm.reconnect() }
    fun set(change: (com.laurio.slamlog.data.UserSettings) -> com.laurio.slamlog.data.UserSettings) = vm.updateSettings(change(s))
    val distText = "%.2f mi".format(s.runDistanceHundredthsMi / 100.0)
    val each = if (s.runByTime) mmss(s.runIntervalSec) else distText

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WALK / RUN", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
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
            Label("Treadmill (optional)")
            Text(
                when (tState) {
                    RowerManager.State.CONNECTED -> "Connected · ${tName ?: "treadmill"}" +
                        (tData?.speedKmh?.let { " · %.1f mph".format(it / 1.609344) } ?: "")
                    RowerManager.State.CONNECTING -> "Connecting to ${tName ?: "the treadmill"}…"
                    RowerManager.State.SCANNING -> "Searching for treadmills…"
                    RowerManager.State.IDLE -> if (tm.hasSavedRower) "Not connected" else "No treadmill paired"
                },
                color = if (tmOn) Mint else MaterialTheme.colorScheme.onSurface,
            )
            tError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tState) {
                    RowerManager.State.CONNECTED, RowerManager.State.CONNECTING ->
                        OutlinedButton(onClick = { tm.disconnect() }) { Text("Disconnect") }
                    RowerManager.State.SCANNING ->
                        OutlinedButton(onClick = { tm.stopScan() }) { Text("Stop searching") }
                    RowerManager.State.IDLE -> {
                        Button(onClick = { tm.startScan() }) { Text("Find treadmill") }
                        if (tm.hasSavedRower) OutlinedButton(onClick = { tm.reconnect() }) { Text("Reconnect") }
                    }
                }
            }
            if (tState == RowerManager.State.SCANNING || (tState == RowerManager.State.IDLE && tDevices.isNotEmpty())) {
                tDevices.forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, Modifier.weight(1f))
                        TextButton(onClick = { tm.connect(d) }) { Text("Connect") }
                    }
                }
            }
            Text(
                if (tmOn) "The treadmill's distance and speed are recorded" +
                    (if (s.runByTime) "." else ", and each interval ends by itself at $distText.")
                else "Works without it: by time, intervals end on the clock; by distance, tap INTERVAL DONE. " +
                    "A treadmill with Bluetooth fitness-machine support adds real distance and speed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Label("Run intervals")
            Stepper("Intervals", "${s.runIntervals}",
                onMinus = { set { it.copy(runIntervals = (it.runIntervals - 1).coerceAtLeast(1)) } },
                onPlus = { set { it.copy(runIntervals = (it.runIntervals + 1).coerceAtMost(30)) } })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Intervals by", Modifier.weight(1f))
                Choice("Distance", !s.runByTime) { set { it.copy(runByTime = false) } }
                Choice("Time", s.runByTime) { set { it.copy(runByTime = true) } }
            }
            if (s.runByTime) {
                Stepper("Time each", mmss(s.runIntervalSec),
                    onMinus = { set { it.copy(runIntervalSec = (it.runIntervalSec - 30).coerceAtLeast(30)) } },
                    onPlus = { set { it.copy(runIntervalSec = (it.runIntervalSec + 30).coerceAtMost(60 * 60)) } })
            } else {
                Stepper("Distance each", distText,
                    onMinus = { set { it.copy(runDistanceHundredthsMi = (it.runDistanceHundredthsMi - 5).coerceAtLeast(5)) } },
                    onPlus = { set { it.copy(runDistanceHundredthsMi = (it.runDistanceHundredthsMi + 5).coerceAtMost(1000)) } })
            }
            Stepper("Speed", "%.1f mph".format(s.runSpeedTenthsMph / 10.0),
                onMinus = { set { it.copy(runSpeedTenthsMph = (it.runSpeedTenthsMph - 1).coerceAtLeast(10)) } },
                onPlus = { set { it.copy(runSpeedTenthsMph = (it.runSpeedTenthsMph + 1).coerceAtMost(150)) } })
            Stepper("Incline", "%.1f%%".format(s.runInclineTenths / 10.0),
                onMinus = { set { it.copy(runInclineTenths = (it.runInclineTenths - 5).coerceAtLeast(0)) } },
                onPlus = { set { it.copy(runInclineTenths = (it.runInclineTenths + 5).coerceAtMost(150)) } })
            Stepper("Recovery target", "${s.runTargetHr} bpm",
                onMinus = { set { it.copy(runTargetHr = (it.runTargetHr - 5).coerceAtLeast(80)) } },
                onPlus = { set { it.copy(runTargetHr = (it.runTargetHr + 5).coerceAtMost(200)) } })
            SwitchRow(
                "Walk until HR ≤ ${s.runTargetHr}",
                "The walk lasts the minimum below, or until you're down to ${s.runTargetHr} (then 5 s to go), whichever is longer.",
                s.runRestUntilTarget,
            ) { v -> set { it.copy(runRestUntilTarget = v) } }
            if (s.runRestUntilTarget) {
                Stepper("Minimum walk", mmss(s.runMinRestSec),
                    onMinus = { set { it.copy(runMinRestSec = (it.runMinRestSec - 15).coerceAtLeast(15)) } },
                    onPlus = { set { it.copy(runMinRestSec = (it.runMinRestSec + 15).coerceAtMost(600)) } })
            } else {
                Stepper("Walk", mmss(s.runRestSec),
                    onMinus = { set { it.copy(runRestSec = (it.runRestSec - 15).coerceAtLeast(15)) } },
                    onPlus = { set { it.copy(runRestSec = (it.runRestSec + 15).coerceAtMost(600)) } })
            }
            Text(
                "Speed and incline are what you set on the treadmill for the run intervals; they're saved with the " +
                    "workout. The end of each run starts the walk and the clock to ${s.runTargetHr} bpm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("START  ·  ${s.runIntervals} × $each", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        if (runs.isNotEmpty()) {
            SectionCard {
                Label("Recent walk/run")
                runs.take(6).forEach { r ->
                    val target = r.sets.firstOrNull { it.targetHr > 0 }?.targetHr
                    val toTarget = target?.let { r.avgTimeToTarget(it) }
                    Column(Modifier.fillMaxWidth().clickable { onOpen(r) }.padding(vertical = 4.dp)) {
                        Text("${runDay.format(Date(r.startMillis))} · ${r.shape}", fontWeight = FontWeight.SemiBold)
                        val pace = r.avgPace?.let { "${mmss(it.roundToInt())} /mi" } ?: "—"
                        val rec = if (toTarget != null) " · to $target in ${mmss(toTarget.roundToInt())} avg" else ""
                        Text("$pace$rec", style = MaterialTheme.typography.bodySmall, color = Sky)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                Text(if (runs.isEmpty()) "History" else "History (${runs.size})")
            }
            OutlinedButton(onClick = onTrends, modifier = Modifier.weight(1f)) { Text("Trends") }
        }
        TextButton(onClick = onTreadmillTest, modifier = Modifier.fillMaxWidth()) {
            Text("Treadmill test: check what it sends over Bluetooth")
        }
        Spacer(Modifier.height(8.dp))
    }
}
