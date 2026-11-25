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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laurio.slamlog.data.Activity
import com.laurio.slamlog.data.MachineBaseline
import com.laurio.slamlog.data.UserSettings
import com.laurio.slamlog.data.Units
import com.laurio.slamlog.workout.Progress
import com.laurio.slamlog.workout.WorkoutViewModel
import com.laurio.slamlog.workout.mmss

/** Definition of the rowing or walk/run baseline test. Opened from that workout's Baseline screen. */
@Composable
fun MachineBaselineSettingsScreen(vm: WorkoutViewModel, activity: Activity, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val b = s.machineBaseline(activity)
    val run = activity == Activity.RUN
    // Baseline tests that match the current definition: the ones a change would stop comparing.
    val matching = Progress.baselines(sessions, s, activity).size
    var confirmed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<UserSettings?>(null) }
    fun change(f: (MachineBaseline) -> MachineBaseline) {
        val nb = f(b)
        val next = if (run) s.copy(runBaseline = nb) else s.copy(rowBaseline = nb)
        if (matching > 0 && !confirmed) pending = next else vm.updateSettings(next)
    }

    BackHandler(onBack = onBack)

    pending?.let { next ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Change the baseline?") },
            text = {
                Text(
                    "You have $matching baseline test${if (matching == 1) "" else "s"} on the current settings. " +
                        "Changing them starts a fresh comparison: only tests done on the new settings are compared. " +
                        "Your earlier tests stay in History, and changing the settings back brings them into the " +
                        "comparison again."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmed = true; vm.updateSettings(next); pending = null }) { Text("Change it") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep current") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BASELINE SETTINGS", fontSize = 26.sp, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (matching > 0) {
            Text(
                "$matching baseline test${if (matching == 1) " uses" else "s use"} these settings. Changing them starts a fresh comparison.",
                color = Amber, fontWeight = FontWeight.SemiBold,
            )
        }

        SectionCard {
            Label(if (run) "The walk/run baseline test" else "The rowing baseline test")
            Stepper("Intervals", "${b.intervals}",
                onMinus = { change { it.copy(intervals = (it.intervals - 1).coerceAtLeast(1)) } },
                onPlus = { change { it.copy(intervals = (it.intervals + 1).coerceAtMost(30)) } })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (run) "Runs by" else "Intervals by", Modifier.weight(1f))
                Choice("Distance", !b.byTime) { change { it.copy(byTime = false) } }
                Choice("Time", b.byTime) { change { it.copy(byTime = true) } }
            }
            if (b.byTime) {
                Stepper(if (run) "Run time" else "Time each", mmss(b.intervalSec),
                    onMinus = { change { it.copy(intervalSec = (it.intervalSec - 30).coerceAtLeast(30)) } },
                    onPlus = { change { it.copy(intervalSec = (it.intervalSec + 30).coerceAtMost(60 * 60)) } })
            } else if (run) {
                // 0.05 mi steps.
                val step = Units.METERS_PER_MILE * 0.05
                Stepper("Run distance", Units.distance(activity, b.distanceM),
                    onMinus = { change { it.copy(distanceM = (it.distanceM - step).toInt().coerceAtLeast(step.toInt())) } },
                    onPlus = { change { it.copy(distanceM = Math.round(it.distanceM + step).toInt().coerceAtMost((Units.METERS_PER_MILE * 10).toInt())) } })
            } else {
                Stepper("Distance each", "${b.distanceM} m",
                    onMinus = { change { it.copy(distanceM = (it.distanceM - 100).coerceAtLeast(100)) } },
                    onPlus = { change { it.copy(distanceM = (it.distanceM + 100).coerceAtMost(10_000)) } })
            }
            if (run) {
                Stepper("Run speed", "%.1f mph".format(b.speedTenthsMph / 10.0),
                    onMinus = { change { it.copy(speedTenthsMph = (it.speedTenthsMph - 1).coerceAtLeast(10)) } },
                    onPlus = { change { it.copy(speedTenthsMph = (it.speedTenthsMph + 1).coerceAtMost(150)) } })
                Stepper("Walk speed", "%.1f mph".format(b.walkSpeedTenthsMph / 10.0),
                    onMinus = { change { it.copy(walkSpeedTenthsMph = (it.walkSpeedTenthsMph - 1).coerceAtLeast(5)) } },
                    onPlus = { change { it.copy(walkSpeedTenthsMph = (it.walkSpeedTenthsMph + 1).coerceAtMost(60)) } })
                Stepper("Incline", "%.1f%%".format(b.inclineTenths / 10.0),
                    onMinus = { change { it.copy(inclineTenths = (it.inclineTenths - 5).coerceAtLeast(0)) } },
                    onPlus = { change { it.copy(inclineTenths = (it.inclineTenths + 5).coerceAtMost(150)) } })
            } else {
                Stepper("Resistance", "${b.resistance}",
                    onMinus = { change { it.copy(resistance = (it.resistance - 1).coerceAtLeast(1)) } },
                    onPlus = { change { it.copy(resistance = (it.resistance + 1).coerceAtMost(20)) } })
            }
            Stepper("Recovery target", "${b.targetHr} bpm",
                onMinus = { change { it.copy(targetHr = (it.targetHr - 5).coerceAtLeast(80)) } },
                onPlus = { change { it.copy(targetHr = (it.targetHr + 5).coerceAtMost(200)) } })
            SwitchRow(
                "${if (run) "Walk" else "Rest"} until HR ≤ ${b.targetHr}",
                "Each interval starts from the same heart rate, so every interval gets timed",
                b.restUntilTarget,
            ) { v -> change { it.copy(restUntilTarget = v) } }
            Stepper(if (b.restUntilTarget) (if (run) "Minimum walk" else "Minimum rest") else (if (run) "Walk" else "Rest"), mmss(b.restSec),
                onMinus = { change { it.copy(restSec = (it.restSec - 15).coerceAtLeast(15)) } },
                onPlus = { change { it.copy(restSec = (it.restSec + 15).coerceAtMost(30 * 60)) } })
            Text(
                "Set this once and leave it alone. Only baseline tests that match these numbers are compared, " +
                    "so changing them starts a fresh comparison.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
