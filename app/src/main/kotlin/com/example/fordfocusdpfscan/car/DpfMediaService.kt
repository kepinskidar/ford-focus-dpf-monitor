package com.example.fordfocusdpfscan.car

import com.example.fordfocusdpfscan.i18n.tr
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.fordfocusdpfscan.ble.BleManagerHolder
import com.example.fordfocusdpfscan.data.ConnectionState
import com.example.fordfocusdpfscan.service.DpfForegroundService
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.example.fordfocusdpfscan.data.DpfData
import com.example.fordfocusdpfscan.data.DpfRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════════
// DpfMediaService.kt — Android Auto entry point implemented as a MEDIA app.
//
// Android Auto lists sideloaded (APK) *media* apps without any special setup,
// whereas template apps (CarAppService) are not shown for a sideloaded APK.
// This service exposes the live DPF data as a browsable list ("media items"):
// the title is the label and the subtitle is the value. Nothing is playable —
// it is only a read-only dashboard fed by DpfRepository.
//
//   Each list has at most 3 rows, so it fits the car screen without scrolling
//   (Android Auto resets the scroll position whenever a list is refreshed).
//
//   Root
//   ├─ Filtr DPF    → Sadza / Obciążenie / ΔP
//   ├─ Regeneracja  → Stan / km od regeneracji / EGT
//   ├─ Silnik       → Płyn / Olej / Obroty
//   ├─ Jazda        → Prędkość / Obciążenie silnika / MAP
//   ├─ Zasilanie    → Akumulator / Ciśnienie w listwie / Powietrze dolotowe
//   ├─ Dystanse     → Licznik / km od regeneracji / km od wymiany oleju
//   └─ Połączenie OBD (status)
//
// The session metadata (title/artist) carries a short DPF summary. It reaches the
// Android Auto bottom "now playing" bar after the user taps a row (state PLAYING,
// no audio is played and no audio focus is requested); pressing pause in the bar
// releases it. Only one media app can occupy that bar at a time.
// ═══════════════════════════════════════════════════════════════════════════════

class DpfMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastNotify = 0L
    private var showInBar = false
    private var lastAutoConnect = 0L
    /** Parent list the car screen opened last — only this one is refreshed. */
    @Volatile private var openParent: String = ROOT
    private val lastSent = HashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "MK5DPF").apply {
            // Tapping a row (or Play) pins the DPF summary to the bottom media bar;
            // Pause releases it so the previous media app (e.g. Spotify) can return.
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = setShowInBar(true)
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = setShowInBar(true)
                override fun onPause() = setShowInBar(false)
                override fun onStop() = setShowInBar(false)
            })
            isActive = true
        }
        applyPlaybackState()
        sessionToken = session.sessionToken

        // Keep the bottom-bar text alive: Android Auto collapses the title after a few
        // seconds unless the metadata keeps changing. StateFlow does not re-emit equal
        // values, so a separate ticker refreshes the metadata even when the ECU data is
        // unchanged (a media-id counter + an invisible character force a "change").
        scope.launch {
            var tick = 0L
            while (true) {
                updateMetadata(DpfRepository.dpfData.value, tick++)
                // While pinned, periodically "start playback again": Android Auto shows the
                // media app that started playing most recently, so this wins the bar back
                // if another app (e.g. Spotify) took it over. Pause in the bar stops this.
                if (showInBar) applyPlaybackState(restart = tick % REASSERT_EVERY_TICKS == 0L)
                delay(META_INTERVAL_MS)
            }
        }

        scope.launch {
            DpfRepository.dpfData.collectLatest { d ->
                val now = System.currentTimeMillis()
                // Refresh ONLY the open list, and only if its text actually changed.
                if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                    lastNotify = now
                    val id = openParent
                    val h = childrenFor(id, d).joinToString("|") {
                        "${it.description.title}=${it.description.subtitle}"
                    }.hashCode()
                    if (lastSent[id] != h) {
                        lastSent[id] = h
                        notifyChildrenChanged(id)
                    }
                }
            }
        }
    }

    /**
     * Second line of the bottom bar: "<status> · ΔP <value> kPa". The bar fits roughly
     * 34 characters, so when it would be longer the unit is dropped, and if it is still
     * too long only the status is shown.
     */
    private fun barSubtitle(d: DpfData): String {
        val status = regenShort(d)
        val dp = kpa(d.dpfDeltaPressureKpa)
        var line = "$status · ΔP $dp"
        if (line.length > BAR_MAX_CHARS) line = "$status · ΔP ${dp.removeSuffix(" kPa")}"
        if (line.length > BAR_MAX_CHARS) line = status
        return line
    }

    private fun updateMetadata(d: DpfData, tick: Long) {
        val nudge = if (showInBar && tick % 2L == 0L) "\u200B" else ""
        val mediaId = if (showInBar) "mk5dpf-$tick" else "mk5dpf"
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                    tr("Sadza %s · Obc. %s · EGT %s%s").format(pct(d.sootPercentage), pct(d.loadPercentage), temp(d.egtPostDpfC), nudge))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, barSubtitle(d))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "MK5 DPF")
                .build()
        )
    }

    private fun setShowInBar(show: Boolean) {
        showInBar = show
        applyPlaybackState()
    }

    private fun applyPlaybackState(restart: Boolean = false) {
        // A PAUSED -> PLAYING transition counts as "playback started" for the host.
        if (restart && showInBar) setPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        setPlaybackState(
            if (showInBar) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        )
    }

    private fun setPlaybackState(state: Int) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(state, 0L, 1f)
                .build()
        )
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        autoConnectLastAdapter()
        return BrowserRoot(ROOT, null)
    }

    /**
     * Opening the app from Android Auto starts the BLE foreground service and connects to
     * the last used OBD adapter (same address MainActivity saves). Android 12+ may refuse
     * to start a foreground service while the app is in the background — then this simply
     * logs and the app has to be opened once on the phone.
     */
    private fun autoConnectLastAdapter() {
        val now = System.currentTimeMillis()
        if (now - lastAutoConnect < AUTO_CONNECT_MIN_INTERVAL_MS) return
        lastAutoConnect = now
        try {
            val mgr = BleManagerHolder.instance
            if (mgr != null && mgr.connectionState.value != ConnectionState.DISCONNECTED) return
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return
            val address = getSharedPreferences("focus_prefs", MODE_PRIVATE)
                .getString("last_device_address", null) ?: return
            val intent = Intent(this, DpfForegroundService::class.java).apply {
                action = DpfForegroundService.ACTION_CONNECT_ADDRESS
                putExtra(DpfForegroundService.EXTRA_DEVICE_ADDRESS, address)
            }
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Auto-connect requested from Android Auto: $address")
        } catch (e: Exception) {
            Log.w(TAG, "Auto-connect from Android Auto not allowed: ${e.message}")
        }
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        openParent = parentId
        val items = childrenFor(parentId, DpfRepository.dpfData.value)
        lastSent[parentId] = items.joinToString("|") {
            "${it.description.title}=${it.description.subtitle}"
        }.hashCode()
        result.sendResult(items.toMutableList())
    }

    private fun childrenFor(parentId: String, d: DpfData): List<MediaBrowserCompat.MediaItem> =
        when (parentId) {
            ROOT -> rootItems(d)
            ID_DPF -> listOf(
                leaf("soot", tr("Sadza"), pct(d.sootPercentage)),
                leaf("load", tr("Obciążenie DPF"), pct(d.loadPercentage)),
                leaf("dp", tr("Delta P (różnica ciśnień)"), kpa(d.dpfDeltaPressureKpa))
            )
            ID_REGEN -> listOf(
                leaf("regen", tr("Stan regeneracji"), regenText(d)),
                leaf("kmregen", tr("Od ostatniej regeneracji"), km(d.kmSinceLastRegen)),
                leaf("egt", tr("EGT (temp. spalin)"), temp(d.egtCelsius))
            )
            ID_ENGINE -> listOf(
                leaf("coolant", tr("Płyn chłodzący"), temp(d.coolantTempC)),
                leaf("oil", tr("Temperatura oleju"), temp(d.oilTempC)),
                leaf("rpm", tr("Obroty"), num(d.rpmValue, tr("obr./min")))
            )
            ID_DRIVE -> listOf(
                leaf("speed", tr("Prędkość"), num(d.speedKmh, "km/h")),
                leaf("engload", tr("Obciążenie silnika"), pct(d.engineLoadPct)),
                leaf("map", tr("Ciśnienie w kolektorze"), kpa(d.intakeMapKpa))
            )
            ID_POWER -> listOf(
                leaf("batt", tr("Akumulator"),
                    if (d.batteryVoltage >= 0f) "%.1f V".format(d.batteryVoltage) else NO_DATA),
                leaf("rail", tr("Ciśnienie w listwie"),
                    if (d.fuelRailPressureKpa >= 0f) "%.0f bar".format(d.fuelRailPressureKpa / 100f) else NO_DATA),
                leaf("iat", tr("Powietrze dolotowe"), temp(d.intakeAirTempC))
            )
            ID_DIST -> listOf(
                leaf("odo", tr("Licznik"), km(d.odometerKm)),
                leaf("kmregen2", tr("Od ostatniej regeneracji"), km(d.kmSinceLastRegen)),
                leaf("kmoil", tr("Od wymiany oleju"), km(d.kmSinceOilChange))
            )
            else -> emptyList()
        }

    private fun rootItems(d: DpfData) = listOf(
        folder(ID_DPF, tr("Filtr DPF"),
            tr("Sadza %s · Obc. %s · ΔP %s").format(pct(d.sootPercentage), pct(d.loadPercentage), kpa(d.dpfDeltaPressureKpa))),
        folder(ID_REGEN, tr("Regeneracja"), regenText(d)),
        folder(ID_ENGINE, tr("Silnik"),
            tr("Płyn %s · Olej %s · %s").format(temp(d.coolantTempC), temp(d.oilTempC), num(d.rpmValue, "obr./min"))),
        folder(ID_DRIVE, tr("Jazda"),
            tr("%s · obciążenie %s").format(num(d.speedKmh, "km/h"), pct(d.engineLoadPct))),
        folder(ID_POWER, tr("Zasilanie"),
            tr("Akumulator %s").format(if (d.batteryVoltage >= 0f) "%.1f V".format(d.batteryVoltage) else NO_DATA)),
        folder(ID_DIST, tr("Dystanse"),
            tr("Licznik %s · od regen. %s").format(km(d.odometerKm), km(d.kmSinceLastRegen))),
        leaf("conn", tr("Połączenie OBD"), if (d.bleConnected) tr("Połączono") else tr("Rozłączono"))
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun pct(v: Float) = if (v >= 0f) "${v.toInt()}%" else NO_DATA
    private fun kpa(v: Float) = if (v >= 0f) "%.1f kPa".format(v) else NO_DATA
    private fun temp(v: Float) = if (v >= 0f) "${v.toInt()}°C" else NO_DATA
    private fun km(v: Long) = if (v >= 0L) "%,d km".format(v) else NO_DATA
    private fun num(v: Float, unit: String) = if (v >= 0f) "${v.toInt()} $unit" else NO_DATA

    private fun regenShort(d: DpfData) = when (d.regenStatus.name) {
        "INACTIVE" -> tr("DPF: Kumulowanie sadzy")
        "WARNING" -> tr("DPF: Wypalanie pasywne")
        "ACTIVE" -> tr("DPF: !!Regeneracja!!")
        "COMPLETED" -> tr("DPF: Zakończono wypalanie")
        else -> d.regenStatus.name
    }

    private fun regenText(d: DpfData) = when (d.regenStatus.name) {
        "INACTIVE" -> tr("Nieaktywna — można zgasić silnik")
        "WARNING" -> tr("⚠ Wysoka temp. — nie gaś silnika")
        "ACTIVE" -> tr("AKTYWNA — nie gaś silnika!")
        "COMPLETED" -> tr("Zakończona — można zgasić silnik")
        else -> d.regenStatus.name
    }

    private fun leaf(id: String, title: String, value: String) = item(id, title, value, false)
    private fun folder(id: String, title: String, value: String) = item(id, title, value, true)

    private fun item(id: String, title: String, subtitle: String, browsable: Boolean) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build(),
            if (browsable) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )

    companion object {
        private const val TAG = "FOCUS_AutoMedia"
        private const val AUTO_CONNECT_MIN_INTERVAL_MS = 30_000L
        private const val ROOT = "root"
        private const val ID_DPF = "dpf"
        private const val ID_REGEN = "regen_group"
        private const val ID_ENGINE = "engine"
        private const val ID_DRIVE = "drive"
        private const val ID_POWER = "power"
        private const val ID_DIST = "dist"
        private const val NO_DATA = "– –"
        private const val NOTIFY_INTERVAL_MS = 4000L
        private const val META_INTERVAL_MS = 2000L
        private const val BAR_MAX_CHARS = 34
        /** Re-claim the bottom bar every REASSERT_EVERY_TICKS * META_INTERVAL_MS (= 12 s). */
        private const val REASSERT_EVERY_TICKS = 6L
    }
}
