package com.startupmini.nyachat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Token warna semantik (audit UI/UX P2.5): semua warna bermakna (pemasukan,
 * pengeluaran, identitas AI, tag finansial, identitas anggota, palet kategori)
 * dikumpulkan di sini dalam varian light + dark, lalu disediakan lewat
 * [LocalSemanticColors] oleh [CoupleFinanceTheme].
 *
 * Sebelumnya tiap layar mendeteksi mode gelap sendiri lewat
 * `colorScheme.background.luminance() < 0.5f` (rapuh & berulang) dan beberapa
 * komponen lupa memakai varian dark (kontras badge finansial jatuh ke ~1.5:1).
 * Nilai warna identik dengan palet lama di [Color.kt] — hanya aksesnya yang
 * terpusat, sehingga tidak ada perubahan visual.
 */
@Immutable
data class SemanticColors(
    /** true saat tema gelap — pengganti pemeriksaan luminance manual. */
    val isDark: Boolean,
    /** Teks/ikon pemasukan (kontras AA di atas latar light & dark). */
    val income: Color,
    /** Latar lembut bernuansa pemasukan (ikon/badge). */
    val incomeBg: Color,
    /** Teks/ikon pengeluaran. */
    val expense: Color,
    /** Latar lembut bernuansa pengeluaran. */
    val expenseBg: Color,
    /** Identitas AI (ikon, border, spinner). */
    val ai: Color,
    /** Latar bubble/kartu AI. */
    val aiBg: Color,
    /** Teks AI di atas [aiBg] (lebih gelap di light mode supaya AA). */
    val aiText: Color,
    /** Latar badge tag finansial pemasukan di bubble chat. */
    val moneyTagIncomeBg: Color,
    /** Latar badge tag finansial pengeluaran di bubble chat. */
    val moneyTagExpenseBg: Color,
    /** Latar badge CAMPURAN (pemasukan+pengeluaran) — gradien pelangi pastel. */
    val moneyTagMixedBg: List<Color>,
    /** Teks/ikon di atas gradien pelangi badge campuran (mode-aware kontras). */
    val moneyTagMixedText: Color,
    /** Identitas anggota "istri"/perempuan. */
    val wife: Color,
    /** Identitas anggota "suami"/laki-laki (= biru brand). */
    val husband: Color,
    /** Palet 7 warna kategori chart, sudah mode-aware. */
    val categoryPalette: List<Color>,

    // ── Token UX Round-2 (C6): semua warna bermakna di bawah ini mode-aware ──
    /** Centang "pesan dibaca" (kontras AA di atas bubble terang & gelap). */
    val receiptRead: Color,
    /** Titik "pesan diterima, belum dibaca" (netral, bukan hijau). */
    val receiptPending: Color,
    /** Urutan segmen gradien pelangi (indikator "semua dibaca"). */
    val rainbowSegment: List<Color>,
    /** Status sinkronisasi berhasil. */
    val syncOk: Color,
    /** Status sinkronisasi tertunda / sedang berjalan. */
    val syncPending: Color,
    /** Status sinkronisasi gagal. */
    val syncError: Color,
    /**
     * Aksen identitas AI (turunan brand blue, disesuaikan per mode).
     * D-L3: sengaja DIBEDAKAN dari [ai] — light memakai biru teks lebih gelap,
     * dark memakai #AAC7FF sementara [ai] memakai [AiBlueDarkBright].
     */
    val aiAccent: Color,

    // ── Token design-system tambahan (audit C / D-L1, D-L2, D-M2) ──
    /**
     * Aksen brand hijau mode-aware (D-M2 / B-M15). DIGUNAKAN oleh dialog/onboarding
     * lewat `LocalSemanticColors.current.brandAccent` — jangan hardcode #007A49.
     */
    val brandAccent: Color,
    /** Warna teks/ikon di atas latar avatar inisial (D-L1). */
    val onAvatar: Color,
    /** Warna konten (teks/ikon) di atas kontainer tombol AI (D-L2). */
    val aiOnContainer: Color
)

val LightSemanticColors = SemanticColors(
    isDark = false,
    income = IncomeGreen,
    incomeBg = IncomeGreenLight,
    expense = ExpenseRed,
    expenseBg = ExpenseRedLight,
    ai = AiBlue,
    aiBg = AiBlueLight,
    aiText = AiBlueText,
    moneyTagIncomeBg = MoneyTagIncomeBg,
    moneyTagExpenseBg = MoneyTagExpenseBg,
    moneyTagMixedBg = MoneyTagMixedBgLight,
    moneyTagMixedText = MoneyTagMixedTextLight,
    wife = WifePink,
    husband = HusbandBlue,
    categoryPalette = CategoryColorsLight,
    receiptRead = Color(0xFF2E7D32),
    receiptPending = Color(0xFF757575),
    rainbowSegment = listOf(
        Color(0xFFF44336),
        Color(0xFFFF9800),
        Color(0xFF4CAF50),
        Color(0xFF2196F3)
    ),
    syncOk = IncomeGreen,
    syncPending = Color(0xFF8D6E00),
    syncError = ExpenseRed,
    aiAccent = AiBlueText,
    brandAccent = BrandGreen,      // #007A49 — hijau brand Nyachat
    onAvatar = Color.White,
    aiOnContainer = Color.White     // tombol AI light memakai latar biru brand → teks putih
)

val DarkSemanticColors = SemanticColors(
    isDark = true,
    income = IncomeGreenDark,
    incomeBg = MoneyTagIncomeDark,
    expense = ExpenseRedDark,
    expenseBg = MoneyTagExpenseDark,
    ai = AiBlueDarkBright, // D-L3: dibedakan dari aiAccent (#AAC7FF)
    aiBg = Color.Transparent, // dark: bubble/kartu AI memakai surfaceVariant tema
    aiText = AiBlueDark,
    moneyTagIncomeBg = MoneyTagIncomeDark,
    moneyTagExpenseBg = MoneyTagExpenseDark,
    moneyTagMixedBg = MoneyTagMixedBgDark,
    moneyTagMixedText = MoneyTagMixedTextDark,
    wife = WifePinkDark,
    husband = HusbandBlueDark,
    categoryPalette = CategoryColorsDark,
    receiptRead = Color(0xFF81C784),
    receiptPending = Color(0xFFB0BEC5),
    rainbowSegment = listOf(
        Color(0xFFFF8A80),
        Color(0xFFFFD180),
        Color(0xFF69F0AE),
        Color(0xFF82B1FF)
    ),
    syncOk = IncomeGreenDark,
    syncPending = Color(0xFFFFD740),
    syncError = ExpenseRedDark,
    aiAccent = AiBlueDark,         // #AAC7FF — kontras di atas kontainer AI gelap
    brandAccent = BrandGreenDark,  // #6FE5AA — tone terang, kontras AA di dark
    onAvatar = Color.White,
    aiOnContainer = Color(0xFF002E6B) // teks navy di atas aksen biru terang → AA
)

internal val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
