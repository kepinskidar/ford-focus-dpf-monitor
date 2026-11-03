package com.example.fordfocusdpfscan.ui

import com.example.fordfocusdpfscan.i18n.tr
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.fordfocusdpfscan.ble.BleManagerHolder
import com.example.fordfocusdpfscan.ble.EcuPidScanner
import com.example.fordfocusdpfscan.ble.RegenStateCapture
import com.example.fordfocusdpfscan.data.EcuScanRepository
import com.example.fordfocusdpfscan.data.PidResult
import com.example.fordfocusdpfscan.data.PidStatus
import com.example.fordfocusdpfscan.data.ScanPhase
import com.example.fordfocusdpfscan.data.ScanState
import com.example.fordfocusdpfscan.data.ai.AiPrompts
import com.example.fordfocusdpfscan.databinding.ActivityEcuScanBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// EcuScanActivity.kt — Phone UI for the ECU PID discovery scan.
//
// Responsibilities:
//   1. Display real-time scan progress (phase, counts, live log).
//   2. Start / stop the EcuPidScanner via BleManagerHolder.
//   3. Share the complete log file via system share sheet.
//
// The activity obtains the BleManager instance from BleManagerHolder (set by
// DpfForegroundService on connect).  If no instance is available it shows an
// error and finishes.
//
// The log is rendered in a monospace TextView inside a ScrollView and auto-
// scrolls to the bottom after each new line is appended.
// ═══════════════════════════════════════════════════════════════════════════════

class EcuScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEcuScanBinding

    private var scanner: EcuPidScanner? = null

    // Tracks the phase currently shown in the log so we can insert headers
    private var lastLogPhase: ScanPhase? = null
    // Count of results rendered so far — used to append only new lines
    private var renderedResultCount = 0

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEcuScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.example.fordfocusdpfscan.i18n.I18n.apply(window.decorView)

        setupToolbar()
        setupButtons()
        observeScanState()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any running scan when the activity is closed so the normal
        // DPF polling loop resumes promptly.
        scanner?.cancelScan()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Setup
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupButtons() {

        // ── Start scan ────────────────────────────────────────────────────────
        binding.btnStartScan.setOnClickListener {
            val bleManager = BleManagerHolder.instance
            if (bleManager == null) {
                Toast.makeText(this,
                    tr("Adapter OBD nie jest połączony. Najpierw połącz się na ekranie głównym."),
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Reset log state
            lastLogPhase = null
            renderedResultCount = 0
            binding.tvLog.text = ""

            // Create scanner and start
            scanner = EcuPidScanner(bleManager)
            scanner!!.startScan(lifecycleScope)

            // Update button visibility
            binding.btnStartScan.visibility = View.GONE
            binding.btnStopScan.visibility  = View.VISIBLE
            binding.btnShareLog.isEnabled   = false
            binding.btnAnalyzeAi.isEnabled  = false
        }

        // ── Analyze with AI ─────────────────────────────────────────────────────
        binding.btnAnalyzeAi.setOnClickListener { analyzeWithAi() }

        // ── Stop scan ─────────────────────────────────────────────────────────
        binding.btnStopScan.setOnClickListener {
            scanner?.cancelScan()
            binding.btnStopScan.visibility  = View.GONE
            binding.btnStartScan.visibility = View.VISIBLE
            binding.tvCurrentPhase.text = tr("Skanowanie anulowane")
        }

        // ── Share log — writes a .txt file and shares via FileProvider ───────
        // Using EXTRA_STREAM instead of EXTRA_TEXT avoids truncation by
        // WhatsApp / Drive / Gmail which cap inline text at ~250 KB.
        binding.btnShareLog.setOnClickListener {
            val logText = EcuScanRepository.buildLogText()
            try {
                val dateTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir = File(cacheDir, "scan_reports").also { it.mkdirs() }
                val file = File(dir, "MK5DPF_scan_$dateTag.txt")
                file.writeText(logText, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.provider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "MK5 DPF — raport skanowania PID ECU")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, tr("Udostępnij raport ECU")))
            } catch (e: Exception) {
                Toast.makeText(this, tr("Błąd zapisu: %s").format(e.message), Toast.LENGTH_LONG).show()
            }
        }

        // ── Share the auto-captured regen ECU-state log ──────────────────────
        // Filled automatically by DpfForegroundService when a regen is detected
        // (RegenStateCapture writes to filesDir/regen_captures.txt).
        binding.btnShareCapture.setOnClickListener { shareRegenCapture() }
    }

    /** Shares filesDir/regen_captures.txt if any regen state has been captured. */
    private fun shareRegenCapture() {
        val file = File(filesDir, RegenStateCapture.FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this,
                tr("Brak zapisu. Zostanie utworzony automatycznie podczas regeneracji."),
                Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MK5 DPF — zapis stanu regeneracji")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, tr("Udostępnij zapis regen.")))
        } catch (e: Exception) {
            Toast.makeText(this, tr("Błąd udostępniania: %s").format(e.message), Toast.LENGTH_LONG).show()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // State observer
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Collects [EcuScanRepository.scanState] and:
     *   • updates stats (phase label, progress bar, counts)
     *   • appends only NEW result lines to the log TextView
     *   • auto-scrolls the ScrollView to the bottom
     */
    private fun observeScanState() {
        lifecycleScope.launch {
            EcuScanRepository.scanState.collectLatest { state ->
                updateStats(state)
                appendNewResults(state)

                if (state.isCompleted) {
                    onScanCompleted(state)
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI update helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateStats(state: ScanState) {
        // Phase label
        val phaseLabel = when {
            state.isCompleted -> tr("✅ Skanowanie zakończone")
            state.isRunning && state.currentPhase != null ->
                tr("Faza: %s").format(state.currentPhase.displayName)
            else -> tr("Gotowy do skanowania")
        }
        binding.tvCurrentPhase.text = phaseLabel

        // Progress bar
        binding.progressPhase.progress = state.phaseProgressPercent

        // Count badges
        binding.tvCountResponded.text = state.totalResponded.toString()
        binding.tvCountNegative.text  = state.totalNegative.toString()
        binding.tvCountTimeout.text   = state.totalTimeout.toString()
    }

    /**
     * Appends only the results that have been added since the last render.
     * This avoids rebuilding the entire log on every emission.
     */
    private fun appendNewResults(state: ScanState) {
        val newResults = state.results.drop(renderedResultCount)
        if (newResults.isEmpty()) return

        val sb = StringBuilder()

        for (result in newResults) {
            // Insert phase header when phase changes
            if (result.phase != lastLogPhase) {
                if (lastLogPhase != null) sb.append("\n")
                sb.append("── ${result.phase.displayName} ──\n")
                lastLogPhase = result.phase
            }
            sb.append(formatResult(result))
            sb.append("\n")
        }

        binding.tvLog.append(sb.toString())
        renderedResultCount = state.results.size

        // Auto-scroll to bottom
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Formats a single [PidResult] line for the monospace log.
     * Format: [✓] 22 11 49  62 11 49 00 1E = ~1.5 g soot  [★★★ DPF Soot]
     */
    private fun formatResult(result: PidResult): String {
        val decoded = result.decodedValue?.let { " = $it" } ?: ""
        val label   = result.label?.let { "  [$it]" } ?: ""
        val pid     = result.pidHex.padEnd(8)
        val resp    = if (result.status == PidStatus.TIMEOUT) "(brak odpowiedzi)" else result.responseHex
        return "[${result.status.symbol}] $pid  $resp$decoded$label"
    }

    private fun onScanCompleted(state: ScanState) {
        binding.btnStopScan.visibility  = View.GONE
        binding.btnStartScan.visibility = View.VISIBLE
        binding.btnShareLog.isEnabled   = true
        binding.btnAnalyzeAi.isEnabled  = true
        binding.progressPhase.progress  = 100

        // Append summary footer to log
        binding.tvLog.append(
            tr("\n\n══ SKANOWANIE ZAKOŃCZONE ══\n") +
            tr("  Odpowiedzi : %s\n").format(state.totalResponded) +
            tr("  Negatywne  : %s\n").format(state.totalNegative) +
            tr("  Brak odp.  : %s\n").format(state.totalTimeout) +
            tr("\nDotknij [Udostępnij log], aby wysłać raport.")
        )

        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }

        Toast.makeText(this, tr("Skanowanie zakończone! Odpowiedziało PID-ów: %s.").format(state.totalResponded), Toast.LENGTH_LONG).show()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AI analysis — send the responded PIDs to Claude for interpretation
    // ═════════════════════════════════════════════════════════════════════════

    private fun analyzeWithAi() {
        val responded = EcuScanRepository.scanState.value.results
            .filter { it.status == PidStatus.RESPONDED }
        if (responded.isEmpty()) {
            Toast.makeText(this, tr("Brak PID-ów z odpowiedzią do analizy."), Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder(tr("PID-y z odpowiedzią pozytywną:\n"))
        for (r in responded) {
            sb.append(r.pidHex).append("  ").append(r.responseHex)
            r.decodedValue?.let { sb.append(" = ").append(it) }
            r.label?.let { sb.append("  [").append(it).append("]") }
            sb.append('\n')
        }
        AiAssist.run(this, tr("Analiza skanowania"), AiPrompts.scanSystem(com.example.fordfocusdpfscan.ble.EngineProfile.active(this)), sb.toString())
    }
}
