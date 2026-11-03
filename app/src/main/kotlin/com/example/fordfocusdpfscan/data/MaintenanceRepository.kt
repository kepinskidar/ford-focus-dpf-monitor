package com.example.fordfocusdpfscan.data

import android.content.Context
import com.example.fordfocusdpfscan.data.db.MaintenanceDao
import com.example.fordfocusdpfscan.data.db.MaintenanceReminder
import com.example.fordfocusdpfscan.data.db.RegenDatabase
import kotlinx.coroutines.flow.Flow

// ═══════════════════════════════════════════════════════════════════════════════
// MaintenanceRepository.kt — Manages maintenance reminder lifecycle.
//
// Auto-managed entry (tagliando olio):
//   Created automatically on first connection with ECU data.
//   lastDoneKm is derived from: odometerKm - kmSinceOilChange (PID 22 0542).
//   The user cannot edit/delete it — it tracks the ECU oil-change counter.
// ═══════════════════════════════════════════════════════════════════════════════

class MaintenanceRepository(context: Context) {

    private val dao: MaintenanceDao = RegenDatabase.getInstance(context).maintenanceDao()

    companion object {
        /** Default oil change interval for the Ford Focus 1.5 TDCi. */
        const val OIL_CHANGE_INTERVAL_KM = 15_000L
        const val AUTO_TITLE = "Przegląd olejowy"
    }

    /** Live list for the UI — auto-managed entry is always pinned first. */
    val reminders: Flow<List<MaintenanceReminder>> = dao.getAllFlow()

    /** All reminders as a one-shot list (for notification checks). */
    suspend fun getAll(): List<MaintenanceReminder> = dao.getAll()

    suspend fun insert(reminder: MaintenanceReminder): Long = dao.insert(reminder)

    suspend fun update(reminder: MaintenanceReminder) = dao.update(reminder)

    suspend fun delete(reminder: MaintenanceReminder) = dao.delete(reminder)

    /**
     * Marks a reminder as done at [km] and resets all notification flags.
     * Called when the user taps "Fatto oggi" (with current odometer).
     */
    suspend fun markDone(id: Long, km: Long) = dao.markDone(id, km)

    // ── Notification flag setters ─────────────────────────────────────────────

    suspend fun setNotif1000Sent(id: Long)    = dao.setNotif1000Sent(id)
    suspend fun setNotif500Sent(id: Long)     = dao.setNotif500Sent(id)
    suspend fun setNotifOverdueSent(id: Long) = dao.setNotifOverdueSent(id)

    // ── Auto-managed tagliando ────────────────────────────────────────────────

    /**
     * Called whenever we receive a fresh odometer + kmSinceOilChange from the ECU.
     * Creates the auto-managed entry if it doesn't exist yet, then updates
     * [lastDoneKm] = [odometerKm] - [kmSinceOilChange].
     *
     * If the ECU km counter decreased (oil was changed), the notification flags
     * are reset automatically by markDone().
     */
    suspend fun syncAutoManagedTagliando(odometerKm: Long, kmSinceOilChange: Long) {
        if (odometerKm <= 0L || kmSinceOilChange < 0L) return   // ECU data not ready
        val lastDoneKm = (odometerKm - kmSinceOilChange).coerceAtLeast(0L)

        val existing = dao.getAutoManaged()
        if (existing == null) {
            dao.insert(
                MaintenanceReminder(
                    title         = AUTO_TITLE,
                    intervalKm    = OIL_CHANGE_INTERVAL_KM,
                    lastDoneKm    = lastDoneKm,
                    isAutoManaged = true
                )
            )
        } else if (existing.lastDoneKm != lastDoneKm) {
            // lastDoneKm changed → oil was changed or odometer corrected
            dao.markDone(existing.id, lastDoneKm)
        }
    }
}
