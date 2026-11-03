package com.example.fordfocusdpfscan.service

import com.example.fordfocusdpfscan.i18n.tr
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.fordfocusdpfscan.ble.BleManager
import com.example.fordfocusdpfscan.ble.BleManagerHolder
import com.example.fordfocusdpfscan.ble.RegenStateCapture
import com.example.fordfocusdpfscan.car.NotificationHelper
import com.example.fordfocusdpfscan.data.ConnectionState
import com.example.fordfocusdpfscan.data.DpfData
import com.example.fordfocusdpfscan.data.DpfRepository
import com.example.fordfocusdpfscan.data.EcuScanRepository
import com.example.fordfocusdpfscan.data.MaintenanceRepository
import com.example.fordfocusdpfscan.data.RegenHistoryRepository
import com.example.fordfocusdpfscan.data.RegenStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════════
// DpfForegroundService.kt — Foreground Service that owns the BLE connection.
//
// Responsibilities:
//   1. Keep the BLE GATT connection alive when the phone screen is off or the
//      user switches apps (foreground services are not killed by the OS).
//   2. Show the persistent "monitoring" notification (Level 1 in our stack).
//   3. React to DpfRepository state changes to fire event notifications
//      (Level 2 heads-up) via NotificationHelper.
//   4. Expose the BleManager instance so MainActivity can trigger reconnects.
//
// Lifecycle:
//   Started by MainActivity on "Connect" → keeps running until "Disconnect"
//   or the user explicitly stops the app.
// ═══════════════════════════════════════════════════════════════════════════════

class DpfForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "FOCUS_Service"

        // Intent actions used to control the service from MainActivity
        const val ACTION_CONNECT         = "com.example.fordfocusdpfscan.CONNECT"
        const val ACTION_DISCONNECT      = "com.example.fordfocusdpfscan.DISCONNECT"
        const val ACTION_RECONNECT       = "com.example.fordfocusdpfscan.RECONNECT"
        /** Connect to a specific device by MAC address (from the device picker). */
        const val ACTION_CONNECT_ADDRESS = "com.example.fordfocusdpfscan.CONNECT_ADDRESS"
        const val EXTRA_DEVICE_ADDRESS   = "extra_device_address"
    }

    /** The BLE manager instance — created once per service lifetime. */
    val bleManager: BleManager by lazy { BleManager(applicationContext) }

    /** Regen history repository — owns the Room DB and the HTML export. */
    private val historyRepo: RegenHistoryRepository by lazy {
        RegenHistoryRepository(applicationContext)
    }

    /** Maintenance reminders repository. */
    private val maintenanceRepo: MaintenanceRepository by lazy {
        MaintenanceRepository(applicationContext)
    }

    /** Throttle DATA_POINT events: only record one sample every 30 seconds. */
    private var lastDataPointTime = 0L
    private val DATA_POINT_INTERVAL_MS = 30_000L

    // ── Persistent notification throttle ──────────────────────────────────────
    /** Update the persistent notification at most every 5 seconds. The shown
     *  data (soot %, EGT) changes slowly — no need for 1-second refreshes, which
     *  cause the notification drawer to re-render continuously and lag. */
    private var lastNotifUpdateTime = 0L
    private val NOTIF_UPDATE_INTERVAL_MS = 5_000L

    /** Last summary string posted — skip notify() if the content hasn't changed. */
    private var lastNotifSummary = ""

    // ── Maintenance checks ────────────────────────────────────────────────────
    /** Last odometer km at which we ran a maintenance notification check.
     *  Only re-check when the odometer advances by at least 1 km. */
    private var lastMaintenanceCheckKm = -1L

    /** Last values used to sync the auto-managed tagliando.
     *  DB write happens only when odo or oilKm actually changes. */
    private var lastSyncOdo   = -1L
    private var lastSyncOilKm = -1L

    // ═════════════════════════════════════════════════════════════════════════
    // Service lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Expose bleManager via the holder so EcuScanActivity can reach it
        // without needing to bind to this service.
        BleManagerHolder.instance = bleManager

        // Wire up the repository → notification callback BEFORE starting BLE
        DpfRepository.onRegenStatusChanged = { old, new ->
            onRegenStatusChanged(old, new)
        }

        // Wire up regen session events → history repository
        DpfRepository.onRegenSessionEvent = { event, data ->
            onRegenSessionEvent(event, data)
        }

        // Wire up turbo cooldown → background notifications
        DpfRepository.onCooldownStarted = {
            NotificationHelper.notifyCooldownStarted(this)
        }
        DpfRepository.onCooldownComplete = {
            NotificationHelper.notifyCooldownComplete(this)
        }
        DpfRepository.onCooldownCancelled = {
            NotificationHelper.cancelCooldownNotification(this)
        }

        // Show the persistent foreground notification immediately
        // (required before any other work on Android 8+)
        startForeground(
            NotificationHelper.NOTIF_ID_PERSISTENT,
            NotificationHelper.buildPersistentNotification(this)
        )

        // Observe DpfData changes to keep the persistent notification updated
        observeDpfData()

        // Observe BLE connection state to update the persistent notification
        observeConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT    -> bleManager.connect()
            ACTION_CONNECT_ADDRESS -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address != null) bleManager.connectByAddress(address)
                else bleManager.connect()
            }
            ACTION_DISCONNECT -> {
                bleManager.disconnect()
                stopSelf()
            }
            ACTION_RECONNECT  -> bleManager.reconnect()
        }
        return START_STICKY  // Restart the service if killed by the OS
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
        BleManagerHolder.instance = null
        DpfRepository.onRegenStatusChanged  = null
        DpfRepository.onRegenSessionEvent   = null
        DpfRepository.onCooldownStarted     = null
        DpfRepository.onCooldownComplete    = null
        DpfRepository.onCooldownCancelled   = null
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null  // Not a bound service
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Observers
    // ═════════════════════════════════════════════════════════════════════════

    /** Refreshes the persistent notification and triggers maintenance checks on data changes. */
    private fun observeDpfData() {
        lifecycleScope.launch {
            DpfRepository.dpfData.collectLatest { data ->
                // ── Fix: throttle + content check to stop notification drawer lag ──
                // Each OBD poll cycle emits 14+ StateFlow updates. Without throttling
                // we'd call notify() ~10×/second, causing continuous re-renders in the
                // notification shade. We now update at most every 5s AND only when the
                // displayed content actually changed.
                val now = System.currentTimeMillis()
                if (now - lastNotifUpdateTime >= NOTIF_UPDATE_INTERVAL_MS) {
                    val newSummary = buildPersistentSummaryKey(data)
                    if (newSummary != lastNotifSummary) {
                        lastNotifSummary    = newSummary
                        lastNotifUpdateTime = now
                        val updatedNotif = NotificationHelper.buildPersistentNotification(
                            context = this@DpfForegroundService,
                            dpfData = data
                        )
                        NotificationHelper.updatePersistentNotification(
                            this@DpfForegroundService,
                            updatedNotif
                        )
                    }
                }

                // ── Maintenance: sync auto-managed tagliando from ECU ─────────
                // Only hits the DB when odo or oilKm actually changes (not every poll).
                val odo   = data.odometerKm
                val oilKm = data.kmSinceOilChange
                if (odo > 0L && oilKm >= 0L &&
                    (odo != lastSyncOdo || oilKm != lastSyncOilKm)) {
                    lastSyncOdo   = odo
                    lastSyncOilKm = oilKm
                    launch { maintenanceRepo.syncAutoManagedTagliando(odo, oilKm) }
                }

                // ── Maintenance: check reminders when odometer advances ────────
                if (odo > 0L && odo != lastMaintenanceCheckKm) {
                    lastMaintenanceCheckKm = odo
                    checkMaintenanceNotifications(odo)
                }
            }
        }
    }

    /**
     * Builds a compact string key from the values shown in the persistent notification.
     * notify() is only called when this key changes — avoids useless re-renders.
     */
    private fun buildPersistentSummaryKey(data: DpfData): String {
        val connected = if (data.bleConnected) "1" else "0"
        val soot = data.sootPercentage.toInt().toString()
        val egt  = data.egtCelsius.toInt().toString()
        return "$connected|$soot|$egt"
    }

    /**
     * Checks all maintenance reminders against the current odometer and fires
     * notifications when thresholds are crossed. Each notification fires only once
     * per maintenance interval (flags stored in DB).
     */
    private fun checkMaintenanceNotifications(odometerKm: Long) {
        lifecycleScope.launch {
            val reminders = maintenanceRepo.getAll()
            reminders.forEach { reminder ->
                val kmLeft = reminder.kmRemaining(odometerKm)
                when {
                    // Overdue
                    kmLeft < 0 && !reminder.notifOverdueSent -> {
                        NotificationHelper.notifyMaintenanceOverdue(
                            this@DpfForegroundService, reminder
                        )
                        maintenanceRepo.setNotifOverdueSent(reminder.id)
                    }
                    // Urgent (< 500 km)
                    kmLeft in 0..499 && !reminder.notif500Sent -> {
                        NotificationHelper.notifyMaintenanceUrgent(
                            this@DpfForegroundService, reminder, kmLeft
                        )
                        maintenanceRepo.setNotif500Sent(reminder.id)
                    }
                    // Warning (< 1000 km)
                    kmLeft in 500..999 && !reminder.notif1000Sent -> {
                        NotificationHelper.notifyMaintenanceWarning(
                            this@DpfForegroundService, reminder, kmLeft
                        )
                        maintenanceRepo.setNotif1000Sent(reminder.id)
                    }
                }
            }
        }
    }

    /** Fires silent BLE connection/disconnection notifications. */
    private fun observeConnectionState() {
        lifecycleScope.launch {
            var previousState: ConnectionState? = null
            bleManager.connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.DISCONNECTED -> {
                        if (previousState == ConnectionState.CONNECTED) {
                            NotificationHelper.notifyBleLost(this@DpfForegroundService)
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        val deviceName = bleManager.connectedDeviceName ?: tr("Adapter OBD")
                        NotificationHelper.notifyBleConnected(this@DpfForegroundService, deviceName)
                    }
                    else -> { /* SCANNING / CONNECTING — no notification */ }
                }
                previousState = state
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Regen status change handler
    // ═════════════════════════════════════════════════════════════════════════

    // ── Regen event notification debounce ─────────────────────────────────────
    /** Suppress a repeat of the SAME regen event notification within this window —
     *  defence-in-depth against status flapping hammering SystemUI/the launcher
     *  (the state machine's hysteresis already prevents flapping at the source). */
    private val REGEN_EVENT_DEBOUNCE_MS = 60_000L
    private var lastNotifiedStatus: RegenStatus? = null
    private var lastNotifiedTime = 0L

    // ── Regen ECU-state capture guards ────────────────────────────────────────
    /** True while a capture sweep is running — prevents overlapping captures. */
    private var regenCaptureInProgress = false
    /** True once we've captured the ACTIVE state for the current regen episode.
     *  Reset to false when the regen ends (INACTIVE) so the next regen re-arms. */
    private var capturedActiveThisRegen = false

    /**
     * Called by DpfRepository on every status transition.
     * Fires the appropriate heads-up notification + MP3 sound for regen events,
     * and auto-captures the ECU 05xx state so we can locate the regen-status flag.
     */
    private fun onRegenStatusChanged(old: RegenStatus, new: RegenStatus) {
        Log.d(TAG, "Regen status: $old → $new")

        if (new == RegenStatus.INACTIVE) {
            capturedActiveThisRegen = false   // re-arm capture for the next regen
            return                            // INACTIVE never notifies
        }

        // Skip a repeat of the same event within the debounce window.
        val now = System.currentTimeMillis()
        if (new == lastNotifiedStatus && now - lastNotifiedTime < REGEN_EVENT_DEBOUNCE_MS) {
            Log.d(TAG, "Regen event debounced ($new)")
            return
        }
        lastNotifiedStatus = new
        lastNotifiedTime = now

        when (new) {
            RegenStatus.WARNING   -> NotificationHelper.notifyWarning(this)
            RegenStatus.ACTIVE    -> {
                NotificationHelper.notifyActive(this)
                if (!capturedActiveThisRegen) {
                    capturedActiveThisRegen = true
                    captureRegenState(tr("REGEN AKTYWNA"))
                }
            }
            RegenStatus.COMPLETED -> {
                NotificationHelper.notifyCompleted(this)
                captureRegenState(tr("REGEN ZAKOŃCZONA"))
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(8_000)
                    DpfRepository.acknowledgeCompleted()
                }
            }
            RegenStatus.INACTIVE  -> { /* handled above */ }
        }
    }

    /**
     * Sweeps the ECU 22 05xx range and appends it to the capture log so we can
     * diff active-regen vs idle and pin down the regen-status PID. Runs off the
     * main flow and is skipped if a capture (or a manual ECU scan) is already
     * running — both pause the shared BLE polling loop.
     */
    private fun captureRegenState(label: String) {
        if (regenCaptureInProgress) return
        if (EcuScanRepository.scanState.value.isRunning) return
        regenCaptureInProgress = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = RegenStateCapture(applicationContext, bleManager).captureNow(label)
                // 0 PIDs means the dongle dropped mid-sweep — don't notify then.
                if (count > 0) {
                    NotificationHelper.notifyRegenCaptured(this@DpfForegroundService, count)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Regen capture failed: ${e.message}")
            } finally {
                regenCaptureInProgress = false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Regen history recording
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Routes regen session events from DpfRepository to RegenHistoryRepository.
     * DATA_POINT events are throttled to one sample every 30 seconds.
     */
    private fun onRegenSessionEvent(event: String, data: DpfData) {
        lifecycleScope.launch {
            when (event) {
                "STARTED" -> historyRepo.onRegenStarted(data)

                "DATA_POINT" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastDataPointTime >= DATA_POINT_INTERVAL_MS) {
                        lastDataPointTime = now
                        historyRepo.onRegenDataPoint(data)
                    }
                }

                "COMPLETED"   -> historyRepo.onRegenEnded(data, "COMPLETED")
                "INTERRUPTED" -> historyRepo.onRegenEnded(data, "INTERRUPTED")
            }
        }
    }
}
