package com.example.fordfocusdpfscan.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// ═══════════════════════════════════════════════════════════════════════════════
// RegenSession.kt — Room entity for one complete DPF regeneration cycle.
//
// One row = one regen event, from EGT rising above threshold to cooling down.
// Data is captured in three phases:
//   • Pre-regen  — snapshot at the moment regen is detected
//   • Peak       — maximum values observed during the cycle
//   • Post-regen — snapshot once EGT falls back below the safe threshold
// ═══════════════════════════════════════════════════════════════════════════════

@Entity(tableName = "regen_sessions")
data class RegenSession(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ── Timestamps ────────────────────────────────────────────────────────────
    val startTimestamp: Long = 0L,
    val endTimestamp: Long? = null,

    // ── Pre-regen snapshot (captured at regen start) ──────────────────────────
    val preOdometerKm:   Long  = -1L,
    val preSootPct:      Float = -1f,
    val preLoadPct:      Float = -1f,
    val preDeltaPKpa:    Float = -1f,
    val preEgtC:         Float = -1f,
    val preCoolantC:     Float = -1f,

    // ── Peak values observed during regen ─────────────────────────────────────
    val peakEgtC:      Float = 0f,          // inlet (EGT before the DPF)
    val peakEgtOutC:   Float = 0f,          // outlet (EGT after the DPF)
    val peakDeltaPKpa: Float = 0f,

    // ── Post-regen snapshot (captured at regen end) ───────────────────────────
    val postSootPct:    Float? = null,
    val postLoadPct:    Float? = null,
    val postOdometerKm: Long?  = null,
    val postEgtC:       Float? = null,
    val postCoolantC:   Float? = null,

    // ── Summary ───────────────────────────────────────────────────────────────
    /** Total regen duration in minutes. */
    val durationMinutes: Int? = null,

    /** Always "ACTIVE": a session starts only when regen is EGT-confirmed
     *  (EGT ≥ 550 °C sustained). Retained as a column for backward compatibility. */
    val regenType: String = "ACTIVE",

    /** "IN_PROGRESS", "COMPLETED", or "INTERRUPTED" (engine turned off mid-regen). */
    val result: String = "IN_PROGRESS"
)
