package com.example.fordfocusdpfscan.ui

import com.example.fordfocusdpfscan.i18n.tr
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.example.fordfocusdpfscan.BuildConfig
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.ble.EngineProfile
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════════════════
// BaseTabActivity.kt — Base class for the 3 main tab screens.
//
// Provides:
//   1. Body-only entrance animation (header + footer stay fixed).
//   2. Active tab highlighting (no per-activity boilerplate needed).
//   3. Tab click listeners (centralized — no per-activity boilerplate needed).
//   4. Horizontal swipe-to-navigate between tabs.
//
// Each layout MUST have a view with id=contentBody that wraps the scrollable
// body area. The header (<include layout_tab_bar>) and footer stay outside it.
//
// Swipe LEFT  → next tab  (Monitor → Diagnostica → Storico)
// Swipe RIGHT → prev tab  (Storico → Diagnostica → Monitor)
// ═══════════════════════════════════════════════════════════════════════════════

abstract class BaseTabActivity : AppCompatActivity() {

    /** Position of this tab: 0 = Monitor, 1 = Diagnostica, 2 = Storico, 3 = Manutenzione. */
    abstract val tabIndex: Int

    private lateinit var gestureDetector: GestureDetectorCompat

    // ── Swipe thresholds ──────────────────────────────────────────────────────
    private val SWIPE_MIN_DISTANCE = 120   // px — minimum horizontal travel
    private val SWIPE_MIN_VELOCITY = 200   // px/s — minimum fling speed
    private val SWIPE_MAX_OFF_AXIS  = 0.5f  // diffY/diffX — keeps swipes horizontal

    companion object {
        /**
         * Direction for the body entrance animation on the *entering* activity.
         *  +1 → entering from the right (forward, next tab)
         *  -1 → entering from the left  (backward, previous tab)
         *   0 → no animation (cold start / direct launch)
         */
        var pendingSlideDir = 0
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onPostCreate(savedInstanceState: android.os.Bundle?) {
        super.onPostCreate(savedInstanceState)
        gestureDetector = GestureDetectorCompat(this, TabSwipeListener())
        highlightActiveTab()
        setupTabClickListeners()
        animateBodyEntrance()

        // Keep the footer version label in sync with build.gradle on EVERY tab
        // (not just Monitor). The layout's hardcoded default would otherwise show
        // a stale version on Diagnostica / Storico / Manutenzione.
        findViewById<TextView>(R.id.tvFooterVersion)?.text = "v${BuildConfig.VERSION_NAME}"

        com.example.fordfocusdpfscan.i18n.I18n.apply(window.decorView)

        // Vehicle subtitle = active engine profile; tap for profile / language.
        findViewById<TextView>(R.id.tvVehicleSubtitle)?.let { tv ->
            tv.text = "${EngineProfile.active(this).subtitle} ▾"
            tv.setOnClickListener { showSettingsMenu(tv) }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Engine profile picker
    // ═════════════════════════════════════════════════════════════════════════

    private fun showSettingsMenu(subtitleView: TextView) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(arrayOf(tr("Profil silnika"), "Język / Language")) { _, which ->
                if (which == 0) showEngineProfileDialog(subtitleView)
                else com.example.fordfocusdpfscan.i18n.I18n.showLanguageDialog(this, cancelable = true) {
                    com.example.fordfocusdpfscan.i18n.I18n.restartApp(this)
                }
            }
            .show()
    }

    private fun showEngineProfileDialog(subtitleView: TextView) {
        val profiles = EngineProfile.ALL
        val current = EngineProfile.active(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr("Profil silnika"))
            .setSingleChoiceItems(
                profiles.map { it.displayName }.toTypedArray(),
                profiles.indexOfFirst { it.id == current.id }
            ) { dialog, which ->
                EngineProfile.setActive(this, profiles[which])
                subtitleView.text = "${profiles[which].subtitle} ▾"
                Toast.makeText(
                    this,
                    tr("Profil zmieniony. Rozłącz i połącz ponownie adapter OBD2."),
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(tr("Anuluj"), null)
            .show()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tab highlight — marks the current tab as active
    // ═════════════════════════════════════════════════════════════════════════

    private fun highlightActiveTab() {
        val tabIds = listOf(R.id.tabMonitor, R.id.tabDiagnostica, R.id.tabStorico, R.id.tabManutenzione)
        tabIds.forEachIndexed { index, id ->
            val tv = findViewById<TextView>(id) ?: return@forEachIndexed
            if (index == tabIndex) {
                tv.setBackgroundResource(R.drawable.bg_tab_active)
                tv.setTextColor(getColor(R.color.text_primary))
            } else {
                tv.setBackgroundResource(R.drawable.bg_tab_inactive)
                tv.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tab click listeners — navigate on tap
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupTabClickListeners() {
        val tabMap = mapOf(
            R.id.tabMonitor      to 0,
            R.id.tabDiagnostica  to 1,
            R.id.tabStorico      to 2,
            R.id.tabManutenzione to 3
        )
        for ((id, index) in tabMap) {
            if (index == tabIndex) continue   // already here — tapping does nothing
            val v = findViewById<View>(id) ?: continue
            v.setOnClickListener { navigateToTab(index, goingForward = index > tabIndex) }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Body-only entrance animation
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Slides [R.id.contentBody] in from the appropriate side based on
     * [pendingSlideDir] set by the *exiting* activity before startActivity().
     * The header and footer are outside contentBody so they appear instantly —
     * giving the illusion that only the page content changes.
     */
    private fun animateBodyEntrance() {
        val dir = pendingSlideDir
        if (dir == 0) return          // cold start — no animation
        pendingSlideDir = 0

        val body = findViewById<View>(R.id.contentBody) ?: return
        val screenW = resources.displayMetrics.widthPixels.toFloat()

        // Position off-screen on the side we're coming from
        body.translationX = dir * screenW

        // Animate smoothly to its resting position
        body.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Touch dispatch — feed all events to the gesture detector
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * We only OBSERVE here (always return super). This lets ScrollViews and
     * RecyclerViews handle vertical scroll normally while still detecting flings.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Swipe gesture listener
    // ═════════════════════════════════════════════════════════════════════════

    private inner class TabSwipeListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent) = true  // required to receive subsequent events

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val startX = e1?.x ?: return false
            val startY = e1.y

            val diffX = e2.x - startX
            val diffY = e2.y - startY

            // Must be primarily horizontal and fast enough
            if (abs(diffX) < SWIPE_MIN_DISTANCE) return false
            if (abs(velocityX) < SWIPE_MIN_VELOCITY) return false
            if (abs(diffY) / abs(diffX) > SWIPE_MAX_OFF_AXIS) return false

            return if (diffX < 0) {
                // ← Swipe left: go to NEXT tab
                navigateToTab(tabIndex + 1, goingForward = true)
            } else {
                // → Swipe right: go to PREVIOUS tab
                navigateToTab(tabIndex - 1, goingForward = false)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Navigation
    // ═════════════════════════════════════════════════════════════════════════

    private fun navigateToTab(index: Int, goingForward: Boolean): Boolean {
        val targetClass: Class<*> = when (index) {
            0 -> MainActivity::class.java
            1 -> DiagnosticaActivity::class.java
            2 -> HistoryActivity::class.java
            3 -> MaintenanceActivity::class.java
            else -> return false   // out of range — ignore
        }
        // Tell the entering activity which side to slide in from.
        // goingForward (next tab) → body enters from the right (+1 * screenW).
        // goingBackward (prev tab) → body enters from the left  (-1 * screenW).
        pendingSlideDir = if (goingForward) 1 else -1
        startActivity(Intent(this, targetClass))
        overridePendingTransition(0, 0)   // suppress full-screen animation
        finish()
        return true
    }
}
