package com.example.fordfocusdpfscan.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════════════════
// EcuScanRepository.kt — State holder for the ECU PID discovery scan.
//
// Consumed by:
//   • EcuPidScanner  — pushes results in real-time
//   • EcuScanActivity — collects ScanState to update the UI
//
// The entire scan state is collapsed into a single StateFlow<ScanState> so the
// Activity only needs one observer.  buildLogText() formats the full report
// for the share intent.
// ═══════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
// Enums
// ─────────────────────────────────────────────────────────────────────────────

/** Classification of the ECU's reply to a single PID query. */
enum class PidStatus(val symbol: String) {
    RESPONDED("✓"),   // Positive response (41 / 62 / 49)
    NEGATIVE("✗"),    // Negative response (7F)
    TIMEOUT("–")      // No reply within the timeout window
}

/** The scan phases executed in order by EcuPidScanner. */
enum class ScanPhase(val displayName: String) {
    PRIORITY      ("PID-y priorytetowe (znane/ważne)"),
    MODE01_SWEEP  ("Tryb 01 — pełne skanowanie 00–FF"),
    MODE09_INFO   ("Tryb 09 — informacje o pojeździe (VIN…)"),
    UDS_05xx      ("UDS 22 05xx — obszar obciążenia/sadzy DPF (odpowiednik z 2.0 TDCi)"),
    UDS_09xx      ("UDS 22 09xx — PID-y ciśnienia DPF (społeczność)"),
    UDS_10xx      ("UDS 22 11xx (DPF / silnik — potwierdzony zakres)"),
    UDS_40xx      ("UDS 22 40xx–41xx (TPMS / BCM)"),
    UDS_DDxx      ("UDS 22 DDxx (licznik + liczniki — pełny zakres)"),
    UDS_F1xx      ("UDS 22 F1xx (standardowe ID UDS)"),
    UDS_FDxx      ("UDS 22 FDxx — liczniki regeneracji (km od ostatniej, nieudane…)")
}

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

/** A single PID query result, pushed to the repository after each command. */
data class PidResult(
    val phase        : ScanPhase,
    val pidHex       : String,         // e.g. "22 11 49"
    val label        : String?,        // human-readable name if known, else null
    val status       : PidStatus,
    val responseHex  : String,         // raw response bytes as hex, "—" on timeout
    val decodedValue : String?,        // human-readable decoded value if available
    val timestamp    : Long            // System.currentTimeMillis()
)

/**
 * Snapshot of the entire scan state — emitted by [EcuScanRepository.scanState]
 * on every change so the UI always has a consistent picture.
 */
data class ScanState(
    val isRunning       : Boolean         = false,
    val isCompleted     : Boolean         = false,
    val currentPhase    : ScanPhase?      = null,
    val phaseTotalPids  : Int             = 0,
    val phaseProgress   : Int             = 0,
    val totalResponded  : Int             = 0,
    val totalNegative   : Int             = 0,
    val totalTimeout    : Int             = 0,
    val results         : List<PidResult> = emptyList()
) {
    /** Overall count of PIDs queried so far. */
    val totalQueried: Int get() = totalResponded + totalNegative + totalTimeout

    /** Phase progress as 0–100 for a ProgressBar. */
    val phaseProgressPercent: Int
        get() = if (phaseTotalPids > 0) (phaseProgress * 100 / phaseTotalPids) else 0
}

// ─────────────────────────────────────────────────────────────────────────────
// Repository singleton
// ─────────────────────────────────────────────────────────────────────────────

object EcuScanRepository {

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // ── Called by EcuPidScanner ───────────────────────────────────────────────

    /** Resets all state and marks the scan as running. */
    fun reset() {
        _scanState.value = ScanState(isRunning = true)
    }

    /** Signals the start of a new scan phase. */
    fun setPhase(phase: ScanPhase, totalPids: Int) {
        _scanState.value = _scanState.value.copy(
            currentPhase   = phase,
            phaseTotalPids = totalPids,
            phaseProgress  = 0
        )
    }

    /** Appends a single [PidResult] to the live results list. */
    fun addResult(result: PidResult) {
        val current = _scanState.value
        _scanState.value = current.copy(results = current.results + result)
    }

    /**
     * Increments the phase progress counter and the appropriate status bucket.
     * Called for every PID, even timeouts (so the progress bar stays accurate).
     */
    fun incrementProgress(progress: Int, status: PidStatus) {
        val s = _scanState.value
        _scanState.value = s.copy(
            phaseProgress  = progress,
            totalResponded = s.totalResponded + if (status == PidStatus.RESPONDED) 1 else 0,
            totalNegative  = s.totalNegative  + if (status == PidStatus.NEGATIVE)  1 else 0,
            totalTimeout   = s.totalTimeout   + if (status == PidStatus.TIMEOUT)   1 else 0
        )
    }

    /** Marks the scan as finished. */
    fun setCompleted() {
        _scanState.value = _scanState.value.copy(isRunning = false, isCompleted = true)
    }

    // ── Log generation ────────────────────────────────────────────────────────

    /**
     * Formats all results into a plain-text log suitable for sharing.
     * Sections are separated by phase headers.  Known PIDs are annotated
     * with their label and decoded value so you can immediately identify them.
     */
    fun buildLogText(): String {
        val state   = _scanState.value
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        return buildString {
            appendLine("═══════════════════════════════════════════════════════")
            appendLine("  MK5 DPF — RAPORT SKANOWANIA PID ECU")
            appendLine("  Wygenerowano: $dateStr")
            appendLine("═══════════════════════════════════════════════════════")
            appendLine()
            appendLine("  PODSUMOWANIE")
            appendLine("  ─────────────────────────────────────────────────────")
            appendLine("  Zapytane PID-y  : ${state.totalQueried}")
            appendLine("  Odp. pozytywne  : ${state.totalResponded}  ✓")
            appendLine("  Odp. negatywne  : ${state.totalNegative}  ✗")
            appendLine("  Brak odpowiedzi : ${state.totalTimeout}  –")
            appendLine()

            // Group results by phase for readability
            var lastPhase: ScanPhase? = null
            for (result in state.results) {
                if (result.phase != lastPhase) {
                    appendLine()
                    appendLine("  ── ${result.phase.displayName} ──")
                    lastPhase = result.phase
                }

                val decoded = result.decodedValue?.let { " = $it" } ?: ""
                val label   = result.label?.let { "  [$it]" } ?: ""
                appendLine("  [${result.status.symbol}] ${result.pidHex.padEnd(8)}  ${result.responseHex}$decoded$label")
            }

            appendLine()
            appendLine("═══════════════════════════════════════════════════════")
            appendLine("  KONIEC RAPORTU")
            appendLine()
            appendLine("  JAK UŻYWAĆ TEGO LOGU:")
            appendLine("  1. Szukaj linii ✓ — to PID-y obsługiwane przez Twoje ECU.")
            appendLine("  2. Etykiety ★★★ = priorytetowi kandydaci na EGT / flagę regeneracji.")
            appendLine("  3. Wyślij ten plik, aby zmapować rzeczywiste wartości PID.")
        }
    }
}
