package com.example.fordfocusdpfscan.data.ai

import android.content.Context

// ═══════════════════════════════════════════════════════════════════════════════
// AiSettings.kt — Local storage for the optional Claude AI features.
//
// Holds the user-supplied Anthropic API key, the chosen model, and a LOCAL
// usage/cost estimate accumulated from the `usage` field of each API response.
//
// The key never leaves the device (app-private SharedPreferences). The cost figure
// is an ESTIMATE from token counts × published per-token prices — the authoritative
// billing is in the Anthropic Console.
// ═══════════════════════════════════════════════════════════════════════════════

class AiSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "ai_prefs"
        private const val KEY_API       = "api_key"
        private const val KEY_MODEL     = "model"
        private const val KEY_CALLS     = "usage_calls"
        private const val KEY_IN        = "usage_input"
        private const val KEY_OUT       = "usage_output"
        private const val KEY_COST      = "usage_cost_micro"   // micro-USD (Long) for precision
        private const val KEY_LAST_COST = "usage_last_micro"

        const val DEFAULT_MODEL = "claude-sonnet-5"

        /** Approx USD per 1M tokens (input, output). Update if Anthropic pricing changes. */
        val PRICING: Map<String, Pair<Double, Double>> = mapOf(
            "claude-haiku-4-5" to (1.0 to 5.0),
            "claude-sonnet-5"  to (3.0 to 15.0),
            "claude-opus-4-8"  to (5.0 to 25.0)
        )

        /** Models offered in the Settings dropdown, with human labels. */
        val MODELS = listOf("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5")
        val MODEL_LABELS = mapOf(
            "claude-opus-4-8" to "Opus 4.8 — max qualità  ($5 / $25 per 1M)",
            "claude-sonnet-5" to "Sonnet 5 — bilanciato  ($3 / $15 per 1M)",
            "claude-haiku-4-5" to "Haiku 4.5 — tani/szybki  ($1 / $5 per 1M)"
        )
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)
        set(v) { prefs.edit().putString(KEY_API, v?.trim()).apply() }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { prefs.edit().putString(KEY_MODEL, v).apply() }

    val hasKey: Boolean get() = !apiKey.isNullOrBlank()

    // ── Usage / cost (local estimate) ─────────────────────────────────────────
    val totalCalls: Int         get() = prefs.getInt(KEY_CALLS, 0)
    val totalInputTokens: Long  get() = prefs.getLong(KEY_IN, 0L)
    val totalOutputTokens: Long get() = prefs.getLong(KEY_OUT, 0L)
    val totalCostUsd: Double    get() = prefs.getLong(KEY_COST, 0L) / 1_000_000.0
    val lastCostUsd: Double     get() = prefs.getLong(KEY_LAST_COST, 0L) / 1_000_000.0

    /** Accumulates one API call's usage into the local counters. */
    fun addUsage(model: String, inputTokens: Long, outputTokens: Long, cacheRead: Long, cacheWrite: Long) {
        val (inPrice, outPrice) = PRICING[model] ?: (5.0 to 25.0)
        // Cache reads ≈ 0.1× input, cache writes ≈ 1.25× input (approx).
        val costUsd = (inputTokens * inPrice +
                       outputTokens * outPrice +
                       cacheRead * inPrice * 0.1 +
                       cacheWrite * inPrice * 1.25) / 1_000_000.0
        val micro = (costUsd * 1_000_000).toLong()
        prefs.edit()
            .putInt(KEY_CALLS, totalCalls + 1)
            .putLong(KEY_IN, totalInputTokens + inputTokens)
            .putLong(KEY_OUT, totalOutputTokens + outputTokens)
            .putLong(KEY_COST, prefs.getLong(KEY_COST, 0L) + micro)
            .putLong(KEY_LAST_COST, micro)
            .apply()
    }

    fun resetUsage() {
        prefs.edit()
            .remove(KEY_CALLS).remove(KEY_IN).remove(KEY_OUT)
            .remove(KEY_COST).remove(KEY_LAST_COST)
            .apply()
    }
}
