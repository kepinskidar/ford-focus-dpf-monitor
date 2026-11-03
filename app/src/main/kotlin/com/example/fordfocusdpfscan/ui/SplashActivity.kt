package com.example.fordfocusdpfscan.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.i18n.I18n

// ═══════════════════════════════════════════════════════════════════════════════
// SplashActivity — app entry point.
//
// Animation sequence (total ≈ 2.4 s):
//   0 ms   — icon scales in from 60 % + fades in  (600 ms, overshoot)
//   350 ms — app name fades + slides up             (400 ms, decelerate)
//   550 ms — car subtitle fades in                  (350 ms)
//   800 ms — divider + credits fade in              (350 ms)
//   1900 ms — whole screen fades out                (350 ms)
//   2250 ms — MainActivity starts (no animation)
// ═══════════════════════════════════════════════════════════════════════════════

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch: ask for the language, then rebuild this screen in that language.
        if (!I18n.isChosen(this)) {
            I18n.showLanguageDialog(this, cancelable = false) { recreate() }
            return
        }

        setContentView(R.layout.activity_splash)
        I18n.apply(window.decorView)

        val icon     = findViewById<View>(R.id.splashIcon)
        val title    = findViewById<View>(R.id.splashTitle)
        val subtitle = findViewById<View>(R.id.splashSubtitle)
        (subtitle as? android.widget.TextView)?.text =
            com.example.fordfocusdpfscan.ble.EngineProfile.active(this).subtitle
        val divider  = findViewById<View>(R.id.splashDivider)
        val credits  = findViewById<View>(R.id.splashCredits)
        val root     = findViewById<View>(R.id.splashRoot)

        // ── 1. Icon — scale + fade with overshoot bounce ──────────────────────
        icon.scaleX = 0.6f
        icon.scaleY = 0.6f
        icon.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        // ── 2. Title — fade + slide up ────────────────────────────────────────
        title.translationY = 24f
        title.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(350)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // ── 3. Subtitle — simple fade ─────────────────────────────────────────
        subtitle.animate()
            .alpha(1f)
            .setDuration(350)
            .setStartDelay(550)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // ── 4. Divider + Credits — fade together ──────────────────────────────
        divider.animate()
            .alpha(1f)
            .setDuration(350)
            .setStartDelay(800)
            .start()

        credits.animate()
            .alpha(1f)
            .setDuration(350)
            .setStartDelay(800)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // ── 5. Fade out whole screen → start MainActivity ─────────────
                root.animate()
                    .alpha(0f)
                    .setDuration(350)
                    .setStartDelay(1100)   // linger so user can read it
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                        )
                        // No slide animation — screen is already faded to black
                        overridePendingTransition(0, 0)
                        finish()
                    }
                    .start()
            }
            .start()
    }
}
