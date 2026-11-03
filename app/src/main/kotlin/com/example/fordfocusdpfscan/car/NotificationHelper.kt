package com.example.fordfocusdpfscan.car

import com.example.fordfocusdpfscan.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import android.graphics.BitmapFactory
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.data.DpfData
import com.example.fordfocusdpfscan.data.RegenStatus
import com.example.fordfocusdpfscan.data.db.MaintenanceReminder
import com.example.fordfocusdpfscan.ui.EcuScanActivity
import com.example.fordfocusdpfscan.ui.MainActivity
import com.example.fordfocusdpfscan.ui.MaintenanceActivity

// ═══════════════════════════════════════════════════════════════════════════════
// NotificationHelper.kt — Central factory for all app notifications.
//
// Three notification levels:
//   1. Persistent (NOTIF_ID_PERSISTENT) — always visible, updated with live data.
//   2. Regen event heads-up (NOTIF_ID_EVENT) — WARNING / ACTIVE / COMPLETED.
//      Plays the user's custom MP3 (res/raw/dpf_monitor_sound.mp3).
//   3. Connection event (NOTIF_ID_CONNECTION) — BLE connected / disconnected.
//      Silent — no sound, just a pop-up heads-up notification.
//   4. CarToast — fired in-car via the Android Auto Screen (see DpfScreen).
//
// Notification channels:
//   • CHANNEL_PERSISTENT  — IMPORTANCE_LOW  (silent status bar — always visible)
//   • CHANNEL_REGEN       — IMPORTANCE_HIGH + dpf_monitor_sound.mp3 + vibration
//   • CHANNEL_CONNECTION  — IMPORTANCE_DEFAULT, no sound (silent popup)
// ═══════════════════════════════════════════════════════════════════════════════

object NotificationHelper {

    private const val TAG = "FOCUS_Notif"

    // ── Notification IDs ──────────────────────────────────────────────────────
    const val NOTIF_ID_PERSISTENT  = 1001
    const val NOTIF_ID_EVENT       = 1002
    const val NOTIF_ID_CONNECTION  = 1003
    const val NOTIF_ID_SERVICE     = 1004   // tagliando / service reminder
    const val NOTIF_ID_COOLDOWN    = 1005   // turbo cooldown timer
    const val NOTIF_ID_CAPTURE     = 1006   // regen ECU-state capture done
    // Maintenance notifications use IDs 2000 + reminder.id (one per reminder)

    // ── Channel IDs ───────────────────────────────────────────────────────────
    private const val CHANNEL_PERSISTENT  = "focus_persistent"
    // focus_regen_v2: bumped from v1 because Android caches channel sound settings
    // after first creation and ignores subsequent updates. New ID forces a fresh
    // channel with the correct dpf_monitor_sound.mp3 audio attributes.
    private const val CHANNEL_REGEN       = "focus_regen_v2"     // custom MP3 sound
    private const val CHANNEL_CONNECTION  = "focus_connection"   // silent
    private const val CHANNEL_SERVICE     = "focus_service"      // silent, service reminders
    private const val CHANNEL_COOLDOWN     = "focus_cooldown"      // silent, turbo cooldown
    private const val CHANNEL_MAINTENANCE  = "focus_maintenance"   // silent, maintenance reminders

    // ── Notification timeout ──────────────────────────────────────────────────
    private const val EVENT_TIMEOUT_MS = 5_000L   // heads-up dismisses after 5 s

    // ── Whether channels have been created ────────────────────────────────────
    private var channelsCreated = false

    // ── Cached icon bitmap — decoded once off the main thread, then reused ──────
    // ic_mk5_dpf.png is a large PNG. Decoding it synchronously on the main
    // thread (first event notification) caused a ServiceANR. We now pre-warm the
    // cache from a background thread in warmIconCache(), called at app startup.
    // getIconBitmap() falls back to a tiny placeholder until the cache is ready.
    private var cachedIconBitmap: android.graphics.Bitmap? = null

    /**
     * Decode and cache ic_mk5_dpf.png on a background thread.
     * Call this from FocusApp.onCreate() so the bitmap is ready before
     * the first regen notification fires.
     */
    fun warmIconCache(context: Context) {
        if (cachedIconBitmap != null) return
        val appContext = context.applicationContext
        Thread {
            val size = (128 * appContext.resources.displayMetrics.density).toInt()
            val raw = BitmapFactory.decodeResource(appContext.resources, R.drawable.ic_mk5_dpf)
            if (raw != null) {
                val scaled = android.graphics.Bitmap.createScaledBitmap(raw, size, size, true)
                if (scaled !== raw) raw.recycle()
                cachedIconBitmap = scaled
                Log.d(TAG, "Icon bitmap cached (${scaled.width}×${scaled.height})")
            }
        }.start()
    }

    private fun getIconBitmap(context: Context): android.graphics.Bitmap? = cachedIconBitmap

    // ═════════════════════════════════════════════════════════════════════════
    // Channel creation — must be called before posting any notification
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates both notification channels. Safe to call multiple times (idempotent).
     * Call from Application.onCreate() or before the first notification.
     */
    fun createChannels(context: Context) {
        if (channelsCreated) return
        channelsCreated = true

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        // ── Channel 1: Persistent (silent status bar) ─────────────────────────
        val persistentChannel = NotificationChannel(
            CHANNEL_PERSISTENT,
            tr("Monitorowanie DPF"),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = tr("Stan DPF na żywo — widoczny podczas monitorowania")
            setSound(null, null)
            enableVibration(false)
        }

        // ── Channel 2: Regen alerts (user MP3 + vibration + LED) ─────────────
        val regenSoundUri = Uri.parse(
            "android.resource://${context.packageName}/${R.raw.dpf_monitor_sound}"
        )
        val regenChannel = NotificationChannel(
            CHANNEL_REGEN,
            tr(context.getString(R.string.notif_channel_name)),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = tr(context.getString(R.string.notif_channel_desc))
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(regenSoundUri, audioAttr)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            enableLights(true)
            lightColor = Color.RED
            Log.d(TAG, "Regen channel sound: $regenSoundUri")
        }

        // ── Channel 3: Connection events (silent popup — no sound) ────────────
        val connectionChannel = NotificationChannel(
            CHANNEL_CONNECTION,
            tr("Połączenie OBD"),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = tr("Ciche powiadomienia o połączeniu/rozłączeniu Bluetooth")
            setSound(null, null)
            enableVibration(false)
        }

        // ── Channel 4: Service reminders (silent popup — no sound) ───────────
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            tr("Przypomnienia o przeglądzie"),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = tr("Przypomnienia o umówieniu przeglądu w warsztacie")
            setSound(null, null)
            enableVibration(false)
        }

        // ── Channel 5: Turbo cooldown (silent — informational only) ──────────
        val cooldownChannel = NotificationChannel(
            CHANNEL_COOLDOWN,
            tr("Chłodzenie turbosprężarki"),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = tr("Ciche powiadomienia o schładzaniu turbo po jeździe")
            setSound(null, null)
            enableVibration(false)
        }

        // ── Channel 6: Maintenance reminders (silent — informational) ────────
        val maintenanceChannel = NotificationChannel(
            CHANNEL_MAINTENANCE,
            tr("Przypomnienia serwisowe"),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = tr("Terminy serwisowe: przegląd, opony, filtry")
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(
            listOf(persistentChannel, regenChannel, connectionChannel, serviceChannel, cooldownChannel, maintenanceChannel)
        )
        Log.d(TAG, "Notification channels created")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Level 1 — Persistent notification
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Builds (or rebuilds) the always-on foreground service notification.
     * Updated every time DpfRepository emits new data.
     */
    fun buildPersistentNotification(
        context: Context,
        dpfData: DpfData = DpfData()
    ): Notification {
        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Summary line shown in the notification body
        val summary = buildPersistentSummary(dpfData)

        return NotificationCompat.Builder(context, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_car_header)
            .setContentTitle(tr(context.getString(R.string.notif_persistent_title)))
            .setContentText(summary)
            .setOngoing(true)             // Cannot be swiped away
            .setOnlyAlertOnce(true)       // No sound on updates
            .setContentIntent(tapIntent)
            .setSilent(true)
            .build()
    }

    private fun buildPersistentSummary(data: DpfData): String {
        val connected = if (data.bleConnected) tr("Połączono") else tr("Rozłączono")
        val soot = if (data.sootPercentage >= 0) "${"%.0f".format(data.sootPercentage)}%" else "– –"
        val egt  = if (data.egtCelsius >= 0) "${"%.0f".format(data.egtCelsius)}°C" else "– –"
        return tr("%s  •  Sadza: %s  •  EGT: %s").format(connected, soot, egt)
    }

    fun updatePersistentNotification(context: Context, notification: Notification) {
        NotificationManagerCompat.from(context)
            .notify(NOTIF_ID_PERSISTENT, notification)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Level 2 — Event heads-up notifications (all play custom chime)
    // ═════════════════════════════════════════════════════════════════════════

    /** EGT ≥ 450°C — Do not stop engine (could be passive regen). */
    fun notifyWarning(context: Context) {
        postEventNotification(
            context,
            title = tr(context.getString(R.string.notif_warning_title)),
            text  = tr(context.getString(R.string.notif_warning_text)),
            color = Color.parseColor("#F57F17")   // amber
        )
        Log.d(TAG, "⚠ WARNING notification posted")
    }

    /** EGT ≥ 550°C or ECU flag — Active regeneration confirmed. */
    fun notifyActive(context: Context) {
        postEventNotification(
            context,
            title    = tr(context.getString(R.string.notif_active_title)),
            text     = tr(context.getString(R.string.notif_active_text)),
            color    = Color.parseColor("#C62828"),   // red
            priority = NotificationCompat.PRIORITY_MAX
        )
        Log.d(TAG, "🔴 ACTIVE regen notification posted")
    }

    /** Regen cycle completed — EGT dropped below safe threshold. */
    fun notifyCompleted(context: Context) {
        postEventNotification(
            context,
            title = tr(context.getString(R.string.notif_complete_title)),
            text  = tr(context.getString(R.string.notif_complete_text)),
            color = Color.parseColor("#2E7D32")   // green
        )
        Log.d(TAG, "✅ COMPLETED regen notification posted")
    }

    /** BLE connection to OBD dongle lost — silent popup, no sound. */
    fun notifyBleLost(context: Context) {
        postConnectionNotification(
            context,
            title = tr(context.getString(R.string.notif_ble_lost_title)),
            text  = tr(context.getString(R.string.notif_ble_lost_text)),
            color = Color.parseColor("#546E7A")   // grey
        )
        Log.d(TAG, "📵 BLE lost notification posted (silent)")
    }

    /** BLE successfully connected — silent popup, no sound. */
    fun notifyBleConnected(context: Context, deviceName: String) {
        postConnectionNotification(
            context,
            title = tr("OBD połączony"),
            text  = tr("Połączono z %s — monitoring aktywny").format(deviceName),
            color = Color.parseColor("#2E7D32")   // green
        )
        Log.d(TAG, "✅ BLE connected notification posted (silent)")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Turbo cooldown notifications (silent — no MP3, no vibration)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Shown when the car stops after a trip — tells the driver to wait 45s
     * before turning off the engine. Silent popup on phone + Android Auto.
     */
    fun notifyCooldownStarted(context: Context) {
        postCooldownNotification(
            context,
            title = tr(context.getString(R.string.notif_cooldown_started_title)),
            text  = tr(context.getString(R.string.notif_cooldown_started_text)),
            color = Color.parseColor("#F57F17")   // amber
        )
        Log.d(TAG, "Cooldown started notification posted")
    }

    /**
     * Shown when the 45-second cooldown completes — engine is safe to stop.
     * Replaces the "in corso" notification.
     */
    fun notifyCooldownComplete(context: Context) {
        postCooldownNotification(
            context,
            title = tr(context.getString(R.string.notif_cooldown_complete_title)),
            text  = tr(context.getString(R.string.notif_cooldown_complete_text)),
            color = Color.parseColor("#2E7D32")   // green
        )
        Log.d(TAG, "Cooldown complete notification posted")
    }

    /** Cancels any visible cooldown notification (e.g. driver moved before 45s). */
    fun cancelCooldownNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_COOLDOWN)
        Log.d(TAG, "Cooldown notification cancelled")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Maintenance reminder notifications (silent — no sound, no vibration)
    // Each reminder has its own notification ID = 2000 + reminder.id
    // ═════════════════════════════════════════════════════════════════════════

    /** In scadenza tra < 1000 km — soft warning. */
    fun notifyMaintenanceWarning(context: Context, reminder: MaintenanceReminder, kmLeft: Long) {
        postMaintenanceNotification(
            context   = context,
            reminder  = reminder,
            title     = tr("%s — termin się zbliża").format(reminder.title),
            text      = tr("Termin za %,d km. Pamiętaj o umówieniu wizyty.").format(kmLeft),
            color     = Color.parseColor("#FF9500"),   // amber
            priority  = NotificationCompat.PRIORITY_DEFAULT
        )
        Log.d(TAG, "Maintenance WARNING: ${reminder.title} — $kmLeft km left")
    }

    /** Urgente: < 500 km — higher priority. */
    fun notifyMaintenanceUrgent(context: Context, reminder: MaintenanceReminder, kmLeft: Long) {
        postMaintenanceNotification(
            context   = context,
            reminder  = reminder,
            title     = "${reminder.title} — pilne!",
            text      = tr("Tylko %,d km do terminu. Umów wizytę od razu.").format(kmLeft),
            color     = Color.parseColor("#FF6B00"),   // deep orange
            priority  = NotificationCompat.PRIORITY_HIGH
        )
        Log.d(TAG, "Maintenance URGENT: ${reminder.title} — $kmLeft km left")
    }

    /** Scaduto — persistent until the user marks the maintenance done. */
    fun notifyMaintenanceOverdue(context: Context, reminder: MaintenanceReminder) {
        postMaintenanceNotification(
            context   = context,
            reminder  = reminder,
            title     = tr("%s — PO TERMINIE").format(reminder.title),
            text      = tr("Termin minął. Wykonaj serwis jak najszybciej."),
            color     = Color.parseColor("#FF3B30"),   // red
            priority  = NotificationCompat.PRIORITY_HIGH,
            autoCancel = false   // stays until dismissed
        )
        Log.d(TAG, "Maintenance OVERDUE: ${reminder.title}")
    }

    private fun postMaintenanceNotification(
        context: Context,
        reminder: MaintenanceReminder,
        title: String,
        text: String,
        color: Int,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
        autoCancel: Boolean = true
    ) {
        val notifId = (2000 + reminder.id).toInt()

        val tapIntent = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MaintenanceActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MAINTENANCE)
            .setSmallIcon(R.drawable.ic_car_header)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(color)
            .setColorized(true)
            .setAutoCancel(autoCancel)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission not granted: ${e.message}")
        }
    }

    private fun postCooldownNotification(context: Context, title: String, text: String, color: Int) {
        val tapIntent = PendingIntent.getActivity(
            context, NOTIF_ID_COOLDOWN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val carTapIntent = PendingIntent.getService(
            context, NOTIF_ID_COOLDOWN,
            Intent(context, DpfCarAppService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_COOLDOWN)
            .setSmallIcon(R.drawable.ic_car_header)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(color)
            .setColorized(true)
            .setAutoCancel(false)
            .setOngoing(false)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(carTapIntent)
                    .apply { getIconBitmap(context)?.let { setLargeIcon(it) } }
                    .build()
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_COOLDOWN, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission not granted: ${e.message}")
        }
    }

    // ─── Private factories ────────────────────────────────────────────────────

    /**
     * Posts a heads-up regen notification on CHANNEL_REGEN.
     * Plays dpf_monitor_sound.mp3 automatically (set on the channel).
     * Auto-dismisses after [EVENT_TIMEOUT_MS].
     *
     * On the car screen:
     *   - Popup heads-up with large Ford Focus icon
     *   - Plays through car speakers (IMPORTANCE_HIGH on the channel)
     *   - Tap → opens DpfScreen directly on the car display
     */
    private fun postEventNotification(
        context: Context,
        title: String,
        text: String,
        color: Int,
        priority: Int = NotificationCompat.PRIORITY_HIGH
    ) {
        // Phone tap: opens MainActivity on the handset
        val phoneTapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Car tap: opens DpfCarAppService → shows DpfScreen on the car display
        val carTapIntent = PendingIntent.getService(
            context, NOTIF_ID_EVENT,
            Intent(context, DpfCarAppService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REGEN)
            .setSmallIcon(R.drawable.ic_car_header)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(color)
            .setColorized(true)
            .setAutoCancel(true)
            .setTimeoutAfter(EVENT_TIMEOUT_MS)
            .setOnlyAlertOnce(false)             // sound plays every regen event
            .setContentIntent(phoneTapIntent)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(carTapIntent)     // tap → DpfScreen sul display
                    .apply { getIconBitmap(context)?.let { setLargeIcon(it) } }
                    .build()
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_EVENT, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission not granted: ${e.message}")
        }
    }

    /**
     * Posts a silent connection notification on CHANNEL_CONNECTION.
     * No sound, no vibration — just a heads-up popup.
     *
     * On the car screen: tap → opens DpfScreen so the driver can check the status.
     */
    private fun postConnectionNotification(
        context: Context,
        title: String,
        text: String,
        color: Int
    ) {
        val phoneTapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val carTapIntent = PendingIntent.getService(
            context, NOTIF_ID_CONNECTION,
            Intent(context, DpfCarAppService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_car_header)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(color)
            .setAutoCancel(true)
            .setTimeoutAfter(4_000L)   // dismiss after 4 s
            .setSilent(true)
            .setContentIntent(phoneTapIntent)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(carTapIntent)     // tap → DpfScreen sul display
                    .apply { getIconBitmap(context)?.let { setLargeIcon(it) } }
                    .build()
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_CONNECTION, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission not granted: ${e.message}")
        }
    }

    /**
     * Silent, sticky notification fired after a regen ECU-state capture completes.
     * Tapping it opens the ECU Scanner screen, where the user can share the log.
     * No sound/vibration — it's informational and read once the car is stopped.
     */
    fun notifyRegenCaptured(context: Context, pidCount: Int) {
        val tapIntent = PendingIntent.getActivity(
            context, NOTIF_ID_CAPTURE,
            Intent(context, EcuScanActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = tr("📸 Zapisano stan regeneracji")
        val text  = tr("Zapisano %s parametrów ECU. Otwórz i dotknij \"Udostępnij zapis\".").format(pidCount)

        val notification = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_car_header)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                tr("Zapisano %s parametrów ECU podczas regeneracji. ").format(pidCount) +
                tr("Gdy się zatrzymasz, otwórz skaner ECU i dotknij \"Udostępnij zapis regen.\", aby wysłać log.")
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(Color.parseColor("#4CAF50"))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_CAPTURE, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission not granted: ${e.message}")
        }
    }
}
