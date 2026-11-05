package com.example.fordfocusdpfscan.data

// ═══════════════════════════════════════════════════════════════════════════════
// RegenEvaluator.kt — Multi-signal DPF regeneration state machine.
//
// Pure (no Android deps) → fully unit-testable (see RegenEvaluatorTest).
//
// A regeneration is declared ACTIVE from two independent signals. SOOT_DROP is the
// authoritative one — diesel soot only ever falls during a regen, so a sustained
// drop is direct proof a cycle is in progress. EGT_TEMP is a secondary confirmation
// and is vetoed when soot is rising (hot exhaust with accumulating soot is just hard
// driving, not a burn):
//
//   1. SOOT_DROP — soot % falling steadily while the engine is warm (authoritative).
//   2. EGT_TEMP  — inlet EGT ≥ 550 °C sustained, UNLESS soot is rising.
//
// REMOVED (v4.20): the old EXOTHERM signal (outlet EGT ≫ inlet). On this car the two
// PID 01 78 EGT sensors are NOT a clean pre/post-DPF pair — sensor 2 simply reads
// ~80 °C hotter at all times — so "outlet ≫ inlet" is a fixed sensor offset, not
// combustion heat. It fired ACTIVE on ordinary warm driving: verified against 65
// on-car captures in which soot climbed 18 %→75 % straight through 15 such "regens"
// without ever falling. It produced almost every false positive, so it was dropped.
//
// Hysteresis on the WARNING band (enter 450 °C, leave only below 420 °C) stops the
// status from flapping when the EGT hovers around 450 °C during normal hot driving
// — that flapping used to fire a heads-up + chime every poll cycle.
// ═══════════════════════════════════════════════════════════════════════════════

class RegenEvaluator {

    companion object {
        /** Below this → cool enough for the cycle to be considered finished. */
        const val EGT_SAFE_THRESHOLD: Float   = 400f
        /** At/above this → enter WARNING. */
        const val EGT_WARNING_ENTER: Float    = 450f
        /** Stay in WARNING until EGT drops below this (hysteresis, prevents flapping). */
        const val EGT_WARNING_EXIT: Float     = 420f
        /** At/above this (sustained) → ACTIVE via inlet temperature. */
        const val EGT_ACTIVE_THRESHOLD: Float = 550f
        /**
         * While ACTIVE, a regen is still burning as long as the DPF OUTLET is at least this
         * hot, even if the inlet dips below [EGT_SAFE_THRESHOLD] (e.g. a pause in driving).
         * Observed on the Mondeo: inlet 392 °C while outlet 587 °C and soot still falling
         * 75 % -> 15 %; without this one regen was recorded as two sessions.
         */
        const val EGT_OUTLET_HOLD_THRESHOLD: Float = 540f

        /** Consecutive hot samples required before EGT_TEMP declares ACTIVE. */
        const val EGT_ACTIVE_CONFIRM_COUNT = 7

        // ── Soot-drop detection ─────────────────────────────────────────────────
        /** Sliding window over which the soot trend is measured. */
        const val SOOT_DROP_WINDOW_MS = 120_000L
        /** The window must span at least this long before a drop is trusted. */
        const val SOOT_DROP_MIN_SPAN_MS = 90_000L
        /** Minimum net soot drop (percentage points) across the window → regen. */
        const val SOOT_DROP_MIN_PCT = 2f
        /** Minimum net soot RISE across the window that flags "hard driving, not a
         *  regen" — used to veto a high-EGT ACTIVE (a real regen burns soot off). */
        const val SOOT_RISE_MIN_PCT = 2f

        // ── "Engine warm" gate for soot-drop (avoids cold-start model noise) ─────
        const val WARM_COOLANT_C = 60f
        const val WARM_EGT_C     = 200f
    }

    /** Running count of consecutive samples above [EGT_ACTIVE_THRESHOLD]. */
    var activeCounter = 0
        private set

    /** Rolling (timestamp, soot%) history used for the soot-drop trend. */
    private val sootHistory = ArrayDeque<Pair<Long, Float>>()

    /**
     * Computes the next [RegenResult] from the latest [sample] and the [current]
     * status. Mutates internal state (counter + soot history) as a side effect.
     */
    fun evaluate(sample: RegenSample, current: RegenStatus): RegenResult {
        recordSoot(sample.timestampMs, sample.soot)

        // ── 1. Active-regen signals ───────────────────────────────────────────
        val warm = sample.coolant >= WARM_COOLANT_C || sample.egtPre >= WARM_EGT_C
        val sootDelta = sootDropInWindow(sample.timestampMs)   // + = soot fell, − = soot rose
        val sootDrop   = warm && sootDelta >= SOOT_DROP_MIN_PCT
        val sootRising = warm && sootDelta <= -SOOT_RISE_MIN_PCT

        // Sustained high inlet EGT confirms a regen — UNLESS soot is clearly rising,
        // in which case the hot exhaust is just hard driving (a real regen burns soot
        // off, it doesn't accumulate it). That veto kills the old false positives.
        val egtHot = if (sample.egtPre >= EGT_ACTIVE_THRESHOLD) {
            activeCounter++
            activeCounter >= EGT_ACTIVE_CONFIRM_COUNT
        } else {
            activeCounter = 0
            false
        }
        val egtHigh = egtHot && !sootRising

        // SOOT_DROP is authoritative (soot only falls during a regen); EGT_TEMP is a
        // secondary confirmation. The old EXOTHERM trigger was removed — see header.
        val trigger = when {
            sootDrop -> RegenStrategy.SOOT_DROP
            egtHigh  -> RegenStrategy.EGT_TEMP
            else     -> null
        }
        if (trigger != null) return RegenResult(RegenStatus.ACTIVE, trigger)

        // ── 2. No active signal → cool-down / WARNING / INACTIVE (hysteresis) ──
        val egt = sample.egtPre
        val wasHot = current == RegenStatus.ACTIVE || current == RegenStatus.WARNING
        return when {
            // Outlet still hot -> the regen is not over, the inlet may just have dipped.
            current == RegenStatus.ACTIVE && sample.egtPost >= EGT_OUTLET_HOLD_THRESHOLD ->
                RegenResult(RegenStatus.ACTIVE, RegenStrategy.EGT_TEMP)

            // Was hot, now cooled below the safe threshold → cycle completed.
            wasHot && egt >= 0f && egt < EGT_SAFE_THRESHOLD ->
                RegenResult(RegenStatus.COMPLETED, RegenStrategy.NONE)

            // Active but still hot (signal dropped) → stay ACTIVE until it cools.
            current == RegenStatus.ACTIVE && egt >= EGT_SAFE_THRESHOLD ->
                RegenResult(RegenStatus.ACTIVE, RegenStrategy.EGT_TEMP)

            // Enter WARNING.
            egt >= EGT_WARNING_ENTER ->
                RegenResult(RegenStatus.WARNING, RegenStrategy.EGT_TEMP)

            // Stay in WARNING within the hysteresis band (420–449 °C).
            current == RegenStatus.WARNING && egt >= EGT_WARNING_EXIT ->
                RegenResult(RegenStatus.WARNING, RegenStrategy.EGT_TEMP)

            // Hold COMPLETED briefly while still cool.
            current == RegenStatus.COMPLETED && egt >= 0f && egt < EGT_SAFE_THRESHOLD ->
                RegenResult(RegenStatus.COMPLETED, RegenStrategy.NONE)

            else ->
                RegenResult(RegenStatus.INACTIVE, RegenStrategy.NONE)
        }
    }

    private fun recordSoot(now: Long, soot: Float) {
        if (soot < 0f) return
        sootHistory.addLast(now to soot)
        val cutoff = now - SOOT_DROP_WINDOW_MS
        while (sootHistory.isNotEmpty() && sootHistory.first().first < cutoff) {
            sootHistory.removeFirst()
        }
    }

    /**
     * Net soot drop (percentage points) across the retained window: positive means
     * soot fell. Returns 0 until the window spans [SOOT_DROP_MIN_SPAN_MS], so a
     * momentary dip can't trigger a regen.
     */
    private fun sootDropInWindow(now: Long): Float {
        if (sootHistory.size < 2) return 0f
        val oldest = sootHistory.first()
        val newest = sootHistory.last()
        if (newest.first - oldest.first < SOOT_DROP_MIN_SPAN_MS) return 0f
        return oldest.second - newest.second
    }

    /** Clears all state (call on disconnect so a new session starts clean). */
    fun reset() {
        activeCounter = 0
        sootHistory.clear()
    }
}

/** One reading fed to [RegenEvaluator]. -1f fields mean "no data". */
data class RegenSample(
    val egtPre: Float,
    val egtPost: Float,
    val soot: Float,
    val coolant: Float,
    val timestampMs: Long
)

/** The evaluator's verdict: the status and which signal produced it. */
data class RegenResult(
    val status: RegenStatus,
    val strategy: RegenStrategy
)
