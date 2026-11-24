package com.laurio.slamlog.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Switch
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
import com.laurio.slamlog.workout.Physiology
import com.laurio.slamlog.workout.WorkoutViewModel
import com.laurio.slamlog.workout.mmss

@Composable
fun HomeScreen(
    vm: WorkoutViewModel,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onBaseline: () -> Unit,
    onHistory: () -> Unit,
    onTrends: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by vm.hr.state.collectAsState()
    val cfg by vm.config.collectAsState()
    val settings by vm.settings.collectAsState()
    val sessions = vm.sessions.collectAsState().value.filter { it.activity == com.laurio.slamlog.data.Activity.SLAM }
    val perms by vm.permissions.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SLAM BALL", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }

        HeartRateCard(vm, onRequestPermissions)

        // ---- Training workout setup ----
        SectionCard {
            Label("Training workout")
            Stepper("Sets", "${cfg.sets}",
                onMinus = { vm.updateConfig(cfg.copy(sets = (cfg.sets - 1).coerceAtLeast(1))) },
                onPlus = { vm.updateConfig(cfg.copy(sets = (cfg.sets + 1).coerceAtMost(30))) })
            Stepper("Reps per set", "${cfg.targetReps}",
                onMinus = { vm.updateConfig(cfg.copy(targetReps = (cfg.targetReps - 1).coerceAtLeast(1))) },
                onPlus = { vm.updateConfig(cfg.copy(targetReps = (cfg.targetReps + 1).coerceAtMost(200))) })
            Stepper("Recovery target", "${settings.recoveryTargetHr} bpm",
                onMinus = { vm.updateSettings(settings.copy(recoveryTargetHr = (settings.recoveryTargetHr - 5).coerceAtLeast(80))) },
                onPlus = { vm.updateSettings(settings.copy(recoveryTargetHr = (settings.recoveryTargetHr + 5).coerceAtMost(200))) })
            Text(
                "After every set, the app times how long your heart rate takes to fall to this number. " +
                    "Training workouts are only compared at the same target. The baseline test has its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Rest until HR ≤ ${settings.recoveryTargetHr}")
                    Text(
                        "Rest lasts the minimum below, or until you're down to ${settings.recoveryTargetHr} " +
                            "(then 5 s to go), whichever is longer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cfg.restUntilTarget, onCheckedChange = { vm.updateConfig(cfg.copy(restUntilTarget = it)) })
            }
            if (cfg.restUntilTarget) {
                Stepper("Minimum rest", mmss(cfg.minRestSec),
                    onMinus = { vm.updateConfig(cfg.copy(minRestSec = (cfg.minRestSec - 15).coerceAtLeast(15))) },
                    onPlus = { vm.updateConfig(cfg.copy(minRestSec = (cfg.minRestSec + 15).coerceAtMost(600))) })
            } else {
                Stepper("Rest", mmss(cfg.restSec),
                    onMinus = { vm.updateConfig(cfg.copy(restSec = (cfg.restSec - 15).coerceAtLeast(15))) },
                    onPlus = { vm.updateConfig(cfg.copy(restSec = (cfg.restSec + 15).coerceAtMost(600))) })
            }
            Stepper("Ball weight", "${cfg.ballWeightLbs} lb",
                onMinus = { vm.updateConfig(cfg.copy(ballWeightLbs = (cfg.ballWeightLbs - 5).coerceAtLeast(5))) },
                onPlus = { vm.updateConfig(cfg.copy(ballWeightLbs = (cfg.ballWeightLbs + 5).coerceAtMost(150))) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Count reps by listening")
                    Text(
                        "Uses the mic to hear each slam. You can still tap +1 / −1.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cfg.autoCount, onCheckedChange = {
                    vm.updateConfig(cfg.copy(autoCount = it))
                    if (it && !perms.mic) onRequestPermissions()
                })
            }
        }

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text("START TRAINING  ·  ${cfg.sets} × ${cfg.targetReps}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (state != State.CONNECTED) {
            Text(
                "Heart rate isn't connected. The workout will still run, but without HR, zones, recovery or calories.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                Text(if (sessions.isEmpty()) "History" else "History (${sessions.size})")
            }
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
        }
        OutlinedButton(
            onClick = onBaseline,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("BASELINE", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
    }
}
