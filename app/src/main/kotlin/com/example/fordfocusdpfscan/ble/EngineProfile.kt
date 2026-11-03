package com.example.fordfocusdpfscan.ble

import android.content.Context

// ═══════════════════════════════════════════════════════════════════════════════
// EngineProfile.kt — Per-engine ELM327 configuration.
//
// Everything that depends on the engine / ECU lives here: the CAN header,
// whether to request the UDS extended session and which commands are polled.
//
// ECOBLUE_20 (Ford Mondeo Mk5 2019, 2.0 EcoBlue 190 KM) — based on a real scan:
//   * Standard PIDs confirmed working: 01 05/0B/0C/0D/0F/42/5C/59, 01 78 (EGT, 2 sensors)
//     and 01 7A (DPF delta pressure). 01 23 and 01 7C/7D are NOT supported.
//   * Ford odometer 22 DD01 works (matches standard 01 A6).
//   * Mode 22 05xx DIDs 0579 (load), 057B (soot), 050B (km since regen) and 0542
//     (km since oil change) answer positively in the default session with plausible
//     values, so they are polled — but their meaning on EcoBlue is UNVERIFIED
//     (they were reverse engineered on the 1.5 TDCi). Verify while driving: soot
//     should fall after a regeneration and km-since-regen should reset to ~0.
// ═══════════════════════════════════════════════════════════════════════════════

data class EngineProfile(
    val id: String,
    val displayName: String,
    /** Short label shown under the app title. */
    val subtitle: String,
    /** ELM327 "ATSH" header — CAN request ID of the engine ECU. */
    val ecuHeader: String,
    /** Send UDS 10 03 (extended diagnostic session) after the ELM init. */
    val requestExtendedSession: Boolean,
    /** Commands polled in a loop while connected. */
    val pids: List<String>
) {
    /** Full ELM327 init sequence for this profile. */
    val elmInit: List<String>
        get() = buildList {
            add("ATZ")                  // soft reset
            add("ATE0")                 // echo off
            add("ATL0")                 // linefeeds off
            add("ATH0")                 // headers off
            add("ATAT1")                // adaptive timing
            add("ATSP0")                // auto-detect OBD protocol
            add("ATSH$ecuHeader")       // engine ECU request header
            if (requestExtendedSession) add("1003")
        }

    companion object {
        private const val PREFS_NAME = "focus_prefs"
        private const val KEY_PROFILE = "engine_profile_id"

        val FOCUS_15_TDCI = EngineProfile(
            id = "focus_15_tdci",
            displayName = "Ford Focus 1.5 TDCi (EDC17C70)",
            subtitle = "Ford Focus 1.5 TDCi · EDC17C70",
            ecuHeader = "7E0",
            requestExtendedSession = true,
            pids = listOf(
                BleManager.CMD_COOLANT_TEMP, BleManager.CMD_EGT, BleManager.CMD_DPF_PRESSURE,
                BleManager.CMD_DPF_LOAD, BleManager.CMD_DPF_SOOT, BleManager.CMD_ODOMETER,
                BleManager.CMD_LAST_REGEN_DIST, BleManager.CMD_OIL_CHANGE,
                BleManager.CMD_RPM, BleManager.CMD_SPEED, BleManager.CMD_ENGINE_LOAD,
                BleManager.CMD_INTAKE_MAP, BleManager.CMD_BARO_PRESSURE, BleManager.CMD_OIL_TEMP,
                BleManager.CMD_BATTERY_VOLTAGE, BleManager.CMD_FUEL_RAIL_PRESS_FORD,
                BleManager.CMD_INTAKE_AIR_TEMP
            )
        )

        val MONDEO_ECOBLUE_20 = EngineProfile(
            id = "mondeo_mk5_20_ecoblue",
            displayName = "Ford Mondeo Mk5 2.0 EcoBlue 190 KM",
            subtitle = "Ford Mondeo Mk5 · 2.0 EcoBlue 190 KM",
            ecuHeader = "7E0",
            requestExtendedSession = false,
            pids = listOf(
                BleManager.CMD_COOLANT_TEMP, BleManager.CMD_EGT, BleManager.CMD_DPF_PRESSURE,
                BleManager.CMD_ODOMETER,
                // EXPERIMENTAL — these Mode 22 DIDs answer on the Mondeo ECU (scan 2026-10-30)
                // with plausible values, but their meaning is NOT yet verified on EcoBlue.
                BleManager.CMD_DPF_LOAD, BleManager.CMD_DPF_SOOT,
                BleManager.CMD_LAST_REGEN_DIST, BleManager.CMD_OIL_CHANGE,
                BleManager.CMD_RPM, BleManager.CMD_SPEED, BleManager.CMD_ENGINE_LOAD,
                BleManager.CMD_INTAKE_MAP, BleManager.CMD_BARO_PRESSURE, BleManager.CMD_OIL_TEMP,
                BleManager.CMD_BATTERY_VOLTAGE,
                // 01 23 is NOT supported by this ECU (scan) — only 01 59 is polled.
                BleManager.CMD_FUEL_RAIL_PRESS_A,
                BleManager.CMD_INTAKE_AIR_TEMP
            )
        )

        val ALL = listOf(MONDEO_ECOBLUE_20, FOCUS_15_TDCI)

        /** Active profile (default: Mondeo Mk5 2.0 EcoBlue). */
        fun active(context: Context): EngineProfile {
            val id = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PROFILE, null)
            return ALL.firstOrNull { it.id == id } ?: MONDEO_ECOBLUE_20
        }

        fun setActive(context: Context, profile: EngineProfile) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PROFILE, profile.id).apply()
        }
    }
}
