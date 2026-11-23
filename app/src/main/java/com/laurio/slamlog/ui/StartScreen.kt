package com.laurio.slamlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laurio.slamlog.workout.WorkoutViewModel

/** First screen: connect the heart rate strap, then pick the workout. */
@Composable
fun StartScreen(
    vm: WorkoutViewModel,
    onRequestPermissions: () -> Unit,
    onSlamBall: () -> Unit,
    onRowing: () -> Unit,
    onAboutYou: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("CARDIO LOG", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)

        if (!settings.hasBirthday) {
            SectionCard {
                Text("Add your birthday", fontWeight = FontWeight.Bold)
                Text(
                    "Heart rate zones and max heart rate are based on your age. Until you add your birthday, " +
                        "they assume you're ${settings.age}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAboutYou) { Text("Add birthday") }
            }
        }

        HeartRateCard(vm, onRequestPermissions)

        Label("Choose a workout")
        WorkoutChoice("SLAM BALL", "Sets of slams, counted by the mic", Orange, onSlamBall)
        WorkoutChoice("ROWING MACHINE", "Intervals with rest until your heart rate recovers", Sky, onRowing)

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WorkoutChoice(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(88.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.Black)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Black.copy(alpha = 0.75f))
        }
    }
}
