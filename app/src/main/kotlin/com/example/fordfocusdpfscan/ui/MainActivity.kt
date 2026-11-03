package com.example.fordfocusdpfscan.ui

import com.example.fordfocusdpfscan.i18n.tr
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.data.DpfData
import com.example.fordfocusdpfscan.data.DpfRepository
import com.example.fordfocusdpfscan.data.RegenStatus
import com.example.fordfocusdpfscan.databinding.ActivityMainBinding
import com.example.fordfocusdpfscan.service.DpfForegroundService
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════════
// MainActivity.kt — Phone UI entry point.
//
// Responsibilities:
//   1. Request BLE + notification permissions.
//   2. Start/stop the DpfForegroundService (which owns the BleManager).
//   3. Display live DPF data from DpfRepository.dpfData StateFlow.
//   4. Allow the user to manually enter the last service km.
//
// The actual BLE scanning and OBD2 polling happen inside DpfForegroundService
// and BleManager — this activity is only the control panel and display.
// ═══════════════════════════════════════════════════════════════════════════════

class MainActivity : BaseTabActivity() {

    override val tabIndex = 0

    private lateinit var binding: ActivityMainBinding

    // ── Device picker state ───────────────────────────────────────────────────
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var bleScanCallback: ScanCallback? = null
    private var devicePickerDialog: AlertDialog? = null
    private val scanStopHandler = Handler(Looper.getMainLooper())

    /** Last connection state applied to the connection card. Used so the card
     *  (and its accordion) is only touched on a real connect/disconnect
     *  transition — NOT on every DpfData emission, which would otherwise slam
     *  the accordion shut ~10×/second the instant the user expands it. */
    private var lastConnectedState: Boolean? = null

    // ── SharedPreferences key for last connected device ───────────────────────
    private val PREFS_NAME = "focus_prefs"
    private val KEY_LAST_ADDRESS = "last_device_address"
    private val KEY_LAST_NAME = "last_device_name"

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startBleService()
        } else {
            Toast.makeText(this,
                tr("Do połączenia z adapterem OBD wymagane są uprawnienia Bluetooth."),
                Toast.LENGTH_LONG).show()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        setupGauges()
        setupFooter()
        observeDpfData()
        observeConnectionState()
    }

    /**
     * Auto-connect when the app comes to the foreground, IF:
     *   • All required BT/notification permissions are already granted
     *   • A device address was saved from a previous session
     *   • The service is not already connected (BleManagerHolder.instance == null
     *     means the service hasn't been started yet in this process)
     *
     * This avoids the extra button-press the user had to do every time.
     */
    override fun onStart() {
        super.onStart()
        autoConnectIfNeeded()
    }

    private fun autoConnectIfNeeded() {
        // Only auto-connect if permissions are already granted — don't prompt here
        if (!hasRequiredPermissions()) return
        // If BLE is already CONNECTED (not just service running), skip
        val existingManager = com.example.fordfocusdpfscan.ble.BleManagerHolder.instance
        if (existingManager != null &&
            existingManager.connectionState.value == com.example.fordfocusdpfscan.data.ConnectionState.CONNECTED) return
        // If no device was ever saved, nothing to connect to
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAddress = prefs.getString(KEY_LAST_ADDRESS, null) ?: return
        val lastName    = prefs.getString(KEY_LAST_NAME, lastAddress) ?: lastAddress

        Log.d("FOCUS_Main", "Auto-connecting to $lastName ($lastAddress)…")
        Toast.makeText(this,
            tr("Ponowne łączenie z %s…").format(lastName),
            Toast.LENGTH_SHORT).show()
        connectToDeviceAddress(lastAddress)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PieChart gauge helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * One-time configuration for a PieChart used as a donut gauge.
     * The chart has no labels, legend or description — only a coloured arc.
     * The hole is transparent so the card background shows through.
     */
    private fun setupGauges() {
        listOf(binding.chartDpfLoad, binding.chartDpfSoot).forEach { chart ->
            chart.apply {
                holeRadius              = 74f          // large hole = thin donut ring
                transparentCircleRadius = 0f
                setHoleColor(android.graphics.Color.TRANSPARENT)
                setDrawEntryLabels(false)
                description.isEnabled  = false
                legend.isEnabled       = false
                isRotationEnabled      = false
                setTouchEnabled(false)
                minOffset              = 0f
                setDrawCenterText(false)
                // Draw grey placeholder ring so the gauge looks "full" from the start
                updateGauge(this, 0f, 320f, getColor(R.color.status_ok))
            }
        }
    }

    /**
     * Updates a donut gauge with [value] on a [maxForDisplay] scale.
     * Values beyond [maxForDisplay] are capped visually (arc goes full circle)
     * but the numeric label in the overlay TextView is not capped — caller sets that.
     *
     * @param chart        The PieChart to update.
     * @param value        Raw sensor value (e.g. loadPercentage 0–320).
     * @param maxForDisplay Upper bound that maps to 100 % arc (e.g. 320 for DPF load/soot).
     * @param fillColor    ARGB int for the filled arc.
     */
    private fun updateGauge(chart: PieChart, value: Float, maxForDisplay: Float, fillColor: Int) {
        val fillPct     = if (value < 0f) 0f else (value / maxForDisplay * 100f).coerceIn(0f, 100f)
        val emptyPct    = 100f - fillPct
        val emptyColor  = 0xFF1A1A2A.toInt()   // AMOLED track — dark but visible on true black

        val entries = listOf(PieEntry(fillPct), PieEntry(emptyPct))
        val dataSet = PieDataSet(entries, "").apply {
            colors      = listOf(fillColor, emptyColor)
            setDrawValues(false)
            sliceSpace  = 0f
        }
        chart.data = PieData(dataSet)
        chart.invalidate()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI setup
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupFooter() {
        // Version — always in sync with build.gradle versionName
        binding.layoutFooter.tvFooterVersion.text = "v${com.example.fordfocusdpfscan.BuildConfig.VERSION_NAME}"

        // GitHub link — opens in browser on tap
        binding.layoutFooter.tvFooterGithub.setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/andreasesuru")
            )
            startActivity(intent)
        }
    }

    private fun setupClickListeners() {
        // ── Accordion header toggle ───────────────────────────────────────────
        binding.layoutConnectionHeader.setOnClickListener {
            val isExpanded = binding.layoutConnectionButtons.visibility == View.VISIBLE
            binding.layoutConnectionButtons.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.tvConnectionChevron.text = if (isExpanded) "▸" else "▾"
        }

        // ── Scan & Connect button ─────────────────────────────────────────────
        binding.btnScan.setOnClickListener {
            if (hasRequiredPermissions()) {
                onScanButtonPressed()
            } else {
                requestRequiredPermissions()
            }
        }

        // ── Disconnect button ─────────────────────────────────────────────────
        binding.btnDisconnect.setOnClickListener {
            val intent = Intent(this, DpfForegroundService::class.java).apply {
                action = DpfForegroundService.ACTION_DISCONNECT
            }
            startService(intent)
        }

        // ── ECU PID Scanner ───────────────────────────────────────────────────
        binding.btnScanEcu.setOnClickListener {
            startActivity(Intent(this, EcuScanActivity::class.java))
        }

        // ── Fault-code reader (DTC) ─────────────────────────────────────────────
        binding.btnReadDtc.setOnClickListener {
            startActivity(Intent(this, DtcActivity::class.java))
        }

        // ── AI Assistant (chat) ───────────────────────────────────────────────
        binding.btnAiAssistant.setOnClickListener {
            startActivity(Intent(this, AiChatActivity::class.java))
        }

        // ── AI Settings ─────────────────────────────────────────────────────────
        binding.btnAiSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Tab bar highlighting + click listeners are handled centrally by BaseTabActivity.

        // Oil change km and last regen km are now read live from ECU —
        // no manual input needed.
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Data observers
    // ═════════════════════════════════════════════════════════════════════════

    /** Observes DpfRepository and updates all UI views on every emission. */
    private fun observeDpfData() {
        lifecycleScope.launch {
            DpfRepository.dpfData.collectLatest { data ->
                updateDpfViews(data)
            }
        }
    }

    /** Observes BLE connection state to update the connection card. */
    private fun observeConnectionState() {
        // We infer connection state from DpfData.bleConnected for simplicity.
        // A more granular approach would observe BleManager.connectionState directly
        // via a bound service — suitable for a future improvement.
        lifecycleScope.launch {
            DpfRepository.dpfData.collectLatest { data ->
                updateConnectionCard(data.bleConnected)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // View update helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateDpfViews(data: DpfData) {
        // ── DPF Load — PID 22 0579 (closed-loop) ─────────────────────────────
        val loadFill = dpfColor(data.loadPercentage)
        if (data.loadPercentage >= 0) {
            binding.tvDpfLoad.text = "${data.loadPercentage.toInt()}"
            binding.tvDpfLoad.setTextColor(loadFill)
            binding.tvDpfNote.text = ""
        } else {
            binding.tvDpfLoad.text = "—"
            binding.tvDpfLoad.setTextColor(getColor(R.color.text_secondary))
            binding.tvDpfNote.text = ""
        }
        updateGauge(binding.chartDpfLoad, data.loadPercentage, 320f, loadFill)

        // ── DPF Soot — PID 22 057B (open-loop) ───────────────────────────────
        val sootFill = dpfColor(data.sootPercentage)
        if (data.sootPercentage >= 0) {
            binding.tvDpfSoot.text = "${data.sootPercentage.toInt()}"
            binding.tvDpfSoot.setTextColor(sootFill)
        } else {
            binding.tvDpfSoot.text = "—"
            binding.tvDpfSoot.setTextColor(getColor(R.color.text_secondary))
        }
        updateGauge(binding.chartDpfSoot, data.sootPercentage, 320f, sootFill)

        // ── DPF Differential Pressure (01 7A — dato reale confermato) ─────────
        if (data.dpfDeltaPressureKpa >= 0f) {
            val kPa = data.dpfDeltaPressureKpa
            binding.tvDpfDeltaP.text = "${"%.2f".format(kPa)} kPa"
            // Color thresholds based on typical DPF behaviour:
            // <8 kPa = OK (green), 8–15 kPa = attention (orange), >15 kPa = possible blockage (red)
            binding.tvDpfDeltaP.setTextColor(
                when {
                    kPa >= 15f -> getColor(R.color.status_danger)
                    kPa >= 8f  -> getColor(R.color.status_warning)
                    else       -> getColor(R.color.status_ok)
                }
            )
        } else {
            binding.tvDpfDeltaP.text = tr(getString(R.string.value_no_data))
            binding.tvDpfDeltaP.setTextColor(getColor(R.color.text_secondary))
        }

        // ── Regen Status ──────────────────────────────────────────────────────
        val (regenText, regenColor) = when (data.regenStatus) {
            RegenStatus.INACTIVE  -> Pair(tr(getString(R.string.regen_inactive)),
                getColor(R.color.status_ok))
            RegenStatus.WARNING   -> Pair(tr(getString(R.string.regen_warning)),
                getColor(R.color.status_warning))
            RegenStatus.ACTIVE    -> Pair(tr(getString(R.string.regen_active)),
                getColor(R.color.status_danger))
            RegenStatus.COMPLETED -> Pair(tr(getString(R.string.regen_completed)),
                getColor(R.color.status_ok))
        }
        binding.tvRegenStatus.text = regenText
        binding.tvRegenStatus.setTextColor(regenColor)

        // ── EGT ───────────────────────────────────────────────────────────────
        binding.tvEgt.text = if (data.egtCelsius >= 0)
            "${"%.0f".format(data.egtCelsius)} °C"
        else tr(getString(R.string.value_no_data))

        binding.tvEgt.setTextColor(
            when {
                data.egtCelsius >= 550f -> getColor(R.color.status_danger)
                data.egtCelsius >= 450f -> getColor(R.color.status_warning)
                else                    -> getColor(R.color.text_primary)
            }
        )

        // ── Coolant ───────────────────────────────────────────────────────────
        binding.tvCoolant.text = if (data.coolantTempC >= 0)
            "${"%.0f".format(data.coolantTempC)} °C"
        else tr(getString(R.string.value_no_data))

        binding.tvCoolant.setTextColor(
            if (data.coolantTempC > 100f) getColor(R.color.status_danger)
            else getColor(R.color.text_primary)
        )

        // ── Odometer (live from ECU — PID 22 DD01) ───────────────────────────
        binding.tvOdometer.text = if (data.odometerKm > 0L)
            "%,d km".format(data.odometerKm)
        else tr(getString(R.string.value_no_data))

        // ── Km alla prossima rigenerazione (ECU countdown — PID 22 050B) ──────
        binding.tvLastRegen.text = if (data.kmSinceLastRegen >= 0L)
            "za ~%,d km".format(data.kmSinceLastRegen)
        else tr(getString(R.string.value_no_data))

        // ── Km dall'ultimo cambio olio (ECU — PID 22 0542) ────────────────────
        binding.tvOilChange.text = if (data.kmSinceOilChange >= 0L)
            "%,d km".format(data.kmSinceOilChange)
        else tr(getString(R.string.value_no_data))
    }

    private fun updateConnectionCard(connected: Boolean) {
        // Only react to an actual connect/disconnect transition. Without this
        // guard the accordion (layoutConnectionButtons) is force-set on every
        // DpfData emission, re-collapsing it the moment the user expands it.
        if (connected == lastConnectedState) return
        lastConnectedState = connected

        if (connected) {
            binding.tvConnectionStatus.text = tr(getString(R.string.status_connected))
            binding.tvConnectionStatus.setTextColor(getColor(R.color.status_ok))
            binding.btnScan.visibility       = View.GONE
            binding.btnDisconnect.visibility = View.VISIBLE
            binding.btnScanEcu.visibility    = View.VISIBLE
            binding.btnReadDtc.visibility    = View.VISIBLE
            // Auto-collapse accordion: connected = rare to need these buttons
            binding.layoutConnectionButtons.visibility = View.GONE
            binding.tvConnectionChevron.text = "▸"
        } else {
            binding.tvConnectionStatus.text = tr(getString(R.string.status_disconnected))
            binding.tvConnectionStatus.setTextColor(getColor(R.color.status_neutral))
            binding.btnScan.visibility       = View.VISIBLE
            binding.btnDisconnect.visibility = View.GONE
            binding.btnScanEcu.visibility    = View.GONE
            binding.btnReadDtc.visibility    = View.GONE
            // Auto-expand accordion: disconnected = user needs to tap Scan
            binding.layoutConnectionButtons.visibility = View.VISIBLE
            binding.tvConnectionChevron.text = "▾"
        }
    }

    /**
     * Returns the appropriate color for a DPF load/soot percentage (0–320 scale).
     *   <100% = normal (green) — DPF healthy, no regen needed
     *   100–200% = warning (orange) — regen triggered or in progress
     *   >200% = danger (red) — regen blocked, ECU in safe mode
     *   < 0   = muted (no data)
     */
    private fun dpfColor(pct: Float): Int = when {
        pct < 0f   -> getColor(R.color.text_secondary)
        pct < 100f -> getColor(R.color.status_ok)
        pct < 200f -> getColor(R.color.status_warning)
        else       -> getColor(R.color.status_danger)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BLE Service control
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called when the user presses the scan button.
     * If a device was connected before, offers quick-reconnect OR new scan.
     * Otherwise, opens the device picker immediately.
     */
    private fun onScanButtonPressed() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAddress = prefs.getString(KEY_LAST_ADDRESS, null)
        val lastName    = prefs.getString(KEY_LAST_NAME, lastAddress)

        if (lastAddress != null) {
            AlertDialog.Builder(this)
                .setTitle(tr("Połącz z urządzeniem"))
                .setItems(arrayOf(
                    tr("Połącz ponownie: %s").format(lastName),
                    tr("Szukaj nowego urządzenia…")
                )) { _, which ->
                    if (which == 0) connectToDeviceAddress(lastAddress)
                    else showDevicePicker()
                }
                .setNegativeButton(tr("Anuluj"), null)
                .show()
        } else {
            showDevicePicker()
        }
    }

    /** Opens the device picker showing bonded Classic BT devices + BLE scan results. */
    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        discoveredDevices.clear()
        val listItems   = arrayListOf<String>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listItems)

        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Wybierz urządzenie OBD2"))
            .setAdapter(listAdapter) { _, which ->
                val device = discoveredDevices[which]
                stopDeviceScan()
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAST_ADDRESS, device.address)
                    .putString(KEY_LAST_NAME, device.name ?: device.address)
                    .apply()
                connectToDeviceAddress(device.address)
            }
            .setNegativeButton(tr("Anuluj")) { _, _ -> stopDeviceScan() }
            .create()

        devicePickerDialog = dialog
        dialog.show()

        val bleAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        // ── Prima mostra i dispositivi già associati (Android-Vlink) ──────────
        val bonded = bleAdapter?.bondedDevices ?: emptySet()
        bonded.forEach { device ->
            val name = device.name ?: device.address
            if (discoveredDevices.none { it.address == device.address }) {
                discoveredDevices.add(device)
                listItems.add("★ $name  (sparowane)\n${device.address}")
            }
        }
        if (bonded.isNotEmpty()) {
            listAdapter.notifyDataSetChanged()
            dialog.setTitle(tr("Urządzenia (%s)").format(discoveredDevices.size))
        }

        // ── Poi fa scan BLE per aggiungere eventuali dispositivi BLE ──────────
        val scanner = bleAdapter?.bluetoothLeScanner ?: run {
            if (discoveredDevices.isEmpty())
                Toast.makeText(this, tr("Bluetooth niedostępny"), Toast.LENGTH_SHORT).show()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name   = device.name?.takeIf { it.isNotBlank() } ?: tr("Bez nazwy")
                if (discoveredDevices.none { it.address == device.address }) {
                    discoveredDevices.add(device)
                    runOnUiThread {
                        listItems.add(tr("%s  (BLE)\n%s").format(name, device.address))
                        listAdapter.notifyDataSetChanged()
                        dialog.setTitle(tr("Znalezione urządzenia: %s").format(discoveredDevices.size))
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        tr("Skanowanie BLE nieudane (%s)").format(errorCode), Toast.LENGTH_SHORT).show()
                }
            }
        }

        scanner.startScan(null, settings, bleScanCallback)

        scanStopHandler.postDelayed({
            stopDeviceScan()
            if (dialog.isShowing && discoveredDevices.isEmpty())
                dialog.setTitle(tr("Nie znaleziono urządzeń"))
        }, 10_000)
    }

    /** Stops the BLE scan started by [showDevicePicker]. */
    @SuppressLint("MissingPermission")
    private fun stopDeviceScan() {
        scanStopHandler.removeCallbacksAndMessages(null)
        val bleAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bleScanCallback?.let { bleAdapter?.bluetoothLeScanner?.stopScan(it) }
        bleScanCallback = null
    }

    /** Starts the foreground service targeting a specific device MAC address. */
    private fun connectToDeviceAddress(address: String) {
        binding.tvConnectionStatus.text = tr(getString(R.string.status_scanning))
        val intent = Intent(this, DpfForegroundService::class.java).apply {
            action = DpfForegroundService.ACTION_CONNECT_ADDRESS
            putExtra(DpfForegroundService.EXTRA_DEVICE_ADDRESS, address)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startBleService() {
        binding.tvConnectionStatus.text = tr(getString(R.string.status_scanning))
        val intent = Intent(this, DpfForegroundService::class.java).apply {
            action = DpfForegroundService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDeviceScan()
        devicePickerDialog?.dismiss()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Permissions
    // ═════════════════════════════════════════════════════════════════════════

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(requiredPermissions().toTypedArray())
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ — new BLE permissions
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // Android 6–11 — legacy BLE requires location
            perms += Manifest.permission.BLUETOOTH
            perms += Manifest.permission.BLUETOOTH_ADMIN
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — explicit notification permission
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms
    }
}
