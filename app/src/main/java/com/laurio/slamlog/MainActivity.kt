package com.laurio.slamlog

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.laurio.slamlog.data.Session
import com.laurio.slamlog.ui.AboutYouScreen
import com.laurio.slamlog.ui.BaselineScreen
import com.laurio.slamlog.ui.BaselineSettingsScreen
import com.laurio.slamlog.ui.HistoryScreen
import com.laurio.slamlog.ui.HomeScreen
import com.laurio.slamlog.ui.RowerTestScreen
import com.laurio.slamlog.ui.RowingScreen
import com.laurio.slamlog.ui.RowingTrendsScreen
import com.laurio.slamlog.ui.RunScreen
import com.laurio.slamlog.data.Activity
import com.laurio.slamlog.ui.StartScreen
import com.laurio.slamlog.ui.SettingsScreen
import com.laurio.slamlog.ui.SlamTheme
import com.laurio.slamlog.ui.SummaryScreen
import com.laurio.slamlog.ui.TrendsScreen
import com.laurio.slamlog.ui.WorkoutScreen
import com.laurio.slamlog.workout.Phase
import com.laurio.slamlog.workout.WorkoutViewModel

class MainActivity : ComponentActivity() {
    private val vm: WorkoutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SlamTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm, Modifier.safeDrawingPadding())
                }
            }
        }
    }
}

/** START is the first screen (strap + workout choice); HOME is the Slam Ball screen. */
private enum class Screen { START, HOME, ROWING, RUN, ROWER_TEST, ROW_TRENDS, WORKOUT, SUMMARY, HISTORY, TRENDS, SETTINGS, ABOUT_YOU, BASELINE, BASELINE_SETTINGS }

private fun neededPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    add(Manifest.permission.RECORD_AUDIO)
    // The "workout in progress" notification (Android 13+)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun AppRoot(vm: WorkoutViewModel, modifier: Modifier) {
    var screen by rememberSaveable { mutableStateOf(Screen.START) }
    // Where History, Settings and a workout summary go Back to, and the screen a workout was started from.
    var historyBack by rememberSaveable { mutableStateOf(Screen.START) }
    var settingsBack by rememberSaveable { mutableStateOf(Screen.START) }
    var summaryBack by rememberSaveable { mutableStateOf(Screen.HOME) }
    var workoutFrom by rememberSaveable { mutableStateOf(Screen.HOME) }
    var rowTrendsBack by rememberSaveable { mutableStateOf(Screen.ROWING) }
    // Rowing and Walk/Run share the trends and Bluetooth test screens.
    var machineActivity by rememberSaveable { mutableStateOf(Activity.ROW) }
    // Where Trends' Back goes (Home, or Baseline when opened from there).
    var trendsBack by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Where About you's Back goes (it's only opened from the first screen now).
    var aboutBack by rememberSaveable { mutableStateOf(Screen.START) }
    var shown by remember { mutableStateOf<Session?>(null) }
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionsResult()
    }
    val requestPermissions = { launcher.launch(neededPermissions()) }

    LaunchedEffect(Unit) {
        vm.refreshPermissions()
        if (!vm.hasBluetoothPermission() || !vm.hasMicPermission() || !vm.hasNotificationPermission()) requestPermissions()
    }

    // Permissions can be changed in system settings while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refreshPermissions() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Move between screens as the workout progresses.
    LaunchedEffect(ui.phase) {
        when (ui.phase) {
            Phase.DONE -> {
                shown = vm.lastSession
                summaryBack = workoutFrom
                screen = if (shown != null) Screen.SUMMARY else workoutFrom
                vm.acknowledgeDone()
            }
            Phase.IDLE -> if (screen == Screen.WORKOUT) screen = workoutFrom
            else -> if (screen != Screen.WORKOUT) screen = Screen.WORKOUT
        }
    }

    androidx.compose.foundation.layout.Box(modifier) {
        when (screen) {
            Screen.START -> StartScreen(
                vm = vm,
                onRequestPermissions = requestPermissions,
                onSlamBall = { screen = Screen.HOME },
                onRowing = { screen = Screen.ROWING },
                onRun = { screen = Screen.RUN },
                onAboutYou = { aboutBack = Screen.START; screen = Screen.ABOUT_YOU },
            )
            Screen.HOME -> HomeScreen(
                vm = vm,
                onRequestPermissions = requestPermissions,
                onStart = { workoutFrom = Screen.HOME; vm.startWorkout(); screen = Screen.WORKOUT },
                onBaseline = { screen = Screen.BASELINE },
                onHistory = { historyBack = Screen.HOME; screen = Screen.HISTORY },
                onTrends = { trendsBack = Screen.HOME; screen = Screen.TRENDS },
                onSettings = { settingsBack = Screen.HOME; screen = Screen.SETTINGS },
                onBack = { screen = Screen.START },
            )
            Screen.ROWING -> RowingScreen(
                vm = vm,
                onStart = { workoutFrom = Screen.ROWING; vm.startRowing(); screen = Screen.WORKOUT },
                onOpen = { shown = it; summaryBack = Screen.ROWING; screen = Screen.SUMMARY },
                onHistory = { historyBack = Screen.ROWING; screen = Screen.HISTORY },
                onRowerTest = { machineActivity = Activity.ROW; screen = Screen.ROWER_TEST },
                onTrends = { machineActivity = Activity.ROW; rowTrendsBack = Screen.ROWING; screen = Screen.ROW_TRENDS },
                onBack = { screen = Screen.START },
            )
            Screen.RUN -> RunScreen(
                vm = vm,
                onStart = { workoutFrom = Screen.RUN; vm.startRun(); screen = Screen.WORKOUT },
                onOpen = { shown = it; summaryBack = Screen.RUN; screen = Screen.SUMMARY },
                onHistory = { historyBack = Screen.RUN; screen = Screen.HISTORY },
                onTrends = { machineActivity = Activity.RUN; rowTrendsBack = Screen.RUN; screen = Screen.ROW_TRENDS },
                onBack = { screen = Screen.START },
            )
            Screen.ROWER_TEST -> {
                val tread = machineActivity == Activity.RUN
                RowerTestScreen(vm, onBack = { screen = if (tread) Screen.RUN else Screen.ROWING }, treadmill = tread)
            }
            Screen.ROW_TRENDS -> RowingTrendsScreen(
                sessions = sessions,
                onOpen = { shown = it; summaryBack = Screen.ROW_TRENDS; screen = Screen.SUMMARY },
                onBack = { screen = rowTrendsBack },
                activity = machineActivity,
            )
            Screen.WORKOUT -> WorkoutScreen(vm)
            Screen.SUMMARY -> {
                val s = shown
                if (s == null) {
                    LaunchedEffect(Unit) { screen = Screen.START }
                } else {
                    SummaryScreen(
                        session = s,
                        maxHr = settings.maxHr,
                        onRate = { v -> shown = vm.rateSession(s, v) },
                        onToggleBaseline = { v -> shown = vm.setBaseline(s, v) },
                        onToggleLock = { v -> shown = vm.setLocked(s, v) },
                        onDelete = { if (vm.deleteSession(s.id)) { shown = null; screen = summaryBack } },
                        onDone = { shown = null; screen = summaryBack },
                    )
                }
            }
            Screen.HISTORY -> {
                // Each workout has its own history, picked by the screen History was opened from.
                val act = when (historyBack) { Screen.ROWING -> Activity.ROW; Screen.RUN -> Activity.RUN; else -> Activity.SLAM }
                val list = sessions.filter { it.activity == act }
                HistoryScreen(
                    sessions = list,
                    onOpen = { shown = it; summaryBack = Screen.HISTORY; screen = Screen.SUMMARY },
                    onTrends = if (act.isMachine) ({ machineActivity = act; rowTrendsBack = Screen.HISTORY; screen = Screen.ROW_TRENDS })
                    else ({ trendsBack = Screen.HISTORY; screen = Screen.TRENDS }),
                    onExport = { vm.exportCsvFile(list) },
                    title = when (act) { Activity.ROW -> "ROWING HISTORY"; Activity.RUN -> "WALK/RUN HISTORY"; Activity.SLAM -> "SLAM BALL HISTORY" },
                    onBack = { screen = historyBack },
                )
            }
            Screen.TRENDS -> TrendsScreen(
                // Slam ball trends only; rowing and walk/run have their own trends screen.
                sessions = sessions.filter { it.activity == Activity.SLAM },
                settings = settings,
                target = settings.recoveryTargetHr,
                onOpen = { shown = it; summaryBack = Screen.TRENDS; screen = Screen.SUMMARY },
                onBack = { screen = trendsBack },
            )
            Screen.SETTINGS -> SettingsScreen(
                vm,
                onRequestPermissions = requestPermissions,
                onBack = { screen = settingsBack },
            )
            Screen.ABOUT_YOU -> AboutYouScreen(vm, onBack = { screen = aboutBack })
            Screen.BASELINE -> BaselineScreen(
                vm,
                onStartBaseline = { workoutFrom = Screen.HOME; vm.startBaseline(); screen = Screen.WORKOUT },
                onTrends = { trendsBack = Screen.BASELINE; screen = Screen.TRENDS },
                onBaselineSettings = { screen = Screen.BASELINE_SETTINGS },
                onBack = { screen = Screen.HOME },
            )
            Screen.BASELINE_SETTINGS -> BaselineSettingsScreen(vm, onBack = { screen = Screen.BASELINE })
        }
    }
}
