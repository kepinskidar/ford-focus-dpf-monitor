package com.example.fordfocusdpfscan.car

import com.example.fordfocusdpfscan.i18n.tr
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.data.DpfData
import com.example.fordfocusdpfscan.data.DpfRepository
import com.example.fordfocusdpfscan.data.RegenStatus
import com.example.fordfocusdpfscan.data.RegenStrategy
import com.example.fordfocusdpfscan.service.DpfForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

// ═══════════════════════════════════════════════════════════════════════════════
// DpfScreen.kt — Android Auto main dashboard screen.
//
// ListTemplate — 3 righe (fit su schermo, nessun scroll):
//   ┌─────────────────────────────────────────────────────┐
//   │ Filtro DPF                                          │
//   │ Soot X%  ·  Load X%  ·  ΔP X kPa                  │
//   ├─────────────────────────────────────────────────────┤
//   │ Rigenerazione                                       │
//   │ Inattiva — puoi spegnere  ·  Temp EGT              │
//   ├─────────────────────────────────────────────────────┤
//   │ Info Motore                                         │
//   │ EGT in X°C  ·  EGT out X°C  ·  Motore X°C          │
//   └─────────────────────────────────────────────────────┘
//
// ActionStrip: [Ricollega] — riconnette il dongle OBD2
// CarToast: su ogni transizione di stato (WARNING / ACTIVE / COMPLETED)
// Il timer di raffreddamento turbo viene gestito come notifica di background
// da DpfForegroundService — non più mostrato su questa schermata.
// ═══════════════════════════════════════════════════════════════════════════════

class DpfScreen(carContext: CarContext) : Screen(carContext) {

    private val TAG = "DpfScreen"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var previousRegenStatus: RegenStatus = RegenStatus.INACTIVE
    private var currentData: DpfData = DpfData()

    /** Throttle invalidate() — Car App Library template API ha un rate limit ~1/s */
    private var lastInvalidateTime = 0L
    private val INVALIDATE_INTERVAL_MS = 1_000L

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    DpfRepository.dpfData.collectLatest { data ->
                        if (data.regenStatus != previousRegenStatus) {
                            safeShowCarToast(data.regenStatus)
                            previousRegenStatus = data.regenStatus
                        }
                        currentData = data
                        safeInvalidate()
                    }
                }
            }
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
    }

    private fun safeInvalidate() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidateTime < INVALIDATE_INTERVAL_MS) return
        lastInvalidateTime = now
        try {
            invalidate()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "invalidate() outside car lifecycle — ignored: ${e.message}")
        }
    }

    private fun safeShowCarToast(newStatus: RegenStatus) {
        try { showCarToast(newStatus) } catch (e: IllegalStateException) {
            Log.w(TAG, "CarToast outside car lifecycle — ignored: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Template — ListTemplate con 3 righe
    // ═════════════════════════════════════════════════════════════════════════

    override fun onGetTemplate(): Template {
        val data = currentData
        return ListTemplate.Builder()
            .setTitle(tr(carContext.getString(R.string.car_screen_title)))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(buildActionStrip())
            .setSingleList(
                ItemList.Builder()
                    .addItem(buildDpfRow(data))
                    .addItem(buildRegenRow(data))
                    .addItem(buildInfoMotoreRow(data))
                    .build()
            )
            .build()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Riga 1 — FILTRO DPF: Soot · Load · ΔP
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildDpfRow(data: DpfData): Row {
        val soot   = if (data.sootPercentage >= 0)       "${data.sootPercentage.toInt()}%"              else "– –"
        val load   = if (data.loadPercentage >= 0)       "${data.loadPercentage.toInt()}%"              else "– –"
        val deltaP = if (data.dpfDeltaPressureKpa >= 0)  "${"%.1f".format(data.dpfDeltaPressureKpa)} kPa" else "– –"

        val worst = maxOf(
            data.sootPercentage.takeIf { it >= 0f } ?: 0f,
            data.loadPercentage.takeIf { it >= 0f } ?: 0f
        )
        val color = when {
            data.sootPercentage < 0 && data.loadPercentage < 0 -> CarColor.DEFAULT
            worst >= 80f -> CarColor.RED
            worst >= 60f -> CarColor.YELLOW
            else         -> CarColor.GREEN
        }

        return Row.Builder()
            .setTitle(tr("Filtr DPF"))
            .addText(coloredSpan(tr("Sadza %s  ·  Obciążenie %s  ·  ΔP %s").format(soot, load, deltaP), color))
            .build()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Riga 2 — STATUS RIGENERAZIONE
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildRegenRow(data: DpfData): Row {
        val (statusStr, statusColor) = when (data.regenStatus) {
            RegenStatus.INACTIVE  -> tr("Nieaktywna — można zgasić silnik")  to CarColor.GREEN
            RegenStatus.WARNING   -> tr("Uwaga — nie gasić silnika") to CarColor.YELLOW
            RegenStatus.ACTIVE    -> tr("AKTYWNA — nie gasić silnika!")    to CarColor.RED
            RegenStatus.COMPLETED -> tr("Zakończona")                to CarColor.GREEN
        }

        val source = when (data.regenStrategy) {
            RegenStrategy.EGT_TEMP  -> tr("  ·  Temp. EGT")
            RegenStrategy.SOOT_DROP -> tr("  ·  Sadza ↓")
            RegenStrategy.EXOTHERM  -> tr("  ·  ΔT filtra")
            RegenStrategy.NONE      -> ""
        }

        return Row.Builder()
            .setTitle(tr("Regeneracja"))
            .addText(coloredSpan("$statusStr$source", statusColor))
            .build()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Riga 3 — INFO MOTORE: EGT ingresso · EGT uscita · Temp motore
    //
    // EGT ingresso (pre-DPF) ed uscita (post-DPF) permettono di vedere l'esotermia
    // del filtro (uscita ≫ ingresso = combustione soot in corso). La temperatura
    // motore (refrigerante) è il terzo valore utile a colpo d'occhio.
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildInfoMotoreRow(data: DpfData): Row {
        val egtInStr   = if (data.egtCelsius >= 0)   "${data.egtCelsius.toInt()} °C"   else "– –"
        val egtOutStr  = if (data.egtPostDpfC >= 0)  "${data.egtPostDpfC.toInt()} °C"  else "– –"
        val coolantStr = if (data.coolantTempC >= 0) "${data.coolantTempC.toInt()} °C" else "– –"

        val egtColor = when {
            data.egtCelsius < 0    -> CarColor.DEFAULT
            data.egtCelsius >= 550 -> CarColor.RED
            data.egtCelsius >= 450 -> CarColor.YELLOW
            else                   -> CarColor.GREEN
        }

        return Row.Builder()
            .setTitle(tr("Silnik"))
            .addText(coloredSpan(
                tr("EGT wlot %s  ·  EGT wylot %s  ·  Silnik %s").format(egtInStr, egtOutStr, coolantStr),
                egtColor
            ))
            .build()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ActionStrip — [Ricollega] in alto a destra
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildActionStrip(): ActionStrip {
        val reconnectAction = Action.Builder()
            .setTitle(tr(carContext.getString(R.string.car_action_reconnect)))
            .setOnClickListener {
                carContext.startForegroundService(
                    Intent(carContext, DpfForegroundService::class.java).apply {
                        action = DpfForegroundService.ACTION_RECONNECT
                    }
                )
                CarToast.makeText(
                    carContext,
                    tr("Ponowne łączenie z Android-Vlink…"),
                    CarToast.LENGTH_SHORT
                ).show()
            }
            .build()

        return ActionStrip.Builder()
            .addAction(reconnectAction)
            .build()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CarToast — messaggio sovrapposto in auto su ogni transizione di stato
    // ═════════════════════════════════════════════════════════════════════════

    private fun showCarToast(newStatus: RegenStatus) {
        val message = when (newStatus) {
            RegenStatus.WARNING   -> tr(carContext.getString(R.string.car_toast_warning))
            RegenStatus.ACTIVE    -> tr(carContext.getString(R.string.car_toast_active))
            RegenStatus.COMPLETED -> tr(carContext.getString(R.string.car_toast_complete))
            RegenStatus.INACTIVE  -> return
        }
        CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helper — SpannableString con CarColor span
    // ═════════════════════════════════════════════════════════════════════════

    private fun coloredSpan(text: String, color: CarColor): SpannableString =
        SpannableString(text).apply {
            setSpan(
                ForegroundCarColorSpan.create(color),
                0, text.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
}
