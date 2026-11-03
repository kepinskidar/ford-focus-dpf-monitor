package com.example.fordfocusdpfscan.ui

import com.example.fordfocusdpfscan.i18n.tr
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.data.ai.AiSettings
import com.example.fordfocusdpfscan.data.ai.ClaudeClient
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════════
// SettingsActivity.kt — API key entry, model choice, and local cost monitor
// for the optional Claude AI features.
// ═══════════════════════════════════════════════════════════════════════════════

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AiSettings
    private lateinit var etApiKey: TextInputEditText
    private lateinit var spModel: Spinner
    private lateinit var tvCalls: TextView
    private lateinit var tvTokens: TextView
    private lateinit var tvCost: TextView
    private lateinit var tvLastCost: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        com.example.fordfocusdpfscan.i18n.I18n.apply(window.decorView)
        settings = AiSettings(this)

        etApiKey   = findViewById(R.id.etApiKey)
        spModel    = findViewById(R.id.spModel)
        tvCalls    = findViewById(R.id.tvCalls)
        tvTokens   = findViewById(R.id.tvTokens)
        tvCost     = findViewById(R.id.tvCost)
        tvLastCost = findViewById(R.id.tvLastCost)

        // Pre-fill
        etApiKey.setText(settings.apiKey)

        // Model dropdown
        val labels = AiSettings.MODELS.map { AiSettings.MODEL_LABELS[it] ?: it }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spModel.adapter = adapter
        spModel.setSelection(AiSettings.MODELS.indexOf(settings.model).coerceAtLeast(0))

        findViewById<Button>(R.id.btnSaveAi).setOnClickListener {
            saveInputs()
            Toast.makeText(this, tr("Ustawienia zapisane"), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTestAi).setOnClickListener { testConnection(it as Button) }

        findViewById<Button>(R.id.btnResetUsage).setOnClickListener {
            settings.resetUsage()
            refreshDashboard()
            Toast.makeText(this, tr("Liczniki wyzerowane"), Toast.LENGTH_SHORT).show()
        }

        refreshDashboard()
    }

    override fun onPause() {
        super.onPause()
        // Persist edits even if the user leaves without tapping Salva.
        saveInputs()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun saveInputs() {
        settings.apiKey = etApiKey.text?.toString()
        settings.model  = AiSettings.MODELS[spModel.selectedItemPosition]
    }

    private fun testConnection(btn: Button) {
        saveInputs()
        if (!settings.hasKey) {
            Toast.makeText(this, tr("Najpierw wpisz klucz API"), Toast.LENGTH_SHORT).show()
            return
        }
        btn.isEnabled = false
        btn.text = tr("Sprawdzanie…")
        lifecycleScope.launch {
            val result = ClaudeClient(settings).ask(tr("Odpowiedz tylko: OK"), "ping")
            btn.isEnabled = true
            btn.text = tr("Testuj połączenie")
            result
                .onSuccess { Toast.makeText(this@SettingsActivity, tr("Połączenie udane ✓"), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@SettingsActivity, tr("Błąd: %s").format(it.message), Toast.LENGTH_LONG).show() }
            refreshDashboard()
        }
    }

    private fun refreshDashboard() {
        tvCalls.text    = tr("Zapytania: %s").format(settings.totalCalls)
        tvTokens.text   = tr("Tokeny: %,d wej. / %,d wyj.").format(settings.totalInputTokens, settings.totalOutputTokens)
        tvCost.text     = tr("Szacowany koszt łącznie: $%.4f").format(settings.totalCostUsd)
        tvLastCost.text = tr("Ostatnie zapytanie: $%.4f").format(settings.lastCostUsd)
    }
}
