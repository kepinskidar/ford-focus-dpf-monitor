package com.example.fordfocusdpfscan.ui

import com.example.fordfocusdpfscan.i18n.tr
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fordfocusdpfscan.data.ai.AiSettings
import com.example.fordfocusdpfscan.data.ai.ClaudeClient
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════════
// AiAssist.kt — Shared helper to run a one-shot Claude request from any screen,
// with a progress dialog, a scrollable result dialog, and a "no key" prompt that
// deep-links to Settings. Keeps the DTC and ECU-scan screens tiny.
// ═══════════════════════════════════════════════════════════════════════════════

object AiAssist {

    fun run(activity: AppCompatActivity, title: String, system: String, userText: String) {
        val settings = AiSettings(activity)
        if (!settings.hasKey) {
            promptForKey(activity)
            return
        }

        val progress = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(tr("Trwa analiza AI…"))
            .setCancelable(false)
            .create()
        progress.show()

        activity.lifecycleScope.launch {
            val result = ClaudeClient(settings).ask(system, userText)
            if (progress.isShowing) progress.dismiss()
            result
                .onSuccess { showResult(activity, title, it) }
                .onFailure { showResult(activity, tr("Błąd AI"), it.message ?: tr("Nieznany błąd")) }
        }
    }

    private fun showResult(activity: AppCompatActivity, title: String, message: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)          // AppCompat wraps long text in a ScrollView
            .setPositiveButton(tr("Zamknij"), null)
            .show()
    }

    private fun promptForKey(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Brak klucza AI"))
            .setMessage(tr("Aby korzystać z funkcji AI, potrzebny jest klucz API Anthropic.\nOtworzyć ustawienia AI teraz?"))
            .setPositiveButton(tr("Otwórz ustawienia")) { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .setNegativeButton(tr("Anuluj"), null)
            .show()
    }
}
