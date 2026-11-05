package com.example.fordfocusdpfscan.ui

import com.example.fordfocusdpfscan.i18n.tr
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fordfocusdpfscan.R
import com.example.fordfocusdpfscan.data.RegenHistoryRepository
import com.example.fordfocusdpfscan.data.db.RegenSession
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════════════════
// HistoryActivity.kt — "📋 Storico" tab.
//
// Displays all recorded DPF regeneration sessions in a RecyclerView.
// Export button generates an HTML mechanic report and opens the share sheet.
// ═══════════════════════════════════════════════════════════════════════════════

class HistoryActivity : BaseTabActivity() {

    override val tabIndex = 2

    private lateinit var historyRepo: RegenHistoryRepository
    private lateinit var adapter: SessionAdapter

    // ── Mock sessions shown when DB is empty ──────────────────────────────────
    private val mockSessions: List<RegenSession> by lazy {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        listOf(
            RegenSession(
                id = -1, startTimestamp = now - 90 * day, endTimestamp = now - 90 * day + 28 * 60_000,
                preOdometerKm = 151_340, preSootPct = 72f, preLoadPct = 68f,
                preDeltaPKpa = 12.4f, preEgtC = 198f, preCoolantC = 88f,
                peakEgtC = 683f, peakDeltaPKpa = 18.2f,
                postSootPct = 18f, postLoadPct = 14f, postOdometerKm = 151_358,
                postEgtC = 312f, postCoolantC = 91f,
                durationMinutes = 28, regenType = "ACTIVE", result = "COMPLETED"
            ),
            RegenSession(
                id = -2, startTimestamp = now - 55 * day, endTimestamp = now - 55 * day + 24 * 60_000,
                preOdometerKm = 152_108, preSootPct = 65f, preLoadPct = 61f,
                preDeltaPKpa = 10.8f, preEgtC = 211f, preCoolantC = 87f,
                peakEgtC = 671f, peakDeltaPKpa = 16.5f,
                postSootPct = 12f, postLoadPct = 9f, postOdometerKm = 152_127,
                postEgtC = 298f, postCoolantC = 90f,
                durationMinutes = 24, regenType = "ACTIVE", result = "COMPLETED"
            ),
            RegenSession(
                id = -3, startTimestamp = now - 28 * day, endTimestamp = now - 28 * day + 9 * 60_000,
                preOdometerKm = 152_944, preSootPct = 58f, preLoadPct = 54f,
                preDeltaPKpa = 9.1f, preEgtC = 176f, preCoolantC = 84f,
                peakEgtC = 447f, peakDeltaPKpa = 11.3f,
                postSootPct = null, postLoadPct = null, postOdometerKm = null,
                postEgtC = null, postCoolantC = null,
                durationMinutes = 9, regenType = "WARNING", result = "INTERRUPTED"
            ),
            RegenSession(
                id = -4, startTimestamp = now - 14 * day, endTimestamp = now - 14 * day + 31 * 60_000,
                preOdometerKm = 153_512, preSootPct = 71f, preLoadPct = 67f,
                preDeltaPKpa = 13.6f, preEgtC = 203f, preCoolantC = 89f,
                peakEgtC = 689f, peakDeltaPKpa = 19.1f,
                postSootPct = 15f, postLoadPct = 11f, postOdometerKm = 153_538,
                postEgtC = 321f, postCoolantC = 92f,
                durationMinutes = 31, regenType = "ACTIVE", result = "COMPLETED"
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyRepo = RegenHistoryRepository(this)

        // ── RecyclerView ─────────────────────────────────────────────────────
        val recycler = findViewById<RecyclerView>(R.id.rvSessions)
        adapter = SessionAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // ── Observe sessions ──────────────────────────────────────────────────
        lifecycleScope.launch {
            historyRepo.sessions.collectLatest { sessions ->
                val mockBanner = findViewById<View>(R.id.tvMockBanner)
                val emptyView  = findViewById<View>(R.id.viewEmptyState)
                val statsGroup = findViewById<View>(R.id.layoutStats)

                if (sessions.isEmpty()) {
                    // Show mock data so the layout is always visible
                    mockBanner.visibility = View.VISIBLE
                    emptyView.visibility  = View.GONE
                    statsGroup.visibility = View.VISIBLE
                    adapter.submitList(mockSessions)
                    updateStats(mockSessions)
                    updateSootChart(mockSessions)
                } else {
                    // Real data — hide mock banner
                    mockBanner.visibility = View.GONE
                    emptyView.visibility  = View.GONE
                    statsGroup.visibility = View.VISIBLE
                    adapter.submitList(sessions)
                    updateStats(sessions)
                    updateSootChart(sessions)
                }
            }
        }

        // Tab bar highlighting + click listeners handled centrally by BaseTabActivity.

        // ── Export button ─────────────────────────────────────────────────────
        findViewById<View>(R.id.btnExport).setOnClickListener {
            exportReport()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Stats bar
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateStats(sessions: List<RegenSession>) {
        val completed  = sessions.count { it.result == "COMPLETED" }
        val avgDur     = sessions.filter { it.durationMinutes != null }
            .map { it.durationMinutes!! }.average()
            .let { if (it.isNaN()) 0 else it.toInt() }
        val avgSoot    = sessions
            .filter { it.preSootPct >= 0 && it.postSootPct != null }
            .map { it.preSootPct - it.postSootPct!! }.average()
            .let { if (it.isNaN()) 0.0 else it }

        findViewById<TextView>(R.id.tvTotalRegens).text   = sessions.size.toString()
        findViewById<TextView>(R.id.tvCompleted).text     = completed.toString()
        findViewById<TextView>(R.id.tvAvgDuration).text   = "${avgDur} min"
        findViewById<TextView>(R.id.tvAvgSootReduction).text = "−${"%.1f".format(avgSoot)}%"

        // ── DPF health: average distance between consecutive regens ──────────
        // Sessions are newest-first; the gap between adjacent odometer readings
        // is the distance the car covered between two regens. A shrinking gap
        // means the DPF is regenerating more often (possible clogging / oil issue).
        val odos = sessions.mapNotNull { it.preOdometerKm.takeIf { km -> km >= 0 } }
        val intervals = odos.zipWithNext { newer, older -> newer - older }.filter { it in 1..5000 }
        val tvInterval = findViewById<TextView>(R.id.tvRegenInterval)
        if (intervals.isNotEmpty()) {
            val avg = intervals.average().toInt()
            val hint = when {
                avg >= 400 -> tr("🟢 DPF w dobrej kondycji")
                avg >= 250 -> tr("🟡 interwał nieco krótki")
                else       -> tr("🔴 regeneruje zbyt często — sprawdź DPF/olej")
            }
            tvInterval.text = tr("🔧 Regeneracja średnio co ~%,d km  ·  %s").format(avg, hint)
            tvInterval.visibility = View.VISIBLE
        } else {
            tvInterval.visibility = View.GONE
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Soot BarChart — prima / dopo regen (last 8 sessions with data)
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateSootChart(sessions: List<RegenSession>) {
        val card  = findViewById<View>(R.id.cardSootChart)
        val chart = findViewById<BarChart>(R.id.chartSootHistory)

        // Only show sessions where we have both pre and post soot data
        val withData = sessions
            .filter { it.preSootPct >= 0 && it.postSootPct != null }
            .takeLast(8)

        if (withData.isEmpty()) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE

        val labels    = withData.mapIndexed { i, _ -> "#${sessions.size - withData.size + i + 1}" }
        val preGroup  = withData.mapIndexed { i, s -> BarEntry(i.toFloat(), s.preSootPct) }
        val postGroup = withData.mapIndexed { i, s -> BarEntry(i.toFloat(), s.postSootPct!!) }

        val preSet = BarDataSet(preGroup, tr("Przed")).apply {
            color = 0xFFFF3B30.toInt()
            setDrawValues(false)
        }
        val postSet = BarDataSet(postGroup, tr("Po")).apply {
            color = 0xFF00C853.toInt()
            setDrawValues(false)
        }

        val barData = BarData(preSet, postSet).apply { barWidth = 0.35f }

        chart.apply {
            data = barData
            groupBars(-0.5f, 0.1f, 0.05f)

            description.isEnabled = false
            legend.isEnabled      = false   // custom legend in XML
            setTouchEnabled(false)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)

            xAxis.apply {
                position         = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor        = 0xFF7A8FA6.toInt()
                textSize         = 9f
                granularity      = 1f
                setCenterAxisLabels(true)
                valueFormatter   = IndexAxisValueFormatter(labels)
                axisMinimum      = -0.5f
                axisMaximum      = withData.size.toFloat() - 0.5f
            }
            axisLeft.apply {
                textColor        = 0xFF7A8FA6.toInt()
                textSize         = 9f
                axisMinimum     = 0f
                setDrawGridLines(true)
                gridColor        = 0xFF1A1A2A.toInt()
                gridLineWidth    = 0.5f
            }
            axisRight.isEnabled = false

            animateY(600)
            invalidate()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HTML export + share
    // ═════════════════════════════════════════════════════════════════════════

    private fun exportReport() {
        lifecycleScope.launch {
            try {
                val html = historyRepo.generateHtmlReport(com.example.fordfocusdpfscan.ble.EngineProfile.active(this@HistoryActivity).displayName)
                val fileName = "DPF_Report_${
                    SimpleDateFormat("yyyyMMdd_HHmm", Locale("pl", "PL")).format(Date())
                }.html"
                val file = File(cacheDir, fileName)
                file.writeText(html, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    this@HistoryActivity,
                    "${packageName}.provider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type     = "text/html"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Raport DPF — ${com.example.fordfocusdpfscan.ble.EngineProfile.active(this@HistoryActivity).displayName}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, tr("Wyślij raport do mechanika")))

            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, tr("Błąd eksportu: %s").format(e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RecyclerView Adapter
    // ═════════════════════════════════════════════════════════════════════════

    inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.VH>() {

        private var items: List<RegenSession> = emptyList()

        fun submitList(list: List<RegenSession>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_regen_session, parent, false)
                .also { com.example.fordfocusdpfscan.i18n.I18n.apply(it) }
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], position + 1)
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {

            private val fmt = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("pl", "PL"))

            fun bind(s: RegenSession, num: Int) {
                val v = itemView
                val total = items.size

                v.findViewById<TextView>(R.id.tvSessionNum).text   = "#${total - num + 1}"
                v.findViewById<TextView>(R.id.tvSessionDate).text  = fmt.format(Date(s.startTimestamp))
                v.findViewById<TextView>(R.id.tvSessionOdo).text   =
                    if (s.preOdometerKm >= 0) "📍 %,d km · ${s.durationMinutes ?: "—"} min".format(s.preOdometerKm)
                    else "📍 — · ${s.durationMinutes ?: "—"} min"

                // Regen type badge — detection is always EGT/temperature-based
                // (no ECU regen-flag PID exists for the EDC17C70), so we label the
                // source honestly rather than claiming a "Forzata · ECU" reading.
                val tvType = v.findViewById<TextView>(R.id.tvRegenType)
                tvType.text = tr("🌡  Wykryta po temperaturze")
                tvType.setTextColor(0xFF4F8EF7.toInt())
                tvType.setBackgroundColor(0x224F8EF7)

                // Soot before → after
                val sootPre  = if (s.preSootPct  >= 0) "${"%.0f".format(s.preSootPct)}%" else "—"
                val sootPost = s.postSootPct?.let { "${"%.0f".format(it)}%" } ?: "—"
                v.findViewById<TextView>(R.id.tvSootChange).text = "$sootPre → $sootPost"

                // Peak EGT
                // "inlet / outlet" peak
                val peakIn  = if (s.peakEgtC > 0) "%.0f".format(s.peakEgtC) else "–"
                val peakOut = if (s.peakEgtOutC > 0) "%.0f".format(s.peakEgtOutC) else "–"
                v.findViewById<TextView>(R.id.tvPeakEgt).text = "$peakIn / $peakOut °C"

                // Coolant (indicates engine was warm enough)
                v.findViewById<TextView>(R.id.tvCoolant).text =
                    if (s.preCoolantC >= 0) "${"%.0f".format(s.preCoolantC)} °C" else "—"

                // Result badge
                val tvResult = v.findViewById<TextView>(R.id.tvResult)
                when (s.result) {
                    "COMPLETED"     -> { tvResult.text = tr("✓ Zakończona");            tvResult.setTextColor(0xFF34C759.toInt()) }
                    "ENDED_NATURAL" -> { tvResult.text = tr("✓ Zakończona (naturalnie)"); tvResult.setTextColor(0xFF34C759.toInt()) }
                    "INTERRUPTED"   -> { tvResult.text = tr("⚠ Przerwana");           tvResult.setTextColor(0xFFFF9F0A.toInt()) }
                    "IN_PROGRESS"   -> { tvResult.text = tr("⟳ W trakcie");             tvResult.setTextColor(0xFF4F8EF7.toInt()) }
                    else            -> { tvResult.text = s.result }
                }

                // Warning note for interrupted regens
                val warnView = v.findViewById<TextView>(R.id.tvWarningNote)
                if (s.result == "INTERRUPTED") {
                    warnView.visibility = View.VISIBLE
                    warnView.text = tr("⚠ Regeneracja przerwana — sadza nie została zredukowana. Rozważ regenerację wymuszoną (Ford IDS).")
                } else {
                    warnView.visibility = View.GONE
                }
            }
        }
    }
}
