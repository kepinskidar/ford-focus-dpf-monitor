package com.example.fordfocusdpfscan.ble

import android.content.Context
import android.util.Log
import com.example.fordfocusdpfscan.data.DpfRepository
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// RegenStateCapture.kt — Snapshots the ECU's UDS 22 05xx range during a regen.
//
// Why:  On this EDC17C70 the named "regen status" PIDs (22 117C / 1922 / 1920)
//       return 7F (not supported). The real regen-state flag almost certainly
//       lives somewhere in the responding 22 05xx range — a value that reads 0
//       when idle and flips to 1/2/3 while regenerating. To find it we need a
//       snapshot of that range taken WHILE a regen is active, to diff against the
//       idle baseline. This class does exactly that, triggered automatically by
//       DpfForegroundService when the regen detector fires ACTIVE / COMPLETED.
//
// Output: appended as a delimited block to filesDir/regen_captures.txt, which the
//         user shares from the ECU Scanner screen ("Condividi cattura regen").
//
// Cost:   pauses live polling for the duration of the sweep (~25-35 s). Acceptable
//         because a regen lasts ~10-15 min and we capture at most once per phase.
// ═══════════════════════════════════════════════════════════════════════════════

class RegenStateCapture(
    private val context: Context,
    private val ble: BleManager
) {

    companion object {
        private const val TAG = "FOCUS_Capture"
        const val FILE_NAME = "regen_captures.txt"
        private const val TIMEOUT_MS = 200L
        private const val INTER_CMD_DELAY_MS = 60L
    }

    /**
     * Sweeps 22 0500..05FF (plus the DDxx counters), keeping only positive replies,
     * and appends a timestamped block with a live-data context header.
     * @return number of PIDs that responded positively.
     */
    suspend fun captureNow(label: String): Int {
        ble.pausePolling()
        delay(300)

        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("pl", "PL")).format(Date())
        val d = DpfRepository.dpfData.value

        sb.appendLine("═══════════════════════════════════════════════════")
        sb.appendLine("CATTURA — $label")
        sb.appendLine("Data: $ts")
        sb.appendLine(
            "Contesto: regen=${d.regenStatus} via=${d.regenStrategy}" +
                " soot=${fmt(d.sootPercentage)}%" +
                " load=${fmt(d.loadPercentage)}%" +
                " EGTin=${fmt(d.egtCelsius)}°C" +
                " EGTout=${fmt(d.egtPostDpfC)}°C" +
                " dP=${fmt(d.dpfDeltaPressureKpa)}kPa" +
                " coolant=${fmt(d.coolantTempC)}°C" +
                " rpm=${fmt(d.rpmValue)}" +
                " rail=${fmt(d.fuelRailPressureKpa)}kPa" +
                " kmToRegen=${d.kmSinceLastRegen}"
        )
        sb.appendLine("── 22 05xx (solo risposte positive) ──")

        var count = 0
        try {
            count += sweep(0x0500, 0x05FF, sb)
            sb.appendLine("── 22 DDxx (contatori/odometro) ──")
            count += sweep(0xDD00, 0xDD0F, sb)
        } catch (e: Exception) {
            Log.w(TAG, "capture interrupted: ${e.message}")
        } finally {
            ble.resumePolling()
        }

        sb.appendLine("(PID con risposta: $count)")
        sb.appendLine()

        try {
            File(context.filesDir, FILE_NAME).appendText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "write error: ${e.message}")
        }

        Log.d(TAG, "Capture '$label' done: $count PIDs responded")
        return count
    }

    /** Queries every UDS 22 identifier in [start]..[end], logging positive replies. */
    private suspend fun sweep(start: Int, end: Int, sb: StringBuilder): Int {
        var count = 0
        for (id in start..end) {
            val hi = (id shr 8) and 0xFF
            val lo = id and 0xFF
            val cmd = "22%02X%02X".format(hi, lo)
            val resp = ble.sendRawCommand(cmd, TIMEOUT_MS)
            // A positive UDS reply echoes "62 <hi> <lo> ..."; negatives are "7F 22 31".
            val bytes = if (resp != null) ble.hexStringToBytes(resp) else null
            val positive = bytes != null && bytes.size >= 3 &&
                (bytes[0].toInt() and 0xFF) == 0x62 &&
                (bytes[1].toInt() and 0xFF) == hi &&
                (bytes[2].toInt() and 0xFF) == lo
            if (positive) {
                sb.appendLine("22 %02X%02X -> %s".format(hi, lo, resp!!.trim()))
                count++
            }
            delay(INTER_CMD_DELAY_MS)
        }
        return count
    }

    private fun fmt(v: Float): String = if (v < 0f) "—" else "%.1f".format(v)
}
