package com.example.fordfocusdpfscan.ble

import android.util.Log
import com.example.fordfocusdpfscan.data.EcuScanRepository
import com.example.fordfocusdpfscan.data.PidResult
import com.example.fordfocusdpfscan.data.PidStatus
import com.example.fordfocusdpfscan.data.ScanPhase
import kotlinx.coroutines.*

// ═══════════════════════════════════════════════════════════════════════════════
// EcuPidScanner.kt — Comprehensive ECU PID discovery engine.
//
// Uses ELM327 ASCII protocol (Vgate iCar Pro compatible).
// Commands are sent as strings (e.g. "0105", "221149") via BleManager.sendRawCommand().
// Responses are parsed from ELM327 ASCII output (e.g. "41 05 6B").
//
// Phases (v2.0 — optimised):
//   1 — Priority Mode 01 + Ford UDS (confirmed + likely PIDs, 600 ms timeout)
//   2 — Mode 01 full sweep 00–FF (200 ms timeout)
//   3 — Mode 09 vehicle info (VIN, calibration ID, ECU name)
//   4 — UDS 22 11xx only (Ford Focus engine/DPF range, 256 PIDs)
//   5 — UDS 22 40xx–41xx (TPMS / BCM candidates)
//   6 — UDS 22 DDxx (odometer area)
//   7 — UDS 22 F1xx (standard UDS identifiers)
//
// Removed phases (all returned 7F 22 31 / NO DATA on this ECU):
//   MULTI_HEADER — same result on all 10 CAN headers + factory default
//   UDS_19xx     — 256 PIDs, all requestOutOfRange
//   UDS_20xx     — 256 PIDs, all requestOutOfRange
//   UDS_10xx/12xx/13xx — only 11xx sub-range has real responses
// ═══════════════════════════════════════════════════════════════════════════════

class EcuPidScanner(private val bleManager: BleManager) {

    companion object {
        private const val TAG = "FOCUS_Scanner"

        private const val TIMEOUT_PRIORITY_MS = 600L
        private const val TIMEOUT_SWEEP_MS    = 200L
        private const val INTER_CMD_DELAY_MS  = 90L   // margin for late ELM327 replies; sendRawCommand also drains stale ones

        // ── Human-readable labels for known PIDs ──────────────────────────────
        // Keys are in display format: "01 05", "22 1149", "09 02"
        val KNOWN_LABELS = mapOf(
            // ── Standard OBD2 Mode 01 ─────────────────────────────────────────
            "01 00" to "Supported PIDs 01-20 [bitmask]",
            "01 01" to "Monitor status",
            "01 05" to "★ Engine Coolant Temp",
            "01 06" to "Short-term fuel trim B1",
            "01 07" to "Long-term fuel trim B1",
            "01 0A" to "Fuel pressure",
            "01 0B" to "Intake manifold pressure",
            "01 0C" to "★ Engine RPM",
            "01 0D" to "★ Vehicle Speed",
            "01 0E" to "Timing advance",
            "01 0F" to "★ Intake Air Temp",
            "01 10" to "MAF air flow rate",
            "01 11" to "★ Throttle position",
            "01 14" to "O2 Sensor B1S1",
            "01 15" to "O2 Sensor B1S2",
            "01 1C" to "OBD standard",
            "01 1F" to "Engine run time",
            "01 20" to "Supported PIDs 21-40 [bitmask]",
            "01 21" to "Distance with MIL on",
            "01 22" to "Fuel rail pressure (relative)",
            "01 23" to "Fuel rail gauge pressure",
            "01 2C" to "EGR commanded",
            "01 2D" to "EGR error",
            "01 2E" to "Evaporative purge",
            "01 2F" to "★ Fuel tank level",
            "01 30" to "Warm-ups since codes cleared",
            "01 31" to "Distance since codes cleared",
            "01 33" to "★ Absolute barometric pressure",
            "01 3C" to "Catalyst temp B1S1",
            "01 40" to "Supported PIDs 41-60 [bitmask]",
            "01 42" to "★ Control module voltage",
            "01 44" to "Fuel/air equivalence ratio",
            "01 45" to "Relative throttle position",
            "01 46" to "★ Ambient air temp",
            "01 47" to "Absolute throttle B",
            "01 49" to "Accelerator pedal pos D",
            "01 4A" to "Accelerator pedal pos E",
            "01 4C" to "Commanded throttle actuator",
            "01 4D" to "Time run with MIL on",
            "01 4E" to "Time since codes cleared",
            "01 51" to "Fuel type",
            "01 52" to "Ethanol fuel percentage",
            "01 59" to "Fuel rail absolute pressure",
            "01 5A" to "Relative accelerator pedal",
            "01 5B" to "Hybrid battery pack life",
            "01 5C" to "★ Engine oil temp",
            "01 5D" to "Fuel injection timing",
            "01 5E" to "★ Engine fuel rate",
            "01 60" to "Supported PIDs 61-80 [bitmask]",
            "01 61" to "Driver demand engine torque",
            "01 62" to "★ Actual engine torque",
            "01 63" to "Engine reference torque",
            "01 64" to "Engine percent torque data",
            "01 67" to "Engine coolant temp (ext)",
            "01 68" to "Intake air temp sensor",
            "01 6B" to "EGR temperature",
            "01 6C" to "EGR + commanded EGR",
            "01 6D" to "Fuel injection timing (ext)",
            "01 6E" to "Engine fuel rate (ext)",
            "01 73" to "Exhaust pressure",
            "01 74" to "Turbo compressor inlet pressure",
            "01 75" to "Boost pressure control",
            "01 3C" to "★★ Catalyst temp B1S1 (DPF inlet proxy)",
            "01 3E" to "★★ Catalyst temp B2S1",
            "01 78" to "★★★ EGT Sensors Bank1 [CONFIRMED 100.4°C@idle]",
            "01 79" to "EGT Sensors Bank2",
            "01 7A" to "★★★ DPF pressure/status [CONFIRMED responds]",
            "01 7B" to "DPF pressure",
            "01 7C" to "★★★ DPF Temperature",
            "01 7D" to "★★★ DPF Delta Pressure",
            "01 7E" to "★ EGR + EGR Error",
            "01 7F" to "Throttle actuator control",
            "01 80" to "Supported PIDs 81-A0 [bitmask]",
            "01 83" to "NOx NTE control area status",
            "01 84" to "PM NTE control area status",
            "01 85" to "Engine run time for AECD",
            "01 8E" to "Engine friction percent torque",
            "01 A6" to "★ Odometer",
            // ── Mode 09 Vehicle Info ──────────────────────────────────────────
            "09 02" to "★ VIN (Vehicle ID Number)",
            "09 04" to "Calibration ID",
            "09 06" to "Calibration verification number",
            "09 08" to "In-use performance tracking",
            "09 0A" to "ECU name",
            "09 0C" to "ESN (Electronic Serial Number)",
            // ── Ford confirmed PIDs ───────────────────────────────────────────
            "22 1149" to "★★★ DPF Soot [CONFIRMED]",
            "22 11A8" to "★★★ DPF Load [CONFIRMED]",
            "22 DD00" to "Odometer (raw 4-byte — see DD01)",
            "22 DD01" to "★★★ Odometer Ford [CONFIRMED] (3-byte km)",
            "22 DD02" to "★★ Dystans ostatniego cyklu / licznik (1 bajt)",
            "22 DD04" to "Odometer area — byte flag",
            "22 DD05" to "★ Licznik malejący (możliwy odlicznik DPF)",
            "22 DD06" to "Odometer area — byte flag",
            // ── Standard OBD2 DPF PIDs (confirmed on this ECU) ──────────────
            "01 7A" to "★★★ DPF ΔP / pressione differenziale [CONFERMATO]",
            // ── Ford 2.0 TDCi DPF analogues — test on 1.5 TDCi ──────────────
            "22 0579" to "★★★ DPF Load % (Ford 2.0 TDCi — testowane na 1.5)",
            "22 057B" to "★★★ DPF Soot g (Ford 2.0 TDCi — testowane na 1.5)",
            // ── Community Ford diesel DPF PIDs ───────────────────────────────
            "22 09E2" to "★★ DPF differential pressure kPa (community PID)",
            "22 FD87" to "★★ DPF nieudane regeneracje (licznik)",
            "22 FD89" to "★★ Średni dystans między regeneracjami (km)",
            "22 FD8A" to "★★ Ostatnia regeneracja — przejechane km",
            // ── EDC17C70 specific — Ford Focus TDCI ──────────────────────────
            "22 1175" to "★★★ EGT sensor (Ford TDCI)",
            "22 117C" to "★★★ DPF regen flag (Ford TDCI)",
            "22 1920" to "★★ DPF soot estimate (EDC17)",
            "22 1921" to "★★ DPF soot limit (EDC17)",
            "22 1922" to "★★ DPF state / regen status (EDC17)",
            "22 1810" to "★★ EGT upstream DPF (EDC17)",
            "22 1811" to "★★ EGT downstream DPF (EDC17)",
            "22 2048" to "★★ DPF soot mass [g] (Bosch EDC17)",
            "22 2049" to "★★ DPF soot mass max [g] (Bosch EDC17)",
            "22 204A" to "★★ DPF ash mass [g] (Bosch EDC17)",
            "22 204B" to "★★ DPF load % (Bosch EDC17)",
        )
    }

    private var scanJob: Job? = null
    private var isCancelled = false

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    fun startScan(scope: CoroutineScope) {
        if (scanJob?.isActive == true) return
        isCancelled = false
        EcuScanRepository.reset()

        scanJob = scope.launch(Dispatchers.IO) {
            bleManager.pausePolling()
            delay(300)
            try {
                runScan()
            } finally {
                bleManager.resumePolling()
                EcuScanRepository.setCompleted()
                Log.d(TAG, "Scan finished. Resuming normal polling.")
            }
        }
    }

    fun cancelScan() {
        isCancelled = true
        scanJob?.cancel()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Scan phases
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun runScan() {
        // ── Phase 1 — Priority PIDs (600 ms timeout) ──────────────────────────
        val phase1 = listOf(
            "0105", "010C", "010D", "010F", "0111", "012F", "0133",
            "0142", "0146", "015C", "015E", "0162",
            "013C", "013E",         // Catalyst temps (DPF inlet proxy) — CONFIRMED
            "0178", "017A", "0179", // EGT + DPF pressure — CONFIRMED responding
            "017B", "017C", "017D", "017E", "01A6",
            // DPF ΔP confirmed: 017A → 0.00 kPa at idle ✓ (filter clean)
            // 017C: DPF temperature — NO DATA on this ECU but re-tested in priority
            // Ford confirmed (DD01 = 153 961 km — 3-byte odometer)
            "221149", "2211A8", "22DD01", "22DD00", "22DD02", "22DD05",
            // EGT + regen (Ford TDCI known addresses)
            "221175", "22117C",
            // Bosch EDC17C70 DPF area (scan confirms ECU is EDC17C70)
            "221920", "221921", "221922",  // DPF soot estimate / limit / state
            "221810", "221811",            // EGT upstream / downstream DPF
            "222048", "222049", "22204A", "22204B"  // Bosch EDC17 DPF soot/ash/load
        )
        runPhase(ScanPhase.PRIORITY, phase1, TIMEOUT_PRIORITY_MS)
        if (isCancelled) return

        // ── Phase 2 — Mode 01 full sweep 00–FF (200 ms timeout) ───────────────
        val mode01 = (0x00..0xFF).map { "01%02X".format(it) }
        runPhase(ScanPhase.MODE01_SWEEP, mode01, TIMEOUT_SWEEP_MS)
        if (isCancelled) return

        // ── Phase 3 — Mode 09 vehicle info ────────────────────────────────────
        val mode09 = (0x00..0x0F).map { "09%02X".format(it) }
        runPhase(ScanPhase.MODE09_INFO, mode09, TIMEOUT_PRIORITY_MS)
        if (isCancelled) return

        // ── Phase 4 — UDS 22 05xx (Ford DPF Load/Soot analogue range) ──────────
        // On the 2.0 TDCi, PIDs 0579 (DPF Load %) and 057B (DPF Soot g) exist.
        // Not confirmed on 1.5 TDCi yet — this sweep will find any 05xx responses.
        val uds05 = buildUdsRange(0x05, 0x00, 0x05, 0xFF)
        runPhase(ScanPhase.UDS_05xx, uds05, TIMEOUT_SWEEP_MS)
        if (isCancelled) return

        // ── Phase 5 — UDS 22 09xx (community DPF pressure PID area) ──────────
        // Community PID 09E2 reported for DPF differential pressure (kPa) on
        // some Ford diesels. Scanning last 48 bytes of 09xx only.
        val uds09 = buildUdsRange(0x09, 0xD0, 0x09, 0xFF)
        runPhase(ScanPhase.UDS_09xx, uds09, TIMEOUT_SWEEP_MS)
        if (isCancelled) return

        // ── Phase 6 — UDS 22 11xx only (Ford Focus engine/DPF — 256 PIDs) ─────
        // Previous full 10xx–13xx sweep (1024 PIDs) showed 10xx/12xx/13xx are all
        // requestOutOfRange on this ECU. Only 11xx has confirmed live responses.
        val uds11 = buildUdsRange(0x11, 0x00, 0x11, 0xFF)
        runPhase(ScanPhase.UDS_10xx, uds11, TIMEOUT_SWEEP_MS)
        if (isCancelled) return

        // ── Phase 7 — UDS 22 40xx–41xx (TPMS / BCM) ──────────────────────────
        val uds40 = buildUdsRange(0x40, 0x00, 0x41, 0xFF)
        runPhase(ScanPhase.UDS_40xx, uds40, TIMEOUT_SWEEP_MS)
        if (isCancelled) return

        // ── Phase 8 — UDS 22 DDxx full range (odometer + counters) ───────────
        // Previous scan covered only 00–3F (64 PIDs). Extended to full 00–FF
        // to find any remaining DPF-related counters in the DDxx namespace.
        val udsDD = buildUdsRange(0xDD, 0x00, 0xDD, 0xFF)
        runPhase(ScanPhase.UDS_DDxx, udsDD, TIMEOUT_SWEEP_MS)
        if (isCancelled) return

        // ── Phase 9 — UDS 22 F1xx (standard UDS identifiers) ─────────────────
        val udsF1 = buildUdsRange(0xF1, 0x00, 0xF1, 0xFF)
        runPhase(ScanPhase.UDS_F1xx, udsF1, TIMEOUT_SWEEP_MS)
        if (isCancelled) return

        // ── Phase 10 — UDS 22 FDxx (regen counters area) ─────────────────────
        // Community PIDs: FD87 (failed regens), FD89 (avg distance), FD8A (last regen km).
        // Scanning FD70–FDFF to cover the full regen counter namespace.
        val udsFD = buildUdsRange(0xFD, 0x70, 0xFD, 0xFF)
        runPhase(ScanPhase.UDS_FDxx, udsFD, TIMEOUT_SWEEP_MS)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Phase executor
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun runPhase(
        phase: ScanPhase,
        pids: List<String>,
        timeoutMs: Long
    ) {
        EcuScanRepository.setPhase(phase, pids.size)
        Log.d(TAG, "Phase $phase — ${pids.size} PIDs, ${timeoutMs}ms timeout")

        for ((index, pid) in pids.withIndex()) {
            if (isCancelled) break

            val response  = bleManager.sendRawCommand(pid, timeoutMs)
            val displayKey = pid.toDisplayKey()
            val label     = KNOWN_LABELS[displayKey]
            val status    = classifyResponse(response)
            val decoded   = if (response != null) decode(pid, response) else null

            val result = PidResult(
                phase        = phase,
                pidHex       = displayKey,
                label        = label,
                status       = status,
                responseHex  = response ?: "—",
                decodedValue = decoded,
                timestamp    = System.currentTimeMillis()
            )

            if (status != PidStatus.TIMEOUT || label != null) {
                EcuScanRepository.addResult(result)
            }

            EcuScanRepository.incrementProgress(index + 1, status)

            if (status != PidStatus.TIMEOUT) {
                Log.d(TAG, "[${status.symbol}] $displayKey → ${response ?: "—"} ${decoded?.let { "= $it" } ?: ""}")
            }

            delay(INTER_CMD_DELAY_MS)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** Builds UDS 22 XXYY commands for a byte range, as ELM327 strings. */
    private fun buildUdsRange(
        hiStart: Int, loStart: Int,
        hiEnd: Int,   loEnd: Int
    ): List<String> {
        val result = mutableListOf<String>()
        var hi = hiStart; var lo = loStart
        while (hi < hiEnd || (hi == hiEnd && lo <= loEnd)) {
            result.add("22%02X%02X".format(hi, lo))
            lo++
            if (lo > 0xFF) { lo = 0x00; hi++ }
        }
        return result
    }

    /**
     * Classifies an ELM327 response string into a [PidStatus].
     * Positive responses start with 41 (Mode01), 49 (Mode09), 62 (UDS22).
     */
    private fun classifyResponse(response: String?): PidStatus {
        if (response == null) return PidStatus.TIMEOUT
        val trimmed = response.trim()
        if (trimmed.isEmpty() ||
            trimmed.contains("NO DATA") ||
            trimmed.contains("ERROR")  ||
            trimmed.startsWith("?")) {
            return PidStatus.NEGATIVE
        }

        val bytes = bleManager.hexStringToBytes(trimmed)
        if (bytes == null || bytes.isEmpty()) return PidStatus.NEGATIVE

        return when (bytes[0].toInt() and 0xFF) {
            0x7F                    -> PidStatus.NEGATIVE
            0x41, 0x49, 0x62        -> PidStatus.RESPONDED
            else                    -> PidStatus.RESPONDED
        }
    }

    /**
     * Attempts a human-readable decode for well-known PIDs.
     * Returns null for unknown PIDs — the raw hex is enough.
     */
    private fun decode(elmCmd: String, response: String): String? {
        val bytes = bleManager.hexStringToBytes(response) ?: return null
        if (bytes.size < 2) return null

        return try {
            when (elmCmd.uppercase()) {
                "0105" -> if (bytes.size >= 3) "${(bytes[2].toInt() and 0xFF) - 40} °C (coolant)" else null
                "010C" -> if (bytes.size >= 4) {
                    val raw = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                    "${raw / 4} rpm"
                } else null
                "010D" -> if (bytes.size >= 3) "${bytes[2].toInt() and 0xFF} km/h" else null
                "010F" -> if (bytes.size >= 3) "${(bytes[2].toInt() and 0xFF) - 40} °C (intake air)" else null
                "0111" -> if (bytes.size >= 3) "${"%.1f".format((bytes[2].toInt() and 0xFF) * 100.0 / 255.0)} % (throttle)" else null
                "012F" -> if (bytes.size >= 3) "${"%.1f".format((bytes[2].toInt() and 0xFF) * 100.0 / 255.0)} % (fuel level)" else null
                "0142" -> if (bytes.size >= 4) {
                    val raw = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                    "${"%.3f".format(raw / 1000.0)} V (battery)"
                } else null
                "0146" -> if (bytes.size >= 3) "${(bytes[2].toInt() and 0xFF) - 40} °C (ambient)" else null
                "015C" -> if (bytes.size >= 3) "${(bytes[2].toInt() and 0xFF) - 40} °C (oil)" else null
                "0162" -> if (bytes.size >= 3) "${(bytes[2].toInt() and 0xFF) - 125} % (torque)" else null
                // PID 0178: EGT Sensors Bank1
                // Format: 41 78 <support> <s1Hi> <s1Lo> <s2Hi> <s2Lo> ...
                // bytes[2]=support, bytes[3..4]=sensor1, bytes[5..6]=sensor2
                "0178" -> if (bytes.size >= 5) {
                    val s1 = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    val egt1 = s1 * 0.1 - 40
                    if (bytes.size >= 7) {
                        val s2 = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
                        val egt2 = s2 * 0.1 - 40
                        "${"%.1f".format(egt1)} °C / ${"%.1f".format(egt2)} °C (EGT sensor 1/2)"
                    } else "${"%.1f".format(egt1)} °C (EGT sensor 1)"
                } else null
                // PID 017A: DPF differential pressure (SAE J1979)
                // Format: 41 7A <support> <deltaHi> <deltaLo> <inletHi> <inletLo> <outletHi> <outletLo>
                // support bit0=deltaP, bit1=inletP, bit2=outletP
                // Formula deltaP: (256×B + C) / 100 kPa (signed 16-bit)
                "017A" -> if (bytes.size >= 5) {
                    val support = bytes[2].toInt() and 0xFF
                    val rawDelta = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    val signed16 = if (rawDelta > 0x7FFF) rawDelta - 0x10000 else rawDelta
                    val deltaKpa = signed16 / 100f
                    val inletKpa = if (bytes.size >= 7) {
                        val r = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
                        val s = if (r > 0x7FFF) r - 0x10000 else r
                        "${"%.2f".format(s / 100f)} kPa inlet"
                    } else ""
                    "ΔP=${"%.2f".format(deltaKpa)} kPa | support=0x${support.toString(16).uppercase()} $inletKpa"
                } else null
                "017C" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "${"%.1f".format(raw * 0.1 - 40)} °C (DPF temp)"
                } else null
                "0902" -> if (bytes.size > 3) String(bytes.drop(3).toByteArray()).trim() else null
                // ── Ford 2.0 TDCi DPF analogues (testing on 1.5 TDCi) ────────
                "220579" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ${"%.1f".format(raw / 10.0)} % DPF Load"
                } else null
                "22057B" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ${"%.1f".format(raw / 10.0)} g DPF Soot"
                } else null
                "2209E2" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ${"%.2f".format(raw / 10.05)} kPa DPF ΔP"
                } else null
                // ── Regen counters (FDxx community PIDs) ─────────────────────
                "22FD87" -> if (bytes.size >= 4) {
                    val raw = bytes[3].toInt() and 0xFF
                    "raw=$raw — rigenerazioni fallite"
                } else null
                "22FD89" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw km — distanza media tra regen"
                } else null
                "22FD8A" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw km — ostatnia regeneracja"
                } else null
                // ── Ford confirmed ────────────────────────────────────────────
                "221149" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ~${"%.1f".format(raw * 0.05)} g soot"
                } else null
                "2211A8" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ~${"%.1f".format(raw / 10.0)} % load"
                } else null
                // DD01 confirmed: 62 DD 01 02 59 69 → (02 59 69) = 153 961 km
                "22DD01" -> if (bytes.size >= 6) {
                    val km = ((bytes[3].toLong() and 0xFF) shl 16) or
                             ((bytes[4].toLong() and 0xFF) shl 8)  or
                              (bytes[5].toLong() and 0xFF)
                    "$km km (odometer)"
                } else null
                // DD02 confirmed: 1 data byte — possibly distance or counter
                "22DD02" -> if (bytes.size >= 4) {
                    val raw = bytes[3].toInt() and 0xFF
                    "raw=$raw (0x${raw.toString(16).uppercase()}) — monitor per variazioni"
                } else null
                // DD05 confirmed: 1 byte, cambia tra scan (counter DPF?)
                "22DD05" -> if (bytes.size >= 4) {
                    val raw = bytes[3].toInt() and 0xFF
                    "raw=$raw (0x${raw.toString(16).uppercase()}) — counter (64→63 tra scan)"
                } else null
                // DD00 has 4 bytes — kept for reference, but unit unknown
                "22DD00" -> if (bytes.size >= 7) {
                    val raw = ((bytes[3].toLong() and 0xFF) shl 24) or
                              ((bytes[4].toLong() and 0xFF) shl 16) or
                              ((bytes[5].toLong() and 0xFF) shl 8)  or
                               (bytes[6].toLong() and 0xFF)
                    "raw=0x${raw.toString(16).uppercase()} (unit unknown)"
                } else null
                // ── Bosch EDC17C70 DPF candidates ────────────────────────────
                "221920" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ${"%.1f".format(raw * 0.1)} g (DPF soot est.)"
                } else null
                "221921" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ${"%.1f".format(raw * 0.1)} g (DPF soot limit)"
                } else null
                "221810", "221811" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "${"%.1f".format(raw * 0.1 - 40)} °C (EGT)"
                } else null
                "222048" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ${"%.1f".format(raw * 0.05)} g soot (EDC17)"
                } else null
                "22204A" -> if (bytes.size >= 5) {
                    val raw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                    "raw=$raw → ${"%.1f".format(raw * 0.05)} g ash (EDC17)"
                } else null
                else -> null
            }
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts an ELM327 command string to a display key matching [KNOWN_LABELS].
     * "0105"   → "01 05"
     * "221149" → "22 1149"
     * "0902"   → "09 02"
     */
    private fun String.toDisplayKey(): String = when (length) {
        4 -> "${substring(0, 2)} ${substring(2, 4)}"      // "0105" → "01 05"
        6 -> "${substring(0, 2)} ${substring(2, 6)}"      // "221149" → "22 1149"
        else -> this
    }
}
