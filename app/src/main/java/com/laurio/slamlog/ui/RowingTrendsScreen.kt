package com.laurio.slamlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laurio.slamlog.data.Session
import com.laurio.slamlog.workout.mmss
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val rowTrendDay = SimpleDateFormat("EEE, MMM d", Locale.US)
private val weekLabel = SimpleDateFormat("MMM d", Locale.US)

/** (by time?, seconds or meters): which workouts are comparable. */
private fun Session.intervalKind(): Pair<Boolean, Int> = if (intervalSec > 0) true to intervalSec else false to distanceM

private fun kindLabel(k: Pair<Boolean, Int>) = if (k.first) mmss(k.second) else "${k.second} m"

/** The recovery target a rowing workout used (each interval records it). */
private fun Session.rowTarget(): Int? = sets.firstOrNull { it.targetHr > 0 }?.targetHr

private fun Session.rowToTarget(): Double? = rowTarget()?.let { avgTimeToTarget(it) }

/** Watts per heartbeat over the workout: more power for the same heart effort means fitter. */
private fun Session.wattsPerBeat(): Double? {
    val w = avgWatts ?: return null
    return if (avgHr > 0) w / avgHr else null
}

private fun Session.bestPace(): Double? = sets.mapNotNull { pace500(it) }.minOrNull()

/**
 * "How is my rowing going?": recovery to target, pace, power per heartbeat and stroke rate over time,
 * the latest workout's first half vs second half, weekly meters and bests. Compared at the same
 * interval distance, since a 250 m and a 1,000 m piece aren't comparable.
 */
@Composable
fun RowingTrendsScreen(sessions: List<Session>, onOpen: (Session) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val rows = sessions.filter { it.isRow && it.sets.isNotEmpty() }.sortedBy { it.startMillis }
    // Workouts compare only with the same kind of interval: same distance, or same time.
    val kinds = rows.map { it.intervalKind() }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    var kind by remember { mutableStateOf(rows.lastOrNull()?.intervalKind() ?: (false to 0)) }
    val group = rows.filter { it.intervalKind() == kind }
    val kindLabel = kindLabel(kind)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ROWING TRENDS", fontSize = 28.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }

        if (rows.isEmpty()) {
            Text("No rowing workouts yet. Trends show up after your first one.")
            return@Column
        }

        if (kinds.size > 1) {
            Label("Compare intervals of")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { k -> Choice(kindLabel(k), k == kind) { kind = k } }
            }
        }
        Text(
            "${group.size} workout${if (group.size == 1) "" else "s"} with $kindLabel intervals. " +
                "Each point is one workout's average.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- Recovery (the headline) ----
        val rec = group.mapNotNull { s -> s.rowToTarget()?.let { s.startMillis to it.toFloat() } }
        SectionCard {
            Label("Interval end → target heart rate")
            if (rec.isEmpty()) {
                Text("No interval has reached the recovery target yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Verdict(rec.map { it.second.toDouble() }, lowerIsBetter = true) { secs(it) }
                TrendChart(rec, Mint, { secs(it.toDouble()) })
                Text("Average time from the end of each interval until your heart rate fell to the target. Lower is better.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- Pace ----
        val pace = group.mapNotNull { s -> s.avgPace500?.let { s.startMillis to it.toFloat() } }
        val best = group.mapNotNull { s -> s.bestPace()?.let { s.startMillis to it.toFloat() } }
        if (pace.isNotEmpty()) SectionCard {
            Label("Pace per 500 m")
            Verdict(pace.map { it.second.toDouble() }, lowerIsBetter = true) { mmss(it.roundToInt()) }
            TrendChart(pace, Orange, { mmss(it.roundToInt()) }, secondary = best, secondaryColor = Sky)
            Legend(listOf(Orange to "average", Sky to "best interval"))
        }

        // ---- Power per heartbeat ----
        val wpb = group.mapNotNull { s -> s.wattsPerBeat()?.let { s.startMillis to it.toFloat() } }
        if (wpb.isNotEmpty()) SectionCard {
            Label("Power per heartbeat")
            Verdict(wpb.map { it.second.toDouble() }, lowerIsBetter = false) { "%.2f W/bpm".format(it) }
            TrendChart(wpb, Sky, { "%.2f".format(it) })
            Text("Average watts ÷ average heart rate. Higher means you make more power for the same effort: " +
                "the clearest sign of aerobic fitness improving.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- Stroke rate ----
        val spm = group.mapNotNull { s -> s.avgSpm?.let { s.startMillis to it.toFloat() } }
        if (spm.isNotEmpty()) SectionCard {
            Label("Stroke rate")
            TrendChart(spm, MaterialTheme.colorScheme.onSurface, { "${it.roundToInt()} spm" })
            val p = group.lastOrNull { it.avgSpm != null && it.avgPace500 != null }
            if (p != null) {
                Text("Latest: ${p.avgSpm!!.roundToInt()} strokes/min at ${mmss(p.avgPace500!!.roundToInt())} /500 m. " +
                    "The same pace at a lower stroke rate means more power per stroke.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- Fade within the latest workout ----
        val latest = group.lastOrNull()
        val target = latest?.rowTarget()
        val h = if (latest != null && target != null) latest.halves(target) else null
        if (latest != null && h != null) SectionCard {
            Label("Latest workout: first half vs second half")
            Text("${rowTrendDay.format(Date(latest.startMillis))} · ${latest.shape}", fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onOpen(latest) })
            HalfTable(h, target!!, "interval")
            Text("Slower intervals or longer recovery in the second half shows where the workout gets hard.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- Weekly meters (all distances) ----
        SectionCard {
            Label("Meters per week")
            val weeks = weeklyMeters(rows, 8)
            val top = (weeks.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            weeks.forEach { (start, m) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(weekLabel.format(Date(start)), Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.weight(1f).height(14.dp)) {
                        if (m > 0) Box(
                            Modifier.fillMaxWidth(m.toFloat() / top).fillMaxHeight()
                                .background(Orange, RoundedCornerShape(4.dp)),
                        )
                    }
                    Text(if (m > 0) "%,d".format(m) else "—", Modifier.width(64.dp).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---- Bests ----
        SectionCard {
            Label("Bests")
            val bestPace = rows.flatMap { s -> s.sets.mapNotNull { set -> s.pace500(set)?.let { Triple(it, s, set) } } }
                .filter { it.second.intervalKind() == kind }.minByOrNull { it.first }
            bestPace?.let { (p, s, _) -> BestLine("Fastest $kindLabel interval", "${mmss(p.roundToInt())} /500 m", s, onOpen) }
            val fastestRec = group.mapNotNull { s -> s.rowToTarget()?.let { it to s } }.minByOrNull { it.first }
            fastestRec?.let { (v, s) -> BestLine("Quickest average recovery", secs(v), s, onOpen) }
            val bestWpb = group.mapNotNull { s -> s.wattsPerBeat()?.let { it to s } }.maxByOrNull { it.first }
            bestWpb?.let { (v, s) -> BestLine("Most power per heartbeat", "%.2f W/bpm".format(v), s, onOpen) }
            val most = rows.maxByOrNull { it.totalMeters }
            most?.let { BestLine("Most meters in a workout", "%,d m".format(it.totalMeters), it, onOpen) }
            Text("Total rowed: %,d m in %d workouts.".format(rows.sumOf { it.totalMeters }, rows.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Latest workout against the average of the up-to-3 before it. */
@Composable
private fun Verdict(values: List<Double>, lowerIsBetter: Boolean, fmt: (Double) -> String) {
    val latest = values.last()
    val prev = values.dropLast(1).takeLast(3)
    if (prev.isEmpty()) {
        Text("Latest ${fmt(latest)}. One more workout and you'll see a comparison.", fontWeight = FontWeight.SemiBold)
        return
    }
    val avg = prev.average()
    val diff = latest - avg
    // Under 2% isn't a real change.
    val same = abs(diff) < abs(avg) * 0.02
    val better = if (lowerIsBetter) diff < 0 else diff > 0
    val word = when {
        same -> "about the same as"
        better -> "better than"
        else -> "worse than"
    }
    Text("Latest ${fmt(latest)}: $word your previous ${prev.size} (${fmt(avg)}).", fontWeight = FontWeight.SemiBold,
        color = when { same -> MaterialTheme.colorScheme.onSurface; better -> Mint; else -> Amber })
}

@Composable
private fun BestLine(label: String, value: String, s: Session, onOpen: (Session) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(rowTrendDay.format(Date(s.startMillis)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontWeight = FontWeight.Bold, color = Orange)
    }
}

/** Meters rowed in each of the last [n] weeks (Monday starts), oldest first. */
private fun weeklyMeters(rows: List<Session>, n: Int): List<Pair<Long, Int>> {
    val cal = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis > System.currentTimeMillis()) cal.add(Calendar.WEEK_OF_YEAR, -1)
    val starts = (n - 1 downTo 0).map { k -> (cal.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, -k) }.timeInMillis }
    val week = 7L * 24 * 3600 * 1000
    return starts.map { st -> st to rows.filter { it.startMillis in st until st + week }.sumOf { it.totalMeters } }
}

/** Seconds as "45 s" under a minute, else m:ss. */
private fun secs(v: Double): String { val r = v.roundToInt(); return if (r < 60) "$r s" else mmss(r) }
