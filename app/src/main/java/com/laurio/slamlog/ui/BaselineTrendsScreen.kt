package com.laurio.slamlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laurio.slamlog.data.Session
import com.laurio.slamlog.data.UserSettings
import com.laurio.slamlog.workout.BaselineSummary
import com.laurio.slamlog.workout.BaselineTrends
import com.laurio.slamlog.workout.Progress
import com.laurio.slamlog.workout.mmss
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val testDate = SimpleDateFormat("MMM d", Locale.US)
private val Earlier = Color(0xFF8A8F99)
private val Violet = Color(0xFFA78BFA)

/** Milliseconds between pairs when playing, and how long the dots take to glide to the next pair. */
private const val PAIR_MS = 4000L
private const val GLIDE_MS = 900

private fun label(t: BaselineSummary) = testDate.format(Date(t.startMillis)) + if (t.sample) "*" else ""

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/**
 * Baseline tests compared with each other: an animated peak-HR-vs-recovery chart that steps through
 * consecutive pairs of tests, and every metric as "% better than the first test". Opened from Baseline.
 */
@Composable
fun BaselineTrendsScreen(sessions: List<Session>, settings: UserSettings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val target = settings.baselineTargetHr
    val summarized = Progress.baselines(sessions, settings).map { BaselineTrends.summarize(it, target) }
    val real = summarized.filter { it.sets.size >= BaselineTrends.MIN_SETS }
    val skipped = summarized.filter { it.sets.size < BaselineTrends.MIN_SETS }
    var withSamples by remember { mutableStateOf(false) }
    val tests = if (withSamples && real.isNotEmpty()) real + BaselineTrends.samples(real.last()) else real

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BASELINE TRENDS", fontSize = 26.sp, fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (real.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("My tests", !withSamples) { withSamples = false }
                Choice("+ 2 sample tests", withSamples) { withSamples = true }
            }
        }
        if (withSamples) {
            Text(
                "Dates marked * are made-up sample tests, a little better than your latest, to show how the charts " +
                    "look with more tests. They aren't saved.",
                style = MaterialTheme.typography.bodySmall, color = Amber,
            )
        }
        skipped.forEach { t ->
            Text(
                "The test from ${label(t)} isn't charted: only ${plural(t.sets.size, "set")} went above $target " +
                    "and came back down (${BaselineTrends.MIN_SETS} needed).",
                style = MaterialTheme.typography.bodySmall, color = Amber,
            )
        }

        if (tests.size < 2) {
            SectionCard {
                Text(
                    if (tests.isEmpty()) "No baseline tests to compare yet." else "One baseline test so far.",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "These charts compare baseline tests with each other. Run another baseline in a couple of weeks" +
                        if (real.isNotEmpty()) ", or tap \"+ 2 sample tests\" to see how it will look." else ".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SectionCard {
                Label("Test vs. test")
                PairComparison(tests, target)
            }
            SectionCard {
                val ref = tests.take(BaselineTrends.referenceCount(tests.size))
                Label("Better than " + ref.joinToString(" & ") { label(it) } + if (ref.size > 1) " (average)" else "")
                ImprovementChart(tests)
            }
            Text(
                "Sets where heart rate never went above $target are left out: there was nothing to recover from.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A set's dot on the chart: x = peak HR, y = seconds to target, and how visible it is while fading in or out. */
private data class Dot(val pos: Offset, val alpha: Float)

private typealias Line = Pair<Offset, Offset>

private fun dots(t: BaselineSummary): Map<Int, Dot> =
    t.sets.associate { it.number to Dot(Offset(it.peakHr.toFloat(), it.seconds.toFloat()), 1f) }

private fun lerp(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

/**
 * Moves each set's dot from [from] toward [to], matched by set number. A set only one side has fades
 * out where it was, or fades in where it will be.
 */
private fun glide(from: Map<Int, Dot>, to: Map<Int, Dot>, f: Float): Map<Int, Dot> =
    (from.keys + to.keys).associateWith { k ->
        val a = from[k]
        val b = to[k]
        when {
            a != null && b != null -> Dot(lerp(a.pos, b.pos, f), a.alpha + (b.alpha - a.alpha) * f)
            a != null -> Dot(a.pos, a.alpha * (1f - f))
            else -> Dot(b!!.pos, b.alpha * f)
        }
    }.filterValues { it.alpha > 0.001f }

private fun glideLine(a: Line?, b: Line?, f: Float): Line? = when {
    a == null -> b
    b == null -> a
    else -> lerp(a.first, b.first, f) to lerp(a.second, b.second, f)
}

/** Least-squares line through a test's dots, from its lowest to its highest peak; null if it can't be fitted. */
private fun fit(t: BaselineSummary): Line? {
    val pts = t.sets.map { Offset(it.peakHr.toFloat(), it.seconds.toFloat()) }
    if (pts.size < 2) return null
    val mx = pts.map { it.x }.average().toFloat()
    val my = pts.map { it.y }.average().toFloat()
    val sxx = pts.sumOf { ((it.x - mx) * (it.x - mx)).toDouble() }
    if (sxx == 0.0) return null
    val b = (pts.sumOf { ((it.x - mx) * (it.y - my)).toDouble() } / sxx).toFloat()
    val x0 = pts.minOf { it.x }
    val x1 = pts.maxOf { it.x }
    return Offset(x0, my + b * (x0 - mx)) to Offset(x1, my + b * (x1 - mx))
}

private fun secs(v: Float): String { val r = v.roundToInt(); return if (r < 60) "$r s" else mmss(r) }

@Composable
private fun PairComparison(tests: List<BaselineSummary>, target: Int) {
    val pairs = tests.size - 1
    var cur by remember(tests.size) { mutableIntStateOf(0) }
    var playing by remember(tests.size) { mutableStateOf(pairs > 1) }

    // The glide runs from a snapshot of where the dots were (from*) to the pair [heading]. When the pair
    // changes mid-glide, the snapshot is retaken from the dots' current spot, so they never jump.
    val progress = remember(tests.size) { Animatable(1f) }
    var heading by remember(tests.size) { mutableIntStateOf(0) }
    var fromA by remember(tests.size) { mutableStateOf(dots(tests[0])) }
    var fromB by remember(tests.size) { mutableStateOf(dots(tests[1])) }
    var lineFromA by remember(tests.size) { mutableStateOf(fit(tests[0])) }
    var lineFromB by remember(tests.size) { mutableStateOf(fit(tests[1])) }

    val c = cur.coerceIn(0, pairs - 1)
    LaunchedEffect(c, tests.size) {
        if (c == heading && progress.value >= 1f) return@LaunchedEffect
        val f = progress.value
        fromA = glide(fromA, dots(tests[heading]), f)
        fromB = glide(fromB, dots(tests[heading + 1]), f)
        lineFromA = glideLine(lineFromA, fit(tests[heading]), f)
        lineFromB = glideLine(lineFromB, fit(tests[heading + 1]), f)
        heading = c
        progress.snapTo(0f)
        progress.animateTo(1f, tween(GLIDE_MS, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(playing, c, pairs) {
        if (playing && pairs > 1) {
            delay(PAIR_MS)
            cur = (c + 1) % pairs
        }
    }

    val a = tests[c]
    val b = tests[c + 1]
    val h = heading.coerceIn(0, pairs - 1)
    val f = progress.value
    val drawA = glide(fromA, dots(tests[h]), f).values
    val drawB = glide(fromB, dots(tests[h + 1]), f).values
    val lineA = glideLine(lineFromA, fit(tests[h]), f)
    val lineB = glideLine(lineFromB, fit(tests[h + 1]), f)

    // Fixed axes across every test, so dots move only when the numbers do.
    val all = tests.flatMap { t -> t.sets.map { Offset(it.peakHr.toFloat(), it.seconds.toFloat()) } }
    val xLo = all.minOf { it.x } - 3f
    val xHi = all.maxOf { it.x } + 3f
    val yHi = (all.maxOf { it.y } * 1.1f).coerceAtLeast(30f)
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${label(a)}  vs  ${label(b)}", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        if (pairs > 1) {
            OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play") }
        }
    }
    if (pairs > 1) {
        Slider(
            value = c.toFloat(),
            onValueChange = { playing = false; cur = it.roundToInt().coerceIn(0, pairs - 1) },
            valueRange = 0f..(pairs - 1).toFloat(),
            steps = (pairs - 2).coerceAtLeast(0),
            modifier = Modifier.semantics { contentDescription = "Choose which two baseline tests to compare" },
        )
    }

    Row {
        PairStat("avg recovery", a.avgRecovery, b.avgRecovery, { secs(it.toFloat()) }, lowerBetter = true, modifier = Modifier.weight(1f))
        PairStat("recovery rate, bpm/s", a.recoveryRate, b.recoveryRate, { "%.2f".format(Locale.US, it) }, lowerBetter = false, modifier = Modifier.weight(1f))
        PairStat("avg peak HR", a.avgPeak, b.avgPeak, { "${it.roundToInt()}" }, lowerBetter = null, modifier = Modifier.weight(1f))
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PairLegendRow(Earlier, square = false, text = "${label(a)}, earlier (dashed line)")
        PairLegendRow(Sky, square = true, text = "${label(b)}, later (solid line)")
    }

    val chartDescription = "Peak heart rate against seconds to $target for each set. ${label(a)}: average " +
        "${a.avgRecovery?.let { secs(it.toFloat()) } ?: "unknown"}. ${label(b)}: average " +
        "${b.avgRecovery?.let { secs(it.toFloat()) } ?: "unknown"}."
    Row(Modifier.height(240.dp)) {
        Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(secs(yHi), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(secs(yHi / 2), style = MaterialTheme.typography.labelSmall, color = muted)
            Text("0 s", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = chartDescription }) {
            fun x(v: Float) = (v - xLo) / (xHi - xLo) * size.width
            fun y(v: Float) = size.height * (1f - v / yHi)
            fun pt(o: Offset) = Offset(x(o.x), y(o.y))
            listOf(0f, 0.5f, 1f).forEach { k ->
                drawLine(grid, Offset(0f, size.height * k), Offset(size.width, size.height * k))
            }
            val w = 2.dp.toPx()
            lineA?.let { (s, e) ->
                drawLine(Earlier, pt(s), pt(e), strokeWidth = w, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f))
            }
            lineB?.let { (s, e) -> drawLine(Sky, pt(s), pt(e), strokeWidth = w, cap = StrokeCap.Round) }
            val r = 4.5.dp.toPx()
            drawA.forEach { d -> drawCircle(Earlier, r, pt(d.pos), alpha = d.alpha) }
            drawB.forEach { d ->
                val q = pt(d.pos)
                drawRect(Sky, Offset(q.x - r, q.y - r), Size(2 * r, 2 * r), alpha = d.alpha)
            }
        }
    }
    Row(Modifier.padding(start = 44.dp)) {
        Text("${xLo.roundToInt()}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
        Text("${((xLo + xHi) / 2).roundToInt()}", Modifier.weight(1f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = muted)
        Text("${xHi.roundToInt()}", Modifier.weight(1f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelSmall, color = muted)
    }
    Text(
        "Across: peak heart rate during the set (bpm). Up: seconds from last slam to $target. " +
            "Lower dots mean faster recovery from the same effort.",
        style = MaterialTheme.typography.bodySmall, color = muted,
    )
}

@Composable
private fun PairLegendRow(color: Color, square: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).height(10.dp)) {
            val r = size.height / 2
            if (square) drawRect(color, Offset(0f, 0f), Size(2 * r, 2 * r)) else drawCircle(color, r, Offset(r, r))
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** The later test's value, with its change from the earlier one (green when better). */
@Composable
private fun PairStat(
    label: String,
    a: Double?,
    b: Double?,
    fmt: (Double) -> String,
    lowerBetter: Boolean?,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(if (b != null) fmt(b) else "–", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (a != null && b != null && a != 0.0) {
            val pct = ((b - a) / a * 100).roundToInt()
            val color = when {
                pct == 0 || lowerBetter == null -> MaterialTheme.colorScheme.onSurfaceVariant
                (pct < 0) == lowerBetter -> Mint
                else -> MaterialTheme.colorScheme.error
            }
            Text("${if (pct > 0) "+" else ""}$pct% vs ${fmt(a)}", style = MaterialTheme.typography.bodySmall, color = color)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every metric as % better than the reference test(s), all oriented so up means better. */
@Composable
private fun ImprovementChart(tests: List<BaselineSummary>) {
    val lines = BaselineTrends.improvement(tests)
    val colors = listOf(Mint, Sky, Orange, Amber, Violet)
    val values = lines.flatMap { it.values.filterNotNull() }
    if (values.isEmpty()) return
    val hi = (values.max().coerceAtLeast(10.0) * 1.1).toFloat()
    val lo = (values.min().coerceAtMost(0.0) * 1.1).toFloat()
    val n = tests.size
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun pctText(v: Float) = "${if (v > 0.5f) "+" else ""}${v.roundToInt()}%"
    val description = "Improvement since the first test, latest values: " + lines.joinToString("; ") { line ->
        line.label + " " + (line.values.lastOrNull { it != null }?.let { pctText(it.toFloat()) } ?: "unknown")
    }

    Row(Modifier.height(200.dp)) {
        Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(pctText(hi), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(pctText(lo), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }) {
            val inset = 6.dp.toPx()
            fun x(i: Int) = if (n == 1) size.width / 2 else inset + i.toFloat() / (n - 1) * (size.width - 2 * inset)
            fun y(v: Float) = size.height * (1f - (v - lo) / (hi - lo))
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f))
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
            drawLine(muted, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1.dp.toPx())
            lines.forEachIndexed { li, line ->
                val color = colors[li % colors.size]
                val path = Path()
                var started = false
                line.values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val o = Offset(x(i), y(v.toFloat()))
                    if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                line.values.forEachIndexed { i, v ->
                    if (v != null) drawCircle(color, 3.5.dp.toPx(), Offset(x(i), y(v.toFloat())))
                }
            }
        }
    }
    Row(Modifier.padding(start = 44.dp)) {
        Text(label(tests.first()), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
        Text(label(tests.last()), Modifier.weight(1f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelSmall, color = muted)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { li, line ->
            val latest = line.values.lastOrNull { it != null }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(18.dp).height(10.dp)) { drawCircle(colors[li % colors.size], radius = size.height / 2) }
                Text(line.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(latest?.let { pctText(it.toFloat()) } ?: "–", style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
    Text(
        "Up is better on every line: faster recovery, bigger heart rate drop, quicker sets. " +
            "With 4 or more tests, it measures against the average of the first two, so one off day can't skew it.",
        style = MaterialTheme.typography.bodySmall, color = muted,
    )
}
