package com.example.fordfocusdpfscan.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.fordfocusdpfscan.data.ConnectionState
import com.example.fordfocusdpfscan.data.DpfRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// ═══════════════════════════════════════════════════════════════════════════════
// BleManager.kt — OBD2 connection manager supporting BOTH transport types:
//
//  ① Classic Bluetooth SPP (BluetoothSocket)
//     Device name: "Android-Vlink"   PIN: 1234
//     Pair first in Android Settings → Bluetooth, then select from picker.
//     This is the primary method for Vgate iCar Pro on Android.
//
//  ② BLE GATT (BluetoothGatt)
//     Device name: "IOS-Vlink"
//     Appears directly in BLE scan — no pre-pairing needed.
//     Fallback / alternative method.
//
// Protocol for both: ELM327 ASCII, commands terminated with \r,
//   responses accumulated until the '>' prompt character.
//
// Init sequence (once per connection):
//   ATZ → ATE0 → ATL0 → ATH0 → ATAT1 → ATSP0 → ATSH<header> [→ 1003]
//   (header / extended session / polled PIDs come from EngineProfile)
// ═══════════════════════════════════════════════════════════════════════════════

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "FOCUS_BLE"

        // ── Classic Bluetooth SPP UUID (Serial Port Profile — standard) ────────
        val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // ── BLE GATT UUIDs (IOS-Vlink / FFF0 profile) ─────────────────────────
        val GATT_SERVICE_UUID  = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val GATT_NOTIFY_UUID   = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val GATT_WRITE_UUID    = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val GATT_DESCRIPTOR    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Additional BLE UUID sets tried if FFF0 is not found
        private data class UuidSet(val service: UUID, val notify: UUID, val write: UUID, val label: String)
        private val EXTRA_UUID_SETS = listOf(
            UuidSet(
                UUID.fromString("000018F0-0000-1000-8000-00805F9B34FB"),
                UUID.fromString("00002AF1-0000-1000-8000-00805F9B34FB"),
                UUID.fromString("00002AF0-0000-1000-8000-00805F9B34FB"),
                "18F0 (Android-Vlink BLE)"
            ),
            UuidSet(
                UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
                UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                "Nordic UART (NUS)"
            )
        )

        // ── ELM327 commands ────────────────────────────────────────────────────
        const val CMD_COOLANT_TEMP    = "0105"
        // DD01 confirmed: response 62 DD 01 XX XX XX → bytes[3..5] = km (3-byte big-endian)
        const val CMD_ODOMETER        = "22DD01"
        // 0178 confirmed: EGT Sensors Bank1 — formula: (A*256+B)*0.1 - 40 = °C
        // Sensor 1 (pre-DPF): bytes[3..4]. Sensor 2 (post-DPF): bytes[5..6] if present.
        const val CMD_EGT             = "0178"
        // 017A confirmed: DPF differential pressure (SAE J1979)
        // Byte A = support flags, Bytes B-C = delta P / 100 kPa (signed 16-bit)
        // Normal: ~0 kPa idle, 5–15 kPa cruise. High (>20 kPa) = filter loading.
        const val CMD_DPF_PRESSURE    = "017A"
        // 220579 CONFIRMED — DPF LOAD % (closed-loop, from pressure sensor)
        // raw integer = % directly. 100% = dynamic regen trigger, 320% = replace DPF.
        // Cross-validated vs competitor app: raw=1 → 1% ✓
        const val CMD_DPF_LOAD        = "220579"
        // 22057B CONFIRMED — DPF SOOT % (open-loop, combustion model)
        // raw integer = % directly. 100% = dynamic regen trigger, 320% = replace DPF.
        // Cross-validated vs competitor app: raw≈14 → 14% ✓
        const val CMD_DPF_SOOT        = "22057B"
        // 22050B CONFIRMED — km since last DPF regeneration (LST REG)
        // Exact match: raw=0x019A=410 km vs competitor 411.5 km (~1.5 km scan offset)
        const val CMD_LAST_REGEN_DIST = "22050B"
        // 220542 CONFIRMED — km since last oil change (OILCHG)
        // Exact match: raw=0x2313=8979 km vs competitor 8979 km ✓ (perfect match)
        const val CMD_OIL_CHANGE      = "220542"

        // ── Standard OBD2 Mode 01 — universally supported ─────────────────────
        // 010C: Engine RPM. Formula: ((A*256)+B)/4. Typical: 750–4500 rpm.
        const val CMD_RPM             = "010C"
        // 010D: Vehicle speed in km/h. Formula: A.
        const val CMD_SPEED           = "010D"
        // 0104: Calculated engine load %. Formula: A*100/255.
        const val CMD_ENGINE_LOAD     = "0104"
        // 010B: Intake Manifold Absolute Pressure (MAP) in kPa. Formula: A.
        // Boost = MAP − baroKpa. Idle: ~100 kPa. Full load: 180–220 kPa.
        const val CMD_INTAKE_MAP      = "010B"
        // 0133: Barometric pressure in kPa. Formula: A. Typically 95–103 kPa.
        const val CMD_BARO_PRESSURE   = "0133"
        // 015C: Engine oil temperature in °C. Formula: A-40. Warm: 90–110°C.
        const val CMD_OIL_TEMP        = "015C"
        // 0142: Control module (battery) voltage. Formula: ((A*256)+B)/1000 V.
        // Running ~13.8–14.4 V; key-off ~12.6 V. Flags weak battery / alternator.
        const val CMD_BATTERY_VOLTAGE   = "0142"
        // 0123: Fuel rail gauge pressure (diesel common rail). ((A*256)+B)*10 kPa.
        // 0159: Fuel rail absolute pressure — same formula; polled as fallback since
        //       ECUs expose one or the other. Both map to the same value.
        const val CMD_FUEL_RAIL_PRESS   = "0123"
        const val CMD_FUEL_RAIL_PRESS_A = "0159"
        // 22 0551 CONFIRMED on the EDC17C70 via scan RPM-correlation (standard 0123/0159
        // return NO DATA on this ECU). 62 05 51 <hi> <lo> → (A*256+B)/10 = bar
        // (idle ~290 bar, 1600 rpm ~408 bar). Stored as ×10 kPa to match the field.
        const val CMD_FUEL_RAIL_PRESS_FORD = "220551"
        // 010F: Intake air temperature. Formula: A-40 °C.
        const val CMD_INTAKE_AIR_TEMP   = "010F"


        // ── Timing ────────────────────────────────────────────────────────────
        private const val COMMAND_TIMEOUT_MS      = 3000L
        private const val INTER_COMMAND_DELAY_MS  = 100L   // was 300 — ~1.5s full cycle
        private const val INTER_CYCLE_DELAY_MS    = 200L   // was 1000 — 100×13 + 200 = 1500ms/cycle
        private const val ELM_INIT_TIMEOUT_MS     = 4000L
        private const val ELM_CMD_TIMEOUT_MS      = 1500L

        // ── SPP reliability ───────────────────────────────────────────────────
        private const val MAX_SPP_RETRIES = 3      // attempts per connect before backing off

        // ── Persistent auto-reconnect (survives dongle power-off at ignition) ──
        private const val RECONNECT_BASE_DELAY_MS = 2_000L    // grows with each attempt
        private const val RECONNECT_MAX_DELAY_MS  = 20_000L   // capped back-off
        // Keep retrying for this long after a drop, then give up to save battery.
        // A fresh attempt is armed again when the app is next foregrounded.
        private const val RECONNECT_GIVE_UP_MS    = 10 * 60_000L
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Name of the currently connected device, or null if not connected. */
    var connectedDeviceName: String? = null
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Response channel — shared by both SPP and BLE paths ───────────────────
    private val responseChannel = Channel<String>(Channel.CONFLATED)

    // ── SPP (Classic Bluetooth) state ─────────────────────────────────────────
    private var sppSocket: BluetoothSocket? = null
    private var sppOutput: OutputStream? = null
    private var sppReadJob: Job? = null

    // Last device connected — used for auto-reconnect after a drop
    @Volatile private var lastConnectedDevice: BluetoothDevice? = null
    // Set to false when the USER explicitly disconnects (prevents auto-reconnect)
    @Volatile private var autoReconnectEnabled = false
    // Address to reconnect to; set on every connect, cleared on user disconnect.
    @Volatile private var reconnectAddress: String? = null
    // Persistent reconnect supervisor job (see startReconnectLoop()).
    private var reconnectJob: Job? = null

    // ── BLE (GATT) state ──────────────────────────────────────────────────────
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val responseBuffer = StringBuilder()

    // ── Polling ───────────────────────────────────────────────────────────────
    private var pollingJob: Job? = null
    @Volatile private var pollingPaused = false

    // Tracks which transport is active
    private var usingSpp = false

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Connects using Classic Bluetooth SPP to a device already paired in
     * Android Settings (e.g. "Android-Vlink").
     *
     * Improvements over the naive single-attempt approach:
     *   • Calls cancelDiscovery() first — an active BT scan causes SPP connect
     *     to fail silently on many Android devices.
     *   • Retries up to MAX_SPP_RETRIES times with increasing back-off.
     *   • Delegates socket creation to createSppSocket() which tries three
     *     progressively more permissive socket types.
     *   • Sets autoReconnectEnabled so startSppReadLoop() can reconnect on drop.
     */
    fun connectSpp(device: BluetoothDevice) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING
        usingSpp = true
        lastConnectedDevice   = device
        autoReconnectEnabled  = true
        reconnectAddress      = device.address
        connectedDeviceName = device.name ?: device.address
        Log.d(TAG, "Connecting via SPP to ${device.name} (${device.address})…")

        scope.launch {
            // cancelDiscovery() is CRITICAL — an active Bluetooth scan causes
            // BluetoothSocket.connect() to throw IOException on many devices.
            btAdapter()?.cancelDiscovery()
            delay(200)

            var lastError = "unknown"
            for (attempt in 1..MAX_SPP_RETRIES) {
                Log.d(TAG, "SPP attempt $attempt/$MAX_SPP_RETRIES…")
                try {
                    val socket = createSppSocket(device)
                    socket.connect()
                    sppSocket = socket
                    sppOutput = socket.outputStream
                    Log.d(TAG, "SPP connected ✓ (attempt $attempt)")
                    startSppReadLoop(socket.inputStream)
                    performElmInit()
                    return@launch          // success — exit retry loop
                } catch (e: IOException) {
                    lastError = e.message ?: "IOException"
                    Log.w(TAG, "SPP attempt $attempt failed: $lastError")
                    try { sppSocket?.close() } catch (_: Exception) {}
                    sppSocket = null
                    sppOutput = null
                    if (attempt < MAX_SPP_RETRIES) delay(attempt * 2000L)
                }
            }
            Log.e(TAG, "SPP connect failed after $MAX_SPP_RETRIES attempts: $lastError")
            _connectionState.value = ConnectionState.DISCONNECTED
            DpfRepository.updateBleConnected(false)
            startReconnectLoop()   // keep retrying (dongle may not be powered yet)
        }
    }

    /**
     * Creates an SPP [BluetoothSocket] using three progressively more
     * permissive approaches until one succeeds.
     *
     *   1. Standard secure RFCOMM socket (preferred, PIN negotiation).
     *   2. Insecure RFCOMM socket — skips PIN phase; helps some OBD adapters.
     *   3. Reflection createRfcommSocket(1) — hardcodes channel 1; last resort
     *      for adapters that don't advertise the SDP record correctly.
     */
    @Suppress("DiscouragedPrivateApi")
    private fun createSppSocket(device: BluetoothDevice): BluetoothSocket {
        return try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e1: IOException) {
            Log.w(TAG, "Secure socket failed (${e1.message}) — trying insecure…")
            try {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e2: IOException) {
                Log.w(TAG, "Insecure socket failed (${e2.message}) — trying reflection…")
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                method.invoke(device, 1) as BluetoothSocket
            }
        }
    }

    /**
     * Scans for BLE devices advertising [TARGET_DEVICE_NAME] and connects.
     * Used for IOS-Vlink / BLE-only adapters.
     */
    fun connect() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        val adapter = btAdapter() ?: return
        _connectionState.value = ConnectionState.SCANNING
        usingSpp = false

        val scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                Log.d(TAG, "BLE found: $name")
                scanner.stopScan(this)
                connectGatt(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

        scanner.startScan(null, settings, cb)
        scope.launch {
            delay(30_000)
            scanner.stopScan(cb)
            if (_connectionState.value == ConnectionState.SCANNING)
                _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Connects to a specific device by MAC address.
     * If the device is already bonded (Classic BT), uses SPP.
     * Otherwise attempts BLE GATT.
     */
    fun connectByAddress(address: String) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        reconnectAddress     = address
        autoReconnectEnabled = true

        val adapter = btAdapter() ?: return
        val bonded = adapter.bondedDevices?.find { it.address == address }
        if (bonded != null) {
            Log.d(TAG, "Address $address is bonded — using SPP")
            connectSpp(bonded)
            return
        }

        // Not bonded → try BLE
        _connectionState.value = ConnectionState.SCANNING
        usingSpp = false

        val scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address == address) {
                    scanner.stopScan(this)
                    connectGatt(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = ConnectionState.DISCONNECTED
                startReconnectLoop()
            }
        }

        scanner.startScan(null, settings, cb)
        scope.launch {
            delay(30_000)
            scanner.stopScan(cb)
            if (_connectionState.value == ConnectionState.SCANNING) {
                _connectionState.value = ConnectionState.DISCONNECTED
                startReconnectLoop()
            }
        }
    }

    /**
     * Disconnects from the current device, then reconnects using the same
     * transport that was used for the last successful connection:
     *   • If the last device is still in Android's bonded set → SPP (connectSpp)
     *   • Otherwise → BLE scan (connect)
     *
     * This fixes the "Ricollega" button which previously always triggered a
     * BLE scan even when the user was connected via Classic Bluetooth SPP.
     */
    fun reconnect() {
        val target = lastConnectedDevice
        val wasSpp = usingSpp
        scope.launch {
            disconnect()
            delay(1_500L)
            if (target != null && wasSpp) {
                Log.d(TAG, "Reconnect → SPP to ${target.name}")
                connectSpp(target)
            } else {
                Log.d(TAG, "Reconnect → BLE scan")
                connect()
            }
        }
    }

    /**
     * Persistent auto-reconnect supervisor. Started on any unexpected drop or
     * failed connect (while [autoReconnectEnabled]). Retries [reconnectAddress]
     * with growing back-off until the link is CONNECTED, the user disconnects,
     * or [RECONNECT_GIVE_UP_MS] elapses (battery guard — re-armed on next
     * foreground via MainActivity.onStart). Idempotent: a second call while a
     * loop is already running is ignored.
     *
     * Fixes the "get out / back in the car" case: the dongle powers down with the
     * ignition, and the old one-shot reconnect gave up before it was ready again
     * (previously forcing a manual Bluetooth off/on to reconnect).
     */
    private fun startReconnectLoop() {
        if (!autoReconnectEnabled) return
        val addr = reconnectAddress ?: return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var attempt = 0
            Log.d(TAG, "Reconnect loop started → $addr")
            while (isActive && autoReconnectEnabled) {
                when (_connectionState.value) {
                    ConnectionState.CONNECTED -> {
                        Log.d(TAG, "Reconnect loop: link restored ✓")
                        return@launch
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (System.currentTimeMillis() - startedAt > RECONNECT_GIVE_UP_MS) {
                            Log.d(TAG, "Reconnect loop: giving up (battery guard)")
                            return@launch
                        }
                        attempt++
                        Log.d(TAG, "Reconnect attempt $attempt → $addr")
                        connectByAddress(addr)
                    }
                    else -> { /* SCANNING / CONNECTING — let the attempt resolve */ }
                }
                val backoff = (RECONNECT_BASE_DELAY_MS * attempt)
                    .coerceIn(RECONNECT_BASE_DELAY_MS, RECONNECT_MAX_DELAY_MS)
                delay(backoff)
            }
        }
    }

    fun pausePolling() {
        pollingPaused = true
        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "Polling paused")
    }

    fun resumePolling() {
        pollingPaused = false
        val canResume = if (usingSpp) sppSocket?.isConnected == true
                        else bluetoothGatt != null && writeCharacteristic != null
        if (canResume) {
            startPollingLoop()
            Log.d(TAG, "Polling resumed")
        }
    }

    /**
     * Sends an ELM327 [command] and returns the cleaned response, or null on timeout.
     * Polling MUST be paused before calling this.
     */
    suspend fun sendRawCommand(command: String, timeoutMs: Long): String? {
        // Drain any stale/late reply left in the CONFLATED channel by a previous
        // command, so we read THIS command's fresh response and not the prior one.
        // (Without this the ECU scanner logged every reply shifted by one PID.)
        while (responseChannel.tryReceive().isSuccess) { /* discard stale */ }
        if (!sendElmCommand(command)) return null
        val raw = withTimeoutOrNull(timeoutMs) { responseChannel.receive() }
        return raw?.let { cleanElmResponse(it) }
    }

    fun disconnect() {
        // Disable auto-reconnect BEFORE cancelling the read job, so that the
        // job's exit handler sees false and does not trigger a reconnect.
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAddress = null
        pollingJob?.cancel()
        sppReadJob?.cancel()
        try { sppSocket?.close() } catch (_: IOException) {}
        sppSocket = null
        sppOutput = null
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
        DpfRepository.updateBleConnected(false)
        Log.d(TAG, "Disconnected (user-initiated)")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SPP read loop
    // ═════════════════════════════════════════════════════════════════════════

    private fun startSppReadLoop(inputStream: InputStream) {
        sppReadJob = scope.launch {
            val buffer    = StringBuilder()
            val byteArray = ByteArray(1024)
            Log.d(TAG, "SPP read loop started")
            try {
                while (isActive) {
                    val bytes = inputStream.read(byteArray)
                    if (bytes == -1) break
                    val chunk = String(byteArray, 0, bytes, Charsets.UTF_8)
                    buffer.append(chunk)
                    Log.v(TAG, "← SPP chunk: ${chunk.replace("\r","\\r")}")
                    if (buffer.contains('>')) {
                        val full = buffer.toString()
                        buffer.clear()
                        responseChannel.send(full)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "SPP read loop ended: ${e.message}")
            }

            // Mark as disconnected and stop the polling loop
            Log.d(TAG, "SPP read loop exited (autoReconnect=$autoReconnectEnabled)")
            pollingJob?.cancel()
            _connectionState.value = ConnectionState.DISCONNECTED
            DpfRepository.updateBleConnected(false)

            // Persistent auto-reconnect unless the user explicitly disconnected
            // (disconnect() sets autoReconnectEnabled = false before cancelling this job).
            if (autoReconnectEnabled) startReconnectLoop()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BLE GATT connection
    // ═════════════════════════════════════════════════════════════════════════

    private fun connectGatt(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        connectedDeviceName = device.name ?: device.address
        lastConnectedDevice   = device
        reconnectAddress      = device.address
        autoReconnectEnabled  = true
        Log.d(TAG, "Connecting GATT to ${device.address}…")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected — discovering services…")
                    _connectionState.value = ConnectionState.CONNECTING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    DpfRepository.updateBleConnected(false)
                    pollingJob?.cancel()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    if (autoReconnectEnabled) startReconnectLoop()   // recover when dongle returns
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed ($status)")
                return
            }

            // Log everything for debugging
            Log.d(TAG, "Services on ${gatt.device.address}:")
            gatt.services.forEach { svc ->
                Log.d(TAG, "  ${svc.uuid}")
                svc.characteristics.forEach { Log.d(TAG, "    char=${it.uuid} props=${it.properties}") }
            }

            // Build full list of UUID sets to try (FFF0 first, then extras)
            data class UuidSet(val service: UUID, val notify: UUID, val write: UUID, val label: String)
            val allSets = mutableListOf(
                UuidSet(GATT_SERVICE_UUID, GATT_NOTIFY_UUID, GATT_WRITE_UUID, "FFF0")
            ) + EXTRA_UUID_SETS.map { UuidSet(it.service, it.notify, it.write, it.label) }

            var notifyChar: BluetoothGattCharacteristic? = null
            for (set in allSets) {
                val svc = gatt.getService(set.service) ?: continue
                val w   = svc.getCharacteristic(set.write)  ?: continue
                val n   = svc.getCharacteristic(set.notify) ?: continue
                writeCharacteristic = w
                notifyChar = n
                Log.d(TAG, "Matched UUID set: ${set.label} ✓")
                break
            }

            if (notifyChar == null) {
                Log.e(TAG, "No known ELM327 service found on device")
                _connectionState.value = ConnectionState.DISCONNECTED
                gatt.disconnect()
                return
            }

            gatt.setCharacteristicNotification(notifyChar, true)
            val desc = notifyChar.getDescriptor(GATT_DESCRIPTOR)
            desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            desc?.let { gatt.writeDescriptor(it) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written — starting ELM327 init")
                scope.launch { performElmInit() }
            } else {
                Log.e(TAG, "Descriptor write failed ($status)")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val text = value.toString(Charsets.UTF_8)
            responseBuffer.append(text)
            if (responseBuffer.contains('>')) {
                val full = responseBuffer.toString()
                responseBuffer.clear()
                scope.launch { responseChannel.send(full) }
            }
        }

        @Deprecated("Legacy API")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ELM327 init + polling — shared by SPP and BLE
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun performElmInit() {
        Log.d(TAG, "ELM327 init sequence started")
        responseBuffer.clear()

        val profile = EngineProfile.active(context)
        Log.d(TAG, "Engine profile: ${profile.id}")
        for ((i, cmd) in profile.elmInit.withIndex()) {
            val timeout = if (i == 0) ELM_INIT_TIMEOUT_MS else ELM_CMD_TIMEOUT_MS
            sendElmCommand(cmd)
            val resp = withTimeoutOrNull(timeout) { responseChannel.receive() }
            Log.d(TAG, "Init [$cmd] → ${resp?.trim() ?: "TIMEOUT"}")
            delay(100)
        }

        Log.d(TAG, "ELM327 init complete")
        _connectionState.value = ConnectionState.CONNECTED
        DpfRepository.updateBleConnected(true)
        startPollingLoop()
    }

    private fun startPollingLoop() {
        pollingJob = scope.launch {
            Log.d(TAG, "Polling loop started")
            while (isActive) {
                for (cmd in EngineProfile.active(context).pids) {
                    if (!isActive || pollingPaused) break
                    sendAndReceive(cmd)
                    delay(INTER_COMMAND_DELAY_MS)
                }
                delay(INTER_CYCLE_DELAY_MS)
            }
        }
    }

    private suspend fun sendAndReceive(cmd: String) {
        if (!sendElmCommand(cmd)) return
        val raw = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { responseChannel.receive() } ?: run {
            Log.w(TAG, "Timeout: $cmd")
            return
        }
        val cleaned = cleanElmResponse(raw)
        if (cleaned.isNotEmpty()) parseResponse(cmd, cleaned)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Send — routes to SPP output stream or BLE write characteristic
    // ═════════════════════════════════════════════════════════════════════════

    private fun sendElmCommand(command: String): Boolean {
        val bytes = "$command\r".toByteArray(Charsets.UTF_8)
        return if (usingSpp) {
            try {
                sppOutput?.write(bytes)
                sppOutput?.flush()
                Log.v(TAG, "→ SPP: $command")
                true
            } catch (e: IOException) {
                Log.e(TAG, "SPP write failed: ${e.message}")
                false
            }
        } else {
            val gatt = bluetoothGatt ?: return false
            val char = writeCharacteristic ?: return false
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = bytes
            val ok = gatt.writeCharacteristic(char)
            Log.v(TAG, "→ BLE: $command (ok=$ok)")
            ok
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Response parser
    // ═════════════════════════════════════════════════════════════════════════

    private fun parseResponse(cmd: String, cleaned: String) {
        if (cleaned.contains("NO DATA") || cleaned.contains("ERROR") ||
            cleaned.startsWith("?") || cleaned.isEmpty()) return

        val bytes = hexStringToBytes(cleaned) ?: return
        if (bytes.isEmpty()) return
        val first = bytes[0].toInt() and 0xFF
        if (first == 0x7F) return   // UDS negative response

        // ── Echo byte validation ───────────────────────────────────────────────
        // Each OBD2 response echoes the requested PID in the first 1-3 bytes.
        // We MUST verify this before applying any formula, because:
        //   - stale responses from timed-out commands can be consumed by the next
        //     command (CONFLATED channel keeps only the last value), and
        //   - applying the wrong formula to mismatched bytes produces wild values.
        //
        // Mode 01: cmd="01XX" → response[0]=0x41, response[1]=0xXX
        // Mode 22: cmd="22XXYY" → response[0]=0x62, response[1]=0xXX, response[2]=0xYY
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        fun checkMode01(pid: Int) = first == 0x41 && bytes.size >= 2 && b(1) == pid
        fun checkMode22(p1: Int, p2: Int) = first == 0x62 && bytes.size >= 3 && b(1) == p1 && b(2) == p2

        when (cmd) {
            CMD_COOLANT_TEMP -> if (checkMode01(0x05) && bytes.size >= 3) {
                // 41 05 <A> — A - 40 = °C. Range: -40..215°C; plausible: -10..130°C.
                val v = (b(2) - 40).toFloat()
                if (v in -10f..130f) DpfRepository.updateCoolantTemp(v)
            }
            CMD_ODOMETER -> if (checkMode22(0xDD, 0x01) && bytes.size >= 6) {
                // 62 DD 01 XX XX XX (3 data bytes → km big-endian)
                val km = ((bytes[3].toLong() and 0xFF) shl 16) or
                         ((bytes[4].toLong() and 0xFF) shl 8)  or
                          (bytes[5].toLong() and 0xFF)
                DpfRepository.updateOdometer(km)
            }
            CMD_EGT -> if (checkMode01(0x78) && bytes.size >= 5) {
                // 41 78 <support> <sens1Hi> <sens1Lo> [<sens2Hi> <sens2Lo>]
                // Formula: (A*256+B) * 0.1 - 40 = °C. Plausible range: 0..950°C.
                val raw1 = (b(3) shl 8) or b(4)
                val egt1 = raw1 * 0.1f - 40f
                if (egt1 in 0f..950f) DpfRepository.updateEgt(egt1)
                if (bytes.size >= 7) {
                    val raw2 = (b(5) shl 8) or b(6)
                    val egt2 = raw2 * 0.1f - 40f
                    if (egt2 in 0f..950f) DpfRepository.updateEgtPost(egt2)
                }
            }
            CMD_DPF_LOAD -> if (checkMode22(0x05, 0x79) && bytes.size >= 5) {
                // 62 05 79 <hi> <lo> — raw integer = % load (0–320 scale)
                val raw = (b(3) shl 8) or b(4)
                DpfRepository.updateLoad(raw.toFloat().coerceIn(0f, 320f))
            }
            CMD_DPF_SOOT -> if (checkMode22(0x05, 0x7B) && bytes.size >= 5) {
                // 62 05 7B <hi> <lo> — raw integer = % soot (0–320 scale)
                val raw = (b(3) shl 8) or b(4)
                DpfRepository.updateSoot(raw.toFloat().coerceIn(0f, 320f))
            }
            CMD_LAST_REGEN_DIST -> if (checkMode22(0x05, 0x0B) && bytes.size >= 5) {
                // 62 05 0B <hi> <lo> — km since last regen
                val km = ((bytes[3].toLong() and 0xFF) shl 8) or (bytes[4].toLong() and 0xFF)
                DpfRepository.updateKmSinceLastRegen(km)
            }
            CMD_OIL_CHANGE -> if (checkMode22(0x05, 0x42) && bytes.size >= 5) {
                // 62 05 42 <hi> <lo> — km since last oil change
                val km = ((bytes[3].toLong() and 0xFF) shl 8) or (bytes[4].toLong() and 0xFF)
                DpfRepository.updateKmSinceOilChange(km)
            }
            CMD_RPM -> if (checkMode01(0x0C) && bytes.size >= 4) {
                // 41 0C <A> <B> — ((A*256)+B)/4 = rpm. Plausible: 0..7000 rpm.
                val raw = (b(2) shl 8) or b(3)
                val rpm = raw / 4f
                if (rpm in 0f..7000f) DpfRepository.updateRpm(rpm)
            }
            CMD_SPEED -> if (checkMode01(0x0D) && bytes.size >= 3) {
                // 41 0D <A> — A = km/h. Plausible: 0..250 km/h.
                val v = b(2).toFloat()
                if (v in 0f..250f) DpfRepository.updateSpeed(v)
            }
            CMD_ENGINE_LOAD -> if (checkMode01(0x04) && bytes.size >= 3) {
                // 41 04 <A> — A*100/255 = %. Always 0..100.
                DpfRepository.updateEngineLoad((b(2) * 100f) / 255f)
            }
            CMD_INTAKE_MAP -> if (checkMode01(0x0B) && bytes.size >= 3) {
                // 41 0B <A> — A = kPa absolute. Plausible: 50..300 kPa.
                val v = b(2).toFloat()
                if (v in 50f..300f) DpfRepository.updateIntakeMap(v)
            }
            CMD_BARO_PRESSURE -> if (checkMode01(0x33) && bytes.size >= 3) {
                // 41 33 <A> — A = kPa ambient. Plausible: 85..110 kPa.
                val v = b(2).toFloat()
                if (v in 85f..110f) DpfRepository.updateBaroPressure(v)
            }
            CMD_OIL_TEMP -> if (checkMode01(0x5C) && bytes.size >= 3) {
                // 41 5C <A> — A - 40 = °C. Plausible: -20..150°C.
                val v = (b(2) - 40).toFloat()
                if (v in -20f..150f) DpfRepository.updateOilTemp(v)
            }
            CMD_BATTERY_VOLTAGE -> if (checkMode01(0x42) && bytes.size >= 4) {
                // 41 42 <A> <B> — (A*256+B)/1000 = Volts. Plausible: 6..16 V.
                val v = ((b(2) shl 8) or b(3)) / 1000f
                if (v in 6f..16f) DpfRepository.updateBatteryVoltage(v)
            }
            CMD_FUEL_RAIL_PRESS, CMD_FUEL_RAIL_PRESS_A -> {
                // 41 23/59 <A> <B> — (A*256+B)*10 = kPa (diesel common rail).
                val pid = if (cmd == CMD_FUEL_RAIL_PRESS) 0x23 else 0x59
                if (checkMode01(pid) && bytes.size >= 4) {
                    val kPa = ((b(2) shl 8) or b(3)) * 10f
                    if (kPa in 0f..250_000f) DpfRepository.updateFuelRailPressure(kPa)
                }
            }
            CMD_INTAKE_AIR_TEMP -> if (checkMode01(0x0F) && bytes.size >= 3) {
                // 41 0F <A> — A - 40 = °C. Plausible: -40..120°C.
                val t = (b(2) - 40).toFloat()
                if (t in -40f..120f) DpfRepository.updateIntakeAirTemp(t)
            }
            CMD_FUEL_RAIL_PRESS_FORD -> if (checkMode22(0x05, 0x51) && bytes.size >= 5) {
                // 62 05 51 <hi> <lo> — (A*256+B)/10 = bar → stored as ×10 kPa
                val kPa = ((b(3) shl 8) or b(4)) * 10f
                if (kPa in 0f..250_000f) DpfRepository.updateFuelRailPressure(kPa)
            }
            CMD_DPF_PRESSURE -> if (checkMode01(0x7A) && bytes.size >= 7) {
                // 41 7A <support> <B> <C> <D> <E>. On the EDC17C70 the live DPF
                // backpressure is in bytes D,E (B,C always read 0). (256*D+E)/100 = kPa.
                // Verified on-car: scales with exhaust flow (idle ~0.2, ~1600 rpm ~3.9 kPa).
                val raw      = (b(5) shl 8) or b(6)
                val signed16 = if (raw > 0x7FFF) raw - 0x10000 else raw
                val kPa      = signed16 / 100f
                if (kPa >= -5f) DpfRepository.updateDpfDeltaPressure(kPa.coerceAtLeast(0f))
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Utilities
    // ═════════════════════════════════════════════════════════════════════════

    fun cleanElmResponse(raw: String): String =
        raw.replace(">", "").replace("\r", " ").replace("\n", " ")
           .trim().replace(Regex("\\s+"), " ")

    fun hexStringToBytes(hex: String): ByteArray? = try {
        // Regex filter strips multi-frame markers like "0:" "1:" "00B" that are
        // inserted by the ELM327 for ISO-TP multi-frame responses (e.g. PID 0178).
        // Only tokens that are exactly 2 valid hex digits are kept.
        hex.trim().split(" ")
           .filter { it.matches(Regex("[0-9A-Fa-f]{2}")) }
           .map { it.toInt(16).toByte() }.toByteArray()
    } catch (_: Exception) { null }

    private fun btAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

// ─────────────────────────────────────────────────────────────────────────────
object BleManagerHolder {
    @Volatile var instance: BleManager? = null
}
