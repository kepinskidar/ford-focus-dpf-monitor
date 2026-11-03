package com.example.fordfocusdpfscan.data

// ═══════════════════════════════════════════════════════════════════════════════
// DtcCode.kt — Diagnostic Trouble Code model + description database.
//
// DTCs are read from the engine PCM (header 7E0) via OBD services:
//   03 — stored / confirmed (MIL on)
//   07 — pending (current drive cycle, not yet confirmed)
//   0A — permanent (cannot be cleared until the ECU re-verifies the repair)
//
// Descriptions are in Polish and focus on common diesel codes:
// DPF, EGR, common-rail, turbo/boost, glow plugs.
// Unknown codes fall back to a generic message with the raw code.
// ═══════════════════════════════════════════════════════════════════════════════

/** A single decoded DTC, e.g. "P2002". */
data class DtcCode(val code: String) {
    val description: String get() = DtcDatabase.describe(code)
}

/** Result of a full DTC read across the three services. */
data class DtcResult(
    val stored: List<DtcCode>,
    val pending: List<DtcCode>,
    val permanent: List<DtcCode>
) {
    val total: Int get() = stored.size + pending.size + permanent.size
    val isEmpty: Boolean get() = total == 0
}

object DtcDatabase {

    // Common powertrain codes for a diesel Focus. Not exhaustive by design —
    // unknown codes still display with a helpful fallback message.
    private val DESCRIPTIONS: Map<String, String> = mapOf(
        // ── DPF / cząstki stałe ──────────────────────────────────────────────
        "P2002" to "Skuteczność DPF poniżej progu (rząd 1)",
        "P2003" to "Skuteczność DPF poniżej progu (rząd 2)",
        "P242F" to "DPF — nadmierna ilość popiołu",
        "P2452" to "Czujnik ciśnienia różnicowego DPF — obwód",
        "P2453" to "Czujnik ciśnienia różnicowego DPF — nieprawidłowy sygnał",
        "P2454" to "Czujnik ciśnienia różnicowego DPF — sygnał niski",
        "P2455" to "Czujnik ciśnienia różnicowego DPF — sygnał wysoki",
        "P244A" to "Ciśnienie różnicowe DPF zbyt niskie",
        "P244B" to "Ciśnienie różnicowe DPF zbyt wysokie",
        "P2458" to "Nieprawidłowy czas trwania regeneracji DPF",
        "P2459" to "Nieprawidłowa częstotliwość regeneracji DPF",
        "P2463" to "DPF — nadmierne zapełnienie sadzą",
        "P1206" to "Wysoka ilość cząstek w DPF — wymagana regeneracja",
        // ── EGR ──────────────────────────────────────────────────────────────
        "P0401" to "Niewystarczający przepływ EGR",
        "P0402" to "Nadmierny przepływ EGR",
        "P0403" to "Obwód zaworu EGR",
        "P0404" to "Zawór EGR — zakres/parametry pracy",
        "P0405" to "Czujnik położenia EGR — sygnał niski",
        "P0406" to "Czujnik położenia EGR — sygnał wysoki",
        "P0409" to "Czujnik położenia EGR — obwód",
        "P046C" to "Czujnik położenia EGR 'A' — parametry pracy",
        // ── Układ paliwowy / common rail ─────────────────────────────────────
        "P0087" to "Ciśnienie w listwie/układzie paliwa zbyt niskie",
        "P0088" to "Ciśnienie w listwie/układzie paliwa zbyt wysokie",
        "P0089" to "Regulator ciśnienia paliwa — parametry pracy",
        "P0090" to "Regulator ciśnienia paliwa 1 — obwód",
        "P0093" to "Wykryto duży wyciek w układzie paliwowym",
        "P0094" to "Wykryto mały wyciek w układzie paliwowym",
        "P0191" to "Czujnik ciśnienia w listwie — zakres/parametry pracy",
        "P0192" to "Czujnik ciśnienia w listwie — sygnał niski",
        "P0193" to "Czujnik ciśnienia w listwie — sygnał wysoki",
        "P0201" to "Obwód wtryskiwacza cylindra 1",
        "P0202" to "Obwód wtryskiwacza cylindra 2",
        "P0203" to "Obwód wtryskiwacza cylindra 3",
        "P0204" to "Obwód wtryskiwacza cylindra 4",
        "P0263" to "Cylinder 1 — wyrównanie/udział w pracy",
        "P0266" to "Cylinder 2 — wyrównanie/udział w pracy",
        "P0269" to "Cylinder 3 — wyrównanie/udział w pracy",
        "P0272" to "Cylinder 4 — wyrównanie/udział w pracy",
        "P1290" to "Ciśnienie paliwa — nadciśnienie (Ford)",
        // ── Turbo / doładowanie ──────────────────────────────────────────────
        "P0234" to "Nadmierne doładowanie turbo (overboost)",
        "P0235" to "Czujnik ciśnienia doładowania 'A' — obwód",
        "P0236" to "Czujnik ciśnienia doładowania 'A' — zakres/parametry pracy",
        "P0243" to "Siłownik wastegate turbo — obwód",
        "P0245" to "Elektrozawór wastegate turbo 'A' — sygnał niski",
        "P0246" to "Elektrozawór wastegate turbo 'A' — sygnał wysoki",
        "P0299" to "Zbyt niskie doładowanie turbo (underboost)",
        "P132B" to "Regulacja ciśnienia doładowania — nadciśnienie (Ford)",
        // ── Powietrze / MAF / MAP ────────────────────────────────────────────
        "P0100" to "Obwód czujnika masy powietrza (MAF)",
        "P0101" to "Czujnik masy powietrza (MAF) — zakres/parametry pracy",
        "P0102" to "Czujnik masy powietrza (MAF) — sygnał niski",
        "P0103" to "Czujnik masy powietrza (MAF) — sygnał wysoki",
        "P0107" to "Czujnik ciśnienia w kolektorze (MAP) — sygnał niski",
        "P0108" to "Czujnik ciśnienia w kolektorze (MAP) — sygnał wysoki",
        "P0112" to "Czujnik temperatury powietrza dolotowego — sygnał niski",
        "P0113" to "Czujnik temperatury powietrza dolotowego — sygnał wysoki",
        // ── Świece żarowe / rozruch ──────────────────────────────────────────
        "P0670" to "Moduł sterowania świecami żarowymi — obwód",
        "P0671" to "Świeca żarowa cylindra 1 — obwód",
        "P0672" to "Świeca żarowa cylindra 2 — obwód",
        "P0673" to "Świeca żarowa cylindra 3 — obwód",
        "P0674" to "Świeca żarowa cylindra 4 — obwód",
        // ── Temperatura spalin (EGT) ─────────────────────────────────────────
        "P0544" to "Czujnik temperatury spalin 1 — obwód",
        "P0546" to "Czujnik temperatury spalin 1 — sygnał wysoki",
        "P242C" to "Czujnik temperatury spalin 3 — sygnał niski",
        "P242D" to "Czujnik temperatury spalin 3 — sygnał wysoki",
        // ── Płyn chłodzący / termostat ───────────────────────────────────────
        "P0128" to "Termostat — płyn chłodzący poniżej temperatury regulacji",
        "P0116" to "Czujnik temperatury płynu chłodzącego — zakres/parametry pracy",
        "P0117" to "Czujnik temperatury płynu chłodzącego — sygnał niski",
        "P0118" to "Czujnik temperatury płynu chłodzącego — sygnał wysoki",
        // ── Ogólne ───────────────────────────────────────────────────────────
        "P0016" to "Korelacja wału korbowego i rozrządu (rząd 1)",
        "P0335" to "Czujnik położenia wału korbowego 'A' — obwód",
        "P0340" to "Czujnik położenia wałka rozrządu 'A' — obwód",
        "P1000" to "Monitory OBD nieukończone (testy gotowości w toku)",
        "U0100" to "Utracono komunikację ze sterownikiem silnika (ECM/PCM)"
    )

    fun describe(code: String): String =
        DESCRIPTIONS[code.uppercase()]?.let { com.example.fordfocusdpfscan.i18n.tr(it) }
            ?: com.example.fordfocusdpfscan.i18n.tr("Kod spoza bazy — sprawdź w warsztacie (wyszukaj \"%s\")").format(code)
}
