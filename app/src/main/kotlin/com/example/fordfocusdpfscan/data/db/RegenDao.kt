package com.example.fordfocusdpfscan.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ═══════════════════════════════════════════════════════════════════════════════
// RegenDao.kt — Room data access object for regen history.
// ═══════════════════════════════════════════════════════════════════════════════

@Dao
interface RegenDao {

    // ── Session CRUD ──────────────────────────────────────────────────────────

    @Insert
    suspend fun insertSession(session: RegenSession): Long

    @Update
    suspend fun updateSession(session: RegenSession)

    /** Returns all sessions newest-first, as a live Flow for the UI. */
    @Query("SELECT * FROM regen_sessions ORDER BY startTimestamp DESC")
    fun getAllSessions(): Flow<List<RegenSession>>

    /** Returns all sessions for export (no live updates needed). */
    @Query("SELECT * FROM regen_sessions ORDER BY startTimestamp DESC")
    suspend fun getAllSessionsOnce(): List<RegenSession>

    /** Finds the most recent IN_PROGRESS session (should be at most one). */
    @Query("SELECT * FROM regen_sessions WHERE result = 'IN_PROGRESS' ORDER BY startTimestamp DESC LIMIT 1")
    suspend fun getActiveSession(): RegenSession?

    /** Odometer at the end of the most recent regen that really burned soot (drop >= 10 points). */
    @Query("SELECT postOdometerKm FROM regen_sessions WHERE result != 'INTERRUPTED' AND result != 'IN_PROGRESS' AND postOdometerKm IS NOT NULL AND postOdometerKm > 0 AND preSootPct >= 0 AND postSootPct IS NOT NULL AND (preSootPct - postSootPct) >= 10 ORDER BY endTimestamp DESC LIMIT 1")
    suspend fun getLastRegenEndOdometer(): Long?

    @Query("SELECT COUNT(*) FROM regen_sessions")
    suspend fun getTotalCount(): Int

    /**
     * Re-labels legacy false-positive INTERRUPTED regens recorded before [cutoff]
     * by the old detector. Those cycles ended naturally (conditions changed / EGT
     * dropped), not because the driver switched the engine off. Returns rows updated.
     */
    @Query("""
        UPDATE regen_sessions
        SET result = 'ENDED_NATURAL'
        WHERE result = 'INTERRUPTED' AND startTimestamp < :cutoff
    """)
    suspend fun relabelLegacyInterrupted(cutoff: Long): Int

    @Query("DELETE FROM regen_sessions")
    suspend fun clearAll()

    // ── Data points ───────────────────────────────────────────────────────────

    @Insert
    suspend fun insertDataPoint(point: RegenDataPoint)

    @Query("SELECT * FROM regen_data_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getDataPointsForSession(sessionId: Long): List<RegenDataPoint>
}
