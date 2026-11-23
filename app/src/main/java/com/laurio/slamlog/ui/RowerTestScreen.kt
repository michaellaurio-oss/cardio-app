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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laurio.slamlog.workout.WorkoutViewModel

/** One-off check of what the rower's monitor sends over Bluetooth (see RowerProbe). */
@Composable
fun RowerTestScreen(vm: WorkoutViewModel, onBack: () -> Unit) {
    val probe = vm.rower
    val scanning by probe.scanning.collectAsState()
    val found by probe.found.collectAsState()
    val status by probe.status.collectAsState()
    val layout by probe.layout.collectAsState()
    val streams by probe.streams.collectAsState()
    val rower by probe.rower.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // Leave the rower free for other apps once this screen closes.
    DisposableEffect(Unit) { onDispose { probe.stopScan(); probe.disconnect() } }
    BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ROWER TEST", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        SectionCard {
            Text(
                "1. On the rower's monitor choose Connect App (it shows \"Ready for App Connection\" and an ID).\n" +
                    "2. Close the Rogue app if it's open.\n" +
                    "3. Tap Find rower, then Connect next to your rower.\n" +
                    "4. Row a few strokes, then tap Copy report and paste it to Claude.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(status, color = Sky, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scanning) OutlinedButton(onClick = { probe.stopScan() }) { Text("Stop searching") }
                else Button(onClick = { probe.startScan() }) { Text("Find rower") }
                OutlinedButton(onClick = { probe.disconnect() }) { Text("Disconnect") }
            }
        }

        if (found.isNotEmpty()) {
            SectionCard {
                Label("Nearby (fitness machines first)")
                found.take(12).forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name + if (f.ftms) "  · FITNESS MACHINE" else "", fontWeight = FontWeight.SemiBold,
                                color = if (f.ftms) Mint else MaterialTheme.colorScheme.onSurface)
                            Text("signal ${f.rssi}" + if (f.services.isNotEmpty()) " · ${f.services.joinToString()}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { probe.connect(f) }) { Text("Connect") }
                    }
                }
            }
        }

        if (rower.isNotEmpty()) {
            SectionCard {
                Label("Rower data (standard format)")
                rower.forEach { (k, v) ->
                    Row { Text(k, Modifier.weight(1f)); Text(v, fontWeight = FontWeight.Bold, color = Orange) }
                }
            }
        }

        if (layout.isNotEmpty() || streams.isNotEmpty()) {
            SectionCard {
                Label("What the monitor offers")
                layout.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                if (streams.isNotEmpty()) {
                    Label("Updates received")
                    streams.values.forEach {
                        Text("${it.uuid} ×${it.count}: ${it.hex}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
                Button(onClick = { clipboard.setText(AnnotatedString(probe.report())); copied = true },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (copied) "Copied. Paste it to Claude" else "Copy report")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
