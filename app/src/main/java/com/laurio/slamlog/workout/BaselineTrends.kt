package com.laurio.slamlog.workout

import com.laurio.slamlog.data.Session
import kotlin.math.roundToInt

/** One set of a baseline test that counts toward recovery: HR went above the target and came back down to it. */
data class RecoverySet(
    val number: Int,
    val peakHr: Int,
    /** Seconds from the last slam until HR fell to the target. */
    val seconds: Int,
    /** Heart rate drop over the first minute after the set, bpm; null if unknown or measured over less than a minute. */
    val dropBpm: Int?,
)

/** A baseline test boiled down for the Baseline Trends screen. */
data class BaselineSummary(
    val startMillis: Long,
    val target: Int,
    /** Only sets whose peak went above the target and that got back down to it. */
    val sets: List<RecoverySet>,
    /** Average seconds per finished set (every finished set, not only the ones in [sets]). */
    val avgSetSec: Double?,
    val plannedSets: Int,
    /** Made-up test, shown only when sample tests are turned on. */
    val sample: Boolean = false,
) {
    val avgRecovery: Double? get() = sets.map { it.seconds }.takeIf { it.isNotEmpty() }?.average()

    /** Average recovery over the second half of the sets (sets 6–10 of a 10-set test). */
    val lateRecovery: Double?
        get() = sets.filter { it.number > plannedSets / 2 }.map { it.seconds }.takeIf { it.isNotEmpty() }?.average()

    val avgDrop: Double? get() = sets.mapNotNull { it.dropBpm }.takeIf { it.isNotEmpty() }?.average()

    val avgPeak: Double? get() = sets.map { it.peakHr }.takeIf { it.isNotEmpty() }?.average()

    /**
     * Recovery rate, bpm per second: how far HR had to fall (peak − target) divided by how long it took,
     * over all counted sets. Adjusts for effort: a harder set has farther to fall.
     */
    val recoveryRate: Double?
        get() {
            val secs = sets.sumOf { it.seconds }
            if (secs <= 0) return null
            return sets.sumOf { (it.peakHr - target).toDouble() } / secs
        }
}

/** One line of the improvement chart: % better than the reference, oriented so up is always better. */
data class ImprovementLine(val label: String, val values: List<Double?>)

object BaselineTrends {
    /** A test needs at least this many counted sets to be charted. */
    const val MIN_SETS = 2

    /**
     * Sets count only when HR went above the target and came back down. A set that never got above it
     * (often set 1 after a long rest) has nothing to recover from, so its 0 s and negative drop are left out.
     * The heart rate drop counts only when it was measured over a full minute (0 = not recorded, older workouts).
     */
    fun summarize(s: Session, target: Int): BaselineSummary {
        val counted = s.setsWithTarget(target)
            .filter { it.peakHr > target && it.timeToTargetSec != null }
            .map { set ->
                RecoverySet(
                    number = set.number,
                    peakHr = set.peakHr,
                    seconds = set.timeToTargetSec!!,
                    dropBpm = set.recoveryBpm?.takeIf { set.recoverySec == 0 || set.recoverySec >= 60 },
                )
            }
        return BaselineSummary(
            startMillis = s.startMillis,
            target = target,
            sets = counted,
            avgSetSec = s.avgSetSec,
            plannedSets = if (s.plannedSets > 0) s.plannedSets else s.sets.size,
        )
    }

    /**
     * How many of the first tests the improvement chart measures against: the first one, or the average of
     * the first two once there are 4 or more tests, so one off day at the start can't skew every line
     * (the same rule as [Progress.verdict]).
     */
    fun referenceCount(tests: Int) = if (tests >= 4) 2 else 1

    fun improvement(tests: List<BaselineSummary>): List<ImprovementLine> {
        if (tests.isEmpty()) return emptyList()
        val ref = tests.take(referenceCount(tests.size))
        fun line(label: String, higherBetter: Boolean, sel: (BaselineSummary) -> Double?): ImprovementLine {
            val a = ref.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()
            return ImprovementLine(label, tests.map { t ->
                val v = sel(t)
                if (a == null || v == null || a == 0.0) null
                else if (higherBetter) (v - a) / a * 100 else (a - v) / a * 100
            })
        }
        return listOf(
            line("Recovery rate", true) { it.recoveryRate },
            line("1-min HR drop", true) { it.avgDrop },
            line("Avg recovery time", false) { it.avgRecovery },
            line("Late-set recovery", false) { it.lateRecovery },
            line("Set time", false) { it.avgSetSec },
        )
    }

    /**
     * Two made-up tests, 4 and 8 weeks after the last real one, a little better each time, so the
     * screen can be tried before there are enough real tests. Never saved.
     */
    fun samples(last: BaselineSummary): List<BaselineSummary> = listOf(1 to 0.82, 2 to 0.70).map { (n, k) ->
        last.copy(
            startMillis = last.startMillis + n * 28L * 86_400_000L,
            sets = last.sets.map {
                it.copy(
                    peakHr = it.peakHr - n,
                    seconds = (it.seconds * k).roundToInt().coerceAtLeast(5),
                    dropBpm = it.dropBpm?.plus(n),
                )
            },
            avgSetSec = last.avgSetSec?.let { it * (1 - 0.015 * n) },
            sample = true,
        )
    }
}
