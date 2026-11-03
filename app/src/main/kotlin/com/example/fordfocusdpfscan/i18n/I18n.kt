package com.example.fordfocusdpfscan.i18n

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// I18n.kt — runtime translation (Polish source text → English / German / Italian).
//
// The Polish text is the "key". Translations live in assets/i18n.tsv:
//     <polish>\t<english>\t<german>\t<italian>      (\n, \t and \\ are escaped)
// A string without a translation (or with a different number of %-placeholders)
// is shown in Polish, so a missing entry can never crash the app.
//
// • tr("Polski tekst")         — translate a string in code (also used by the
//                                 Android Auto list and notifications)
// • I18n.apply(view)           — translate the static texts of an inflated layout
// • I18n.showLanguageDialog()  — language picker (first run + settings)
// ═══════════════════════════════════════════════════════════════════════════════

fun tr(pl: String): String = I18n.tr(pl)

object I18n {

    private const val PREFS = "focus_prefs"
    private const val KEY_LANG = "app_language"
    private const val KEY_CHOSEN = "app_language_chosen"

    /** code → name shown in the picker (always in its own language). */
    val LANGUAGES = listOf("pl" to "Polski", "en" to "English", "de" to "Deutsch", "it" to "Italiano")

    @Volatile private var lang: String = "pl"
    @Volatile private var appContext: Context? = null
    @Volatile private var dict: HashMap<String, Array<String>>? = null

    private val SPEC = Regex("%(\\d+\\$)?[-#+0,(]*\\d*(\\.\\d+)?[sdfxXeEgGcb]")

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lang = p.getString(KEY_LANG, null) ?: systemDefault()
    }

    fun lang(): String = lang

    fun isChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHOSEN, false)

    fun setLang(context: Context, code: String) {
        if (LANGUAGES.none { it.first == code }) return
        lang = code
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, code).putBoolean(KEY_CHOSEN, true).apply()
    }

    /** Device language if supported, otherwise English. */
    private fun systemDefault(): String {
        val l = Locale.getDefault().language
        return if (LANGUAGES.any { it.first == l }) l else "en"
    }

    /** Language name for the AI system prompt. */
    fun answerLanguage(): String = when (lang) {
        "en" -> "English"
        "de" -> "Deutsch (German)"
        "it" -> "Italiano (Italian)"
        else -> "polski (Polish)"
    }

    // ── translation ─────────────────────────────────────────────────────────

    fun tr(pl: String): String {
        if (lang == "pl" || pl.isEmpty()) return pl
        val d = dictionary() ?: return pl
        val row = d[pl] ?: return pl
        val idx = when (lang) { "en" -> 0; "de" -> 1; "it" -> 2; else -> return pl }
        val t = row.getOrNull(idx)
        if (t.isNullOrEmpty()) return pl
        // Safety: a wrong number of format specifiers would crash String.format().
        if (SPEC.findAll(t).count() != SPEC.findAll(pl).count()) return pl
        return t
    }

    private fun dictionary(): HashMap<String, Array<String>>? {
        dict?.let { return it }
        val ctx = appContext ?: return null
        synchronized(this) {
            dict?.let { return it }
            val map = HashMap<String, Array<String>>(1024)
            try {
                ctx.assets.open("i18n.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line[0] == '#') continue
                        val c = line.split('\t')
                        if (c.size < 2) continue
                        map[unesc(c[0])] = arrayOf(
                            unesc(c.getOrElse(1) { "" }),
                            unesc(c.getOrElse(2) { "" }),
                            unesc(c.getOrElse(3) { "" })
                        )
                    }
                }
            } catch (_: Exception) { /* no dictionary → Polish */ }
            dict = map
            return map
        }
    }

    private fun unesc(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    // ── layouts ─────────────────────────────────────────────────────────────

    /** Translate the static texts / hints / content descriptions of a view tree. */
    fun apply(root: View?) {
        if (root == null || lang == "pl") return
        walk(root)
    }

    private fun walk(v: View) {
        v.contentDescription?.toString()?.let { s ->
            val t = tr(s); if (t != s) v.contentDescription = t
        }
        if (v is TextInputLayout) {
            v.hint?.toString()?.let { s -> val t = tr(s); if (t != s) v.hint = t }
        }
        if (v is TextView) {
            if (v !is EditText) {
                val s = v.text?.toString()
                if (!s.isNullOrEmpty()) { val t = tr(s); if (t != s) v.text = t }
            }
            v.hint?.toString()?.let { s -> if (s.isNotEmpty()) { val t = tr(s); if (t != s) v.hint = t } }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    // ── dialogs / restart ───────────────────────────────────────────────────

    /**
     * Language picker. [cancelable] = false on first run. [onChosen] is called after the
     * choice has been saved (the caller restarts the UI so every screen uses it).
     */
    fun showLanguageDialog(context: Context, cancelable: Boolean, onChosen: () -> Unit) {
        val current = LANGUAGES.indexOfFirst { it.first == lang }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Język / Language / Sprache / Lingua")
            .setSingleChoiceItems(LANGUAGES.map { it.second }.toTypedArray(), current) { dialog, which ->
                setLang(context, LANGUAGES[which].first)
                dialog.dismiss()
                onChosen()
            }
            .setCancelable(cancelable)
            .apply { if (cancelable) setNegativeButton("✕", null) }
            .show()
    }

    /** Relaunch the app from the splash screen so all screens pick up the new language. */
    fun restartApp(context: Context) {
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(i)
    }
}
