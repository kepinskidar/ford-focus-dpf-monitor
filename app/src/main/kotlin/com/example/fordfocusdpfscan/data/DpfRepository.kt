package com.example.fordfocusdpfscan.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ═══════════════════════════════════════════════════════════════════════════════
// DpfRepository.kt — Singleton data store and regen detection engine.
//
// Responsibilities:
//   1. Hold the single source of truth (StateFlow<DpfData>) consumed by both
//      the phone UI (MainActivity) and the Android Auto screen (DpfScreen).
//   2. Apply regen detection logic (Strategy A + Strategy B) on every update.
//   3. Persist history data (last regen km, last service km) in SharedPreferences.
//   4. Expose callback lambdas so NotificationHelper can react to state changes.
// ═══════════════════════════════════════════════════════════════════════════════

object DpfRepository {

    // ── SharedPreferences ─────────────────────────────────────────────────────
    private const val PREFS_NAME = "focus_prefs"
    /** Persisted last valid odometer km (survives disconnect / app restart). */
    private const val KEY_LAST_ODO = "last_odometer_km"
    private const val KEY_LAST_REGEN_END = "last_regen_end_odometer_km"

    /** Odometer reading when the last completed regen ended (-1 = unknown). */
    private var lastRegenEndKm: Long = -1L

    // ── Odometer glitch filter ──────────────────────────────────────────────────
    /** Max plausible forward step between two accepted odometer readings (km). */
    private const val ODO_MAX_STEP_KM    = 50L
    /** A backward move or a jump beyond [ODO_MAX_STEP_KM] must repeat within this
     *  tolerance before it is trusted — filters one-off parser glitches. */
    private const val ODO_CONFIRM_TOL_KM = 3L
    private const val ODO_JUMP_CONFIRM   = 2

    // ── Internal mutable state ────────────────────────────────────────────────
    private val _dpfData = MutableStateFlow(DpfData())
    val dpfData: StateFlow<DpfData> = _dpfData.asStateFlow()

    /** EGT-based regen state machine (pure, unit-tested — see RegenEvaluator). */
    private val regenEvaluator = RegenEvaluator()

    // ── Odometer glitch-filter state ────────────────────────────────────────────
    /** Last odometer value accepted as valid (mirrored in SharedPreferences). */
    private var lastGoodOdometerKm = 0L
    /** Candidate odometer awaiting confirmation (anomalous jump). */
    private var pendingOdometerKm = 0L
    private var pendingOdometerCount = 0

    /** SharedPreferences instance — initialised once via [init]. */
    private lateinit var prefs: SharedPreferences

    // ── Turbo cooldown state ──────────────────────────────────────────────────
    /** Duration of the post-trip cooldown in ms. Exposed so DpfScreen can compute seconds left. */
    const val COOLDOWN_DURATION_MS = 45_000L

    /** Minimum speed (km/h) the car must reach to arm the cooldown.
     *  Prevents false triggers at engine startup (speed=0, RPM=idle right away). */
    private const val COOLDOWN_ARM_SPEED_KMH = 10f

    /** RPM range considered "engine at idle" (running but not being driven).
     *  Ford Focus 1.5 TDCi idles at ~750 RPM. */
    private val COOLDOWN_IDLE_RPM_RANGE = 500f..1100f

    /** Vehicle speed threshold below which we consider the car stopped (km/h). */
    private const val COOLDOWN_STOPPED_KMH = 3f

    /** True once the car has actually moved (speed > [COOLDOWN_ARM_SPEED_KMH]).
     *  Guards against false triggers at engine startup. */
    private var wasMoving = false

    /** Consecutive samples with speed < [COOLDOWN_STOPPED_KMH] and RPM at idle.
     *  Must reach [COOLDOWN_STOPPED_SAMPLES_NEEDED] before the countdown starts —
     *  prevents false triggers at red lights or in slow traffic. */
    private var stoppedSampleCount = 0

    /** Number of consecutive stopped samples required before arming the cooldown.
     *  OBD2 polls every ~1.5 s → 5 samples ≈ 7–8 seconds stopped. */
    private const val COOLDOWN_STOPPED_SAMPLES_NEEDED = 5

    /** Minimum time since the previous cooldown before a new one may arm.
     *  Prevents a burst of start/cancel notifications in stop-and-go traffic
     *  (which used to hammer SystemUI and freeze the launcher). */
    private const val COOLDOWN_REARM_GUARD_MS = 120_000L

    /** Running cooldown coroutine (null when inactive). */
    private var cooldownJob: Job? = null

    /** Timestamp of the last cooldown start/cancel — see [COOLDOWN_REARM_GUARD_MS]. */
    private var lastCooldownActivityMs = 0L

    /** Dedicated scope for the cooldown timer. Survives across data updates. */
    private val cooldownScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Notification callbacks ────────────────────────────────────────────────
    /**
     * Lambda invoked by the repository whenever the [RegenStatus] changes.
     * Set by [DpfForegroundService] to trigger notifications without a hard
     * dependency on Android context from within BleManager.
     */
    var onRegenStatusChanged: ((oldStatus: RegenStatus, newStatus: RegenStatus) -> Unit)? = null

    /**
     * Lambda invoked on regen lifecycle events for history recording.
     * Events: "STARTED", "DATA_POINT", "COMPLETED", "INTERRUPTED"
     * Set by [DpfForegroundService] which owns [RegenHistoryRepository].
     */
    var onRegenSessionEvent: ((event: String, data: DpfData) -> Unit)? = null

    // ── Turbo cooldown callbacks (set by DpfForegroundService) ───────────────
    /** Fired once when the 45-second cooldown starts. */
    var onCooldownStarted: (() -> Unit)? = null
    /** Fired once when the 45-second countdown reaches zero. */
    var onCooldownComplete: (() -> Unit)? = null
    /** Fired if the driver moved the car before the countdown finished. */
    var onCooldownCancelled: (() -> Unit)? = null

    // ═════════════════════════════════════════════════════════════════════════
    // Initialisation
    // ═════════════════════════════════════════════════════════════════════════

    /** Must be called once from Application.onCreate(). */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Seed the odometer from the last persisted value so maintenance km are
        // available immediately — before the first ECU read and while offline.
        // Distance counters (kmSinceLastRegen, kmSinceOilChange) stay live-only.
        lastGoodOdometerKm = prefs.getLong(KEY_LAST_ODO, 0L)
        lastRegenEndKm = prefs.getLong(KEY_LAST_REGEN_END, -1L)
        if (lastGoodOdometerKm > 0L) {
            _dpfData.value = _dpfData.value.copy(
                odometerKm = lastGoodOdometerKm,
                kmSinceRegenKm = sinceRegen(lastGoodOdometerKm)
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public update methods — called by BleManager on every parsed response
    // ═════════════════════════════════════════════════════════════════════════

    fun updateSoot(percentage: Float) {
        _dpfData.value = _dpfData.value.copy(sootPercentage = percentage)
        reevaluateRegen()   // a falling soot % is itself a regen signal
    }

    fun updateLoad(percentage: Float) {
        _dpfData.value = _dpfData.value.copy(loadPercentage = percentage)
    }

    fun updateCoolantTemp(celsius: Float) {
        _dpfData.value = _dpfData.value.copy(coolantTempC = celsius)
    }

    /** Updates the inlet EGT reading and re-evaluates the regen status. */
    fun updateEgt(celsius: Float) {
        _dpfData.value = _dpfData.value.copy(egtCelsius = celsius)
        reevaluateRegen()
    }

    /**
     * Updates the DPF differential pressure reading from PID 01 7A.
     * [kPa] = (256×B + C) / 100 per SAE J1979. Normal: ~0 at idle, 5–15 at cruise.
     */
    fun updateDpfDeltaPressure(kPa: Float) {
        _dpfData.value = _dpfData.value.copy(dpfDeltaPressureKpa = kPa)
    }

    /**
     * Updates the odometer with glitch filtering:
     *   • ignores zero/garbage reads,
     *   • accepts normal forward progress (0..[ODO_MAX_STEP_KM]),
     *   • requires a backward move or a big forward jump to repeat before trusting
     *     it (filters one-off parser glitches; self-heals a bad persisted value),
     *   • persists the last good value so it survives disconnects/restarts.
     */
    fun updateOdometer(km: Long) {
        if (km <= 0L) return   // no data / garbage

        val last = lastGoodOdometerKm
        if (last == 0L || (km >= last && km <= last + ODO_MAX_STEP_KM)) {
            acceptOdometer(km)
            return
        }

        // Anomalous (backward, or forward jump beyond the plausible step):
        // accept only after two consecutive close readings.
        if (kotlin.math.abs(km - pendingOdometerKm) <= ODO_CONFIRM_TOL_KM) {
            if (++pendingOdometerCount >= ODO_JUMP_CONFIRM) acceptOdometer(km)
        } else {
            pendingOdometerKm = km
            pendingOdometerCount = 1
        }
    }

    /** ECU's own "distance since last DPF regen" (22 0614); -1 until it has been read. */
    private var ecuKmSinceRegen: Long = -1L

    /** Prefer the ECU counter; otherwise odometer minus odometer at the last real regen. */
    private fun sinceRegen(odometerKm: Long): Long = when {
        ecuKmSinceRegen >= 0L -> ecuKmSinceRegen
        lastRegenEndKm in 1..odometerKm -> odometerKm - lastRegenEndKm
        else -> -1L
    }

    /** Updates km since the last DPF regen read directly from the ECU (PID 22 0614). */
    fun updateKmSinceRegenEcu(km: Long) {
        ecuKmSinceRegen = km
        _dpfData.value = _dpfData.value.copy(kmSinceRegenKm = km)
    }

    /** Called when a regen finishes (odometer at that moment); resets "km since last regen". */
    fun setLastRegenEnd(odometerKm: Long) {
        if (odometerKm <= 0L) return
        lastRegenEndKm = odometerKm
        if (::prefs.isInitialized) prefs.edit().putLong(KEY_LAST_REGEN_END, odometerKm).apply()
        val odo = _dpfData.value.odometerKm
        _dpfData.value = _dpfData.value.copy(kmSinceRegenKm = sinceRegen(odo))
    }

    private fun acceptOdometer(km: Long) {
        lastGoodOdometerKm = km
        pendingOdometerKm = 0L
        pendingOdometerCount = 0
        if (::prefs.isInitialized) prefs.edit().putLong(KEY_LAST_ODO, km).apply()
        _dpfData.value = _dpfData.value.copy(odometerKm = km, kmSinceRegenKm = sinceRegen(km))
    }

    /** Updates km travelled since last DPF regeneration (PID 22 050B). */
    fun updateKmSinceLastRegen(km: Long) {
        _dpfData.value = _dpfData.value.copy(kmSinceLastRegen = km)
    }

    /** Updates km travelled since last oil change (PID 22 0542). */
    fun updateKmSinceOilChange(km: Long) {
        _dpfData.value = _dpfData.value.copy(kmSinceOilChange = km)
    }

    /** Updates engine RPM (PID 01 0C). Formula: ((A*256)+B)/4. */
    fun updateRpm(rpm: Float) {
        _dpfData.value = _dpfData.value.copy(rpmValue = rpm)
    }

    /** Updates vehicle speed in km/h (PID 01 0D). */
    fun updateSpeed(kmh: Float) {
        _dpfData.value = _dpfData.value.copy(speedKmh = kmh)
        // Arm the cooldown only once the car has actually moved
        if (kmh > COOLDOWN_ARM_SPEED_KMH) wasMoving = true
        // Cancel countdown if the driver moves again during the 45s window
        if (kmh > COOLDOWN_STOPPED_KMH && cooldownJob?.isActive == true) {
            resetCooldown(fireCancelledCallback = true)
            return
        }
        checkAndStartCooldown(_dpfData.value)
    }

    /** Updates engine load % (PID 01 04). Formula: A*100/255. */
    fun updateEngineLoad(pct: Float) {
        _dpfData.value = _dpfData.value.copy(engineLoadPct = pct)
    }

    /** Updates intake MAP in kPa (PID 01 0B). Subtract baroKpa for boost delta. */
    fun updateIntakeMap(kpa: Float) {
        _dpfData.value = _dpfData.value.copy(intakeMapKpa = kpa)
    }

    /** Updates ambient barometric pressure in kPa (PID 01 33). */
    fun updateBaroPressure(kpa: Float) {
        _dpfData.value = _dpfData.value.copy(baroKpa = kpa)
    }

    /** Updates engine oil temperature in °C (PID 01 5C). */
    fun updateOilTemp(celsius: Float) {
        _dpfData.value = _dpfData.value.copy(oilTempC = celsius)
    }

    /** Updates post-DPF EGT sensor 2 in °C (from PID 01 78 byte pair 5–6).
     *  Feeds the exotherm signal (outlet − inlet) of the regen detector. */
    fun updateEgtPost(celsius: Float) {
        _dpfData.value = _dpfData.value.copy(egtPostDpfC = celsius)
        reevaluateRegen()
    }

    /** Updates control module / battery voltage in V (PID 01 42). */
    fun updateBatteryVoltage(volts: Float) {
        _dpfData.value = _dpfData.value.copy(batteryVoltage = volts)
    }

    /** Updates fuel rail pressure in kPa (PID 01 23 / 01 59). */
    fun updateFuelRailPressure(kpa: Float) {
        _dpfData.value = _dpfData.value.copy(fuelRailPressureKpa = kpa)
    }

    /** Updates intake air temperature in °C (PID 01 0F). */
    fun updateIntakeAirTemp(celsius: Float) {
        _dpfData.value = _dpfData.value.copy(intakeAirTempC = celsius)
    }

    fun updateBleConnected(connected: Boolean) {
        _dpfData.value = _dpfData.value.copy(bleConnected = connected)
        if (!connected) {
            resetCooldown(fireCancelledCallback = false)  // reset on disconnect, no notification
            regenEvaluator.reset()   // clear soot history so a reconnect can't false-trigger
        }
    }

    /**
     * Re-runs the multi-signal regen detector on the current data snapshot and
     * applies any resulting status/strategy change. Called after every EGT, soot
     * or outlet-EGT update so all three detection signals stay current.
     */
    private fun reevaluateRegen() {
        val previous = _dpfData.value
        val result = regenEvaluator.evaluate(
            RegenSample(
                egtPre      = previous.egtCelsius,
                egtPost     = previous.egtPostDpfC,
                soot        = previous.sootPercentage,
                coolant     = previous.coolantTempC,
                timestampMs = System.currentTimeMillis()
            ),
            previous.regenStatus
        )
        val updated = previous.copy(
            regenStatus   = result.status,
            regenStrategy = result.strategy
        )
        applyStatusChange(previous, updated)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Applies the updated [DpfData], fires [onRegenStatusChanged] if the status
     * changed, and records the last-regen km when a regeneration completes.
     */
    private fun applyStatusChange(previous: DpfData, updated: DpfData) {
        _dpfData.value = updated

        if (previous.regenStatus != updated.regenStatus) {
            onRegenStatusChanged?.invoke(previous.regenStatus, updated.regenStatus)

            // Fire history session events on state transitions.
            // "STARTED" fires only on ACTIVE (EGT>550°C sustained ~10 s, confirmed) —
            // NOT on WARNING, to avoid false entries from hard acceleration spikes.
            when {
                updated.regenStatus == RegenStatus.ACTIVE &&
                    previous.regenStatus != RegenStatus.ACTIVE ->
                    onRegenSessionEvent?.invoke("STARTED", updated)

                // Regen completed successfully
                updated.regenStatus == RegenStatus.COMPLETED ->
                    onRegenSessionEvent?.invoke("COMPLETED", updated)

                // Regen interrupted (went straight from ACTIVE/WARNING to INACTIVE without COMPLETED)
                updated.regenStatus == RegenStatus.INACTIVE &&
                    (previous.regenStatus == RegenStatus.ACTIVE ||
                     previous.regenStatus == RegenStatus.WARNING) ->
                    onRegenSessionEvent?.invoke("INTERRUPTED", updated)
            }
        }

        // Emit periodic data points while regen is active
        if (updated.regenStatus == RegenStatus.ACTIVE) {
            onRegenSessionEvent?.invoke("DATA_POINT", updated)
        }
    }

    /** Resets COMPLETED back to INACTIVE after it has been acknowledged/shown. */
    fun acknowledgeCompleted() {
        if (_dpfData.value.regenStatus == RegenStatus.COMPLETED) {
            _dpfData.value = _dpfData.value.copy(
                regenStatus   = RegenStatus.INACTIVE,
                regenStrategy = RegenStrategy.NONE
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Turbo cooldown — private helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called after every EGT or load update.
     * Starts the cooldown countdown when ALL conditions are met:
     *  • Engine was "hot" during this trip (EGT > 150°C or load > 25%)
     *  • Engine is now at idle (load < 12%, RPM in 500–1300)
     *  • No active regeneration in progress
     *  • Cooldown is not already running
     */
    private fun checkAndStartCooldown(data: DpfData) {
        if (!wasMoving) return                          // car never actually moved this session
        if (cooldownJob != null) return                 // already running
        if (data.regenStatus == RegenStatus.ACTIVE ||
            data.regenStatus == RegenStatus.WARNING) return  // don't interrupt regen

        // "At idle" = engine running in idle RPM range AND vehicle stopped.
        // Speed is the reliable trigger — diesel OBD2 load reads 25-35% even at idle.
        val engineAtIdle = data.rpmValue in COOLDOWN_IDLE_RPM_RANGE
        val vehicleStopped = data.speedKmh in 0f..COOLDOWN_STOPPED_KMH

        if (engineAtIdle && vehicleStopped) {
            stoppedSampleCount++
            val guardPassed =
                System.currentTimeMillis() - lastCooldownActivityMs >= COOLDOWN_REARM_GUARD_MS
            if (stoppedSampleCount >= COOLDOWN_STOPPED_SAMPLES_NEEDED && guardPassed) startCooldown()
        } else {
            stoppedSampleCount = 0   // moved again (green light, traffic) → reset
        }
    }

    /** Fires [onCooldownStarted] callback, waits [COOLDOWN_DURATION_MS], then fires
     *  [onCooldownComplete]. No StateFlow updates — cooldown lives entirely in
     *  background notifications managed by DpfForegroundService. */
    private fun startCooldown() {
        lastCooldownActivityMs = System.currentTimeMillis()
        onCooldownStarted?.invoke()
        cooldownJob = cooldownScope.launch {
            delay(COOLDOWN_DURATION_MS)
            onCooldownComplete?.invoke()
            delay(15_000L)   // give service time to show "safe to stop" notif, then reset
            resetCooldown(fireCancelledCallback = false)
        }
    }

    /**
     * Cancels any running countdown and clears all cooldown state.
     * [fireCancelledCallback] = true → notify the driver the countdown was cancelled
     * (e.g. car moved again). false → silent reset (BLE disconnect, or after completion).
     */
    private fun resetCooldown(fireCancelledCallback: Boolean = true) {
        val wasActive = cooldownJob?.isActive == true
        cooldownJob?.cancel()
        cooldownJob = null
        wasMoving = false
        stoppedSampleCount = 0
        // Extend the re-arm guard from the moment a cooldown ends/cancels.
        if (wasActive) lastCooldownActivityMs = System.currentTimeMillis()
        if (fireCancelledCallback && wasActive) {
            onCooldownCancelled?.invoke()
        }
    }
}
