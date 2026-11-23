package com.laurio.slamlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.laurio.slamlog.workout.Physiology
import com.laurio.slamlog.workout.WorkoutViewModel

/** Heart rate strap status, live bpm, and find / connect / disconnect. Used on the first screen and Slam Ball. */
@Composable
fun HeartRateCard(vm: WorkoutViewModel, onRequestPermissions: () -> Unit) {
    val state by vm.hr.state.collectAsState()
    val bpm by vm.hr.heartRate.collectAsState()
    val devices by vm.hr.devices.collectAsState()
    val name by vm.hr.deviceName.collectAsState()
    val error by vm.hr.error.collectAsState()
    val settings by vm.settings.collectAsState()
    val perms by vm.permissions.collectAsState()
    val bluetoothOn by vm.hr.bluetoothOn.collectAsState()
    SectionCard {
        Label("Heart rate")
        Row(verticalAlignment = Alignment.CenterVertically) {
            val status = when (state) {
                State.CONNECTED -> "Connected · ${name ?: "strap"}"
                State.CONNECTING -> "Connecting to ${name ?: "strap"}…"
                State.WAITING -> "Waiting for ${name ?: "your strap"}. Put it on to connect."
                State.SCANNING -> "Searching… wet the pads and put the strap on."
                State.IDLE -> if (vm.hr.hasSavedStrap) "Not connected" else "No strap paired yet"
            }
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            val current = bpm
            if (state == State.CONNECTED && current != null) {
                val zone = Physiology.zone(current, settings.maxHr)
                Text("$current", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = zoneColor(zone))
                Text(" bpm", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!perms.bluetooth) {
            Text("The app needs the Nearby devices permission to reach your strap.", color = MaterialTheme.colorScheme.error)
            Button(onClick = onRequestPermissions) { Text("Allow permissions") }
        } else if (!bluetoothOn) {
            Text("Bluetooth is off. Turn it on in your phone's quick settings.", color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                State.CONNECTED, State.CONNECTING, State.WAITING ->
                    OutlinedButton(onClick = { vm.hr.disconnect() }) { Text("Disconnect") }
                State.SCANNING ->
                    OutlinedButton(onClick = { vm.hr.stopScan() }) { Text("Stop searching") }
                State.IDLE -> {
                    Button(onClick = { vm.hr.startScan() }) { Text("Find strap") }
                    if (vm.hr.hasSavedStrap) {
                        OutlinedButton(onClick = { vm.hr.autoConnect() }) { Text("Reconnect") }
                    }
                }
            }
        }

        if (state == State.SCANNING || (state == State.IDLE && devices.isNotEmpty())) {
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, Modifier.weight(1f))
                    TextButton(onClick = { vm.hr.connect(d) }) { Text("Connect") }
                }
            }
            if (state == State.SCANNING && devices.isEmpty()) {
                Text("Nothing yet. The H808S wakes up when it touches skin.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
