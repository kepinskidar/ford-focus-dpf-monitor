package com.example.fordfocusdpfscan.ui

import com.example.fordfocusdpfscan.i18n.tr
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.ble.BleManagerHolder
import com.example.fordfocusdpfscan.ble.DtcReader
import com.example.fordfocusdpfscan.data.DpfRepository
import com.example.fordfocusdpfscan.data.DtcCode
import com.example.fordfocusdpfscan.data.DtcResult
import com.example.fordfocusdpfscan.data.ai.AiPrompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════════════════
// DtcActivity.kt — Reads and clears engine fault codes (check-engine light).
//
// Launched from MainActivity (visible only when connected). Uses the shared
// BleManager via BleManagerHolder; the actual OBD I/O runs on Dispatchers.IO.
// ═══════════════════════════════════════════════════════════════════════════════

class DtcActivity : AppCompatActivity() {

    private lateinit var btnRead: Button
    private lateinit var btnClear: Button
    private lateinit var btnExplain: Button
    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout

    /** Last read result, kept so the AI-explain button can build its prompt. */
    private var lastResult: DtcResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dtc)
        com.example.fordfocusdpfscan.i18n.I18n.apply(window.decorView)
        btnRead   = findViewById(R.id.btnReadDtc)
        btnClear  = findViewById(R.id.btnClearDtc)
        tvStatus  = findViewById(R.id.tvDtcStatus)
        container = findViewById(R.id.containerDtc)

        btnExplain = findViewById(R.id.btnExplainAi)

        btnRead.setOnClickListener { readCodes() }
        btnClear.setOnClickListener { confirmClear() }
        btnExplain.setOnClickListener { explainWithAi() }
    }

    private fun reader(): DtcReader? {
        val ble = BleManagerHolder.instance
        if (ble == null) {
            Toast.makeText(this,
                tr("Adapter niepołączony — połącz się w zakładce Monitor."), Toast.LENGTH_LONG).show()
            return null
        }
        return DtcReader(ble)
    }

    private fun readCodes() {
        val reader = reader() ?: return
        lifecycleScope.launch {
            setBusy(true, tr("Odczyt kodów…"))
            val result = withContext(Dispatchers.IO) { runCatching { reader.readAll() }.getOrNull() }
            setBusy(false, null)
            if (result == null) {
                tvStatus.text = tr("Błąd odczytu. Spróbuj ponownie.")
                return@launch
            }
            render(result)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(tr("Kasuj kody błędów"))
            .setMessage(
                tr("Kasuje zapisane kody i gasi kontrolkę silnika.\n\n") +
                tr("Jeśli usterka nadal występuje, kontrolka zapali się ponownie. Kontynuować?")
            )
            .setPositiveButton(tr("Kasuj")) { _, _ -> clearCodes() }
            .setNegativeButton(tr("Anuluj"), null)
            .show()
    }

    private fun clearCodes() {
        val reader = reader() ?: return
        lifecycleScope.launch {
            setBusy(true, tr("Kasowanie…"))
            val ok = withContext(Dispatchers.IO) { runCatching { reader.clearCodes() }.getOrDefault(false) }
            if (!ok) {
                setBusy(false, null)
                tvStatus.text = tr("Kasowanie nie powiodło się.")
                return@launch
            }
            Toast.makeText(this@DtcActivity, tr("Kody skasowane."), Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) { runCatching { reader.readAll() }.getOrNull() }
            setBusy(false, null)
            if (result != null) render(result) else tvStatus.text = tr("Skasowano. Odczytaj ponownie, aby potwierdzić.")
        }
    }

    private fun setBusy(busy: Boolean, status: String?) {
        btnRead.isEnabled  = !busy
        btnClear.isEnabled = !busy
        if (status != null) tvStatus.text = status
    }

    private fun render(result: DtcResult) {
        lastResult = result
        container.removeAllViews()
        if (result.isEmpty) {
            tvStatus.text = tr("✅ Brak zapisanych kodów błędów.")
            btnClear.visibility = View.GONE
            btnExplain.visibility = View.GONE
            return
        }
        tvStatus.text = tr("Znalezione kody: %s").format(result.total)
        btnClear.visibility = View.VISIBLE
        btnExplain.visibility = View.VISIBLE

        addSection(tr("🔴  Potwierdzone (kontrolka silnika)"), result.stored,    0xFFFF3B30.toInt())
        addSection(tr("🟡  Oczekujące"),               result.pending,   0xFFFF9F0A.toInt())
        addSection(tr("🔒  Stałe"),               result.permanent, 0xFF9E9E9E.toInt())
    }

    private fun addSection(title: String, codes: List<DtcCode>, accent: Int) {
        if (codes.isEmpty()) return
        container.addView(TextView(this).apply {
            text = title
            setTextColor(accent)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(14), dp(4), dp(6))
        })
        codes.forEach { container.addView(codeRow(it, accent)) }
    }

    private fun codeRow(code: DtcCode, accent: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xFF12203A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        }
        row.addView(TextView(this).apply {
            text = code.code
            setTextColor(accent)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = code.description
            setTextColor(0xFFC7D2E0.toInt())
            textSize = 13f
        })
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ═════════════════════════════════════════════════════════════════════════
    // AI explanation — send the codes + live context to Claude
    // ═════════════════════════════════════════════════════════════════════════

    private fun explainWithAi() {
        val result = lastResult ?: return
        val d = DpfRepository.dpfData.value
        fun n(v: Float, unit: String) = if (v >= 0f) "${v.toInt()}$unit" else "b/d"

        val sb = StringBuilder(tr("Kody odczytane ze sterownika:\n"))
        result.stored.forEach    { sb.append("- ${it.code} (potwierdzony): ${it.description}\n") }
        result.pending.forEach   { sb.append(tr("- %s (oczekujący): %s\n").format(it.code, it.description)) }
        result.permanent.forEach { sb.append(tr("- %s (stały): %s\n").format(it.code, it.description)) }
        sb.append(tr("\nDane na żywo:\n"))
        sb.append(tr("Sadza %s, Obciążenie %s, ").format(n(d.sootPercentage, "%"), n(d.loadPercentage, "%")))
        sb.append(tr("EGT %s, ").format(n(d.egtCelsius, "°C")))
        sb.append(tr("Delta P "))
        sb.append(if (d.dpfDeltaPressureKpa >= 0f) "%.1f kPa".format(d.dpfDeltaPressureKpa) else "b/d")
        sb.append(", km od ostatniej regen. ${d.kmSinceLastRegen}")
        sb.append(", km od wymiany oleju ${d.kmSinceOilChange}, licznik ${d.odometerKm}")

        AiAssist.run(this, tr("Wyjaśnienie kodów"), AiPrompts.dtcSystem(com.example.fordfocusdpfscan.ble.EngineProfile.active(this)), sb.toString())
    }
}
