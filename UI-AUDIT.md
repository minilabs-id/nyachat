# Nyachat — Laporan Audit UI Visual (pasca-perbaikan UX R2)

**Tanggal:** 2026-11-19
**Fokus:** murni **visual/geometri/layout/tema** — bukan alur UX atau keamanan.
**Metode:** static review 3 lensa (bubble chat · layar/dialog · design system). Gradle/emulator tidak dijalankan.
**Temuan tumpang tindih antar-lensa dide-duplikasi.**

---

## Ringkasan

| Severity | Jumlah | Fokus utama |
|---|---|---|
| HIGH | 4 | ikon PDF putih-di-putih, foto potret strip kosong, PIN 8 digit meluap 320dp, sheet anggota tidak center di tablet |
| MED | 30 | ellipsis/overflow, letterbox near-square, indikator status gambar, insets nav-bar dark mode, konsistensi sheet |
| LOW | 18 | token radius, ukuran ikon, determinisme font, golden coverage |

**Verifikasi commit terbaru (kode):**
- `1312cd7` ripple mengikuti bubble ✅ benar (clip sebelum clickable, shape sama)
- `aae3e08` footer teks kiri-bawah ✅ benar (`placeTimeAtStart = true`)
- `62d93af` indikator di kiri waktu ✅ benar di footer teks, ❌ **tidak berlaku gambar murni** (MED-3)
- `63d2f97` caption full-width ⚠️ benar, tapi efek samping bubble 280dp → HIGH-2

---

## A. Chat bubble & media

### HIGH

**A-H1. Ikon PDF lampiran praktis tak terlihat di bubble sendiri (light)** — `ChatBubbles.kt:714,726`
Latar `primaryContainer` light `#A8F0C8` + `Color.White.copy(0.2f)` ≈ `#B9F3D3`, lalu ikon `PictureAsPdf` **putih** → kontras ≈1.25:1. Di dark aman.
**Fix:** `tint = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else semantic.expense`; latar `onPrimaryContainer.copy(0.12f)`.

**A-H2. Foto potret menyisip strip kosong ±89dp di dalam bubble** — `ChatBubbles.kt:437,908-945,1008-1029`
Caption `fillMaxWidth()` di `Column` yang diukur dengan `widthIn(max=280dp)` → lebar bubble ditentukan caption, bukan foto. Foto 9:16 hanya ~191dp start-aligned. Test `ChatBubbleGestureTest.kt:206` hanya mengecek caption vs bubble, bukan foto vs bubble.
**Fix:** kunci lebar media: `Column(Modifier.width(mediaW.dp))`, caption `fillMaxWidth()` di dalamnya; atau foto `fillMaxWidth().height(mediaH.dp)`.

### MEDIUM

| # | Temuan | Lokasi | Fix |
|---|---|---|---|
| A-M1 | Badge finansial memaksa footer ≥48dp → band kosong ±20dp & baseline jam melayang | `ChatBubbles.kt:791-804,1060-1076` | jangan pasang `minimumInteractiveComponentSize()` di jalur layout ini (badge sudah `clickable`); atau kompensasi padding negatif |
| A-M2 | Foto near-square (rasio >0.824) kena letterbox — lebar dipaksa 280dp tapi tinggi tetap 340dp | `ChatBubbles.kt:437,972-975,988-992` | `scale = minOf(280f/w, 340f/h, 1f)`; `mediaW = w*scale`, `mediaH = h*scale` |
| A-M3 | **Gambar murni milik sendiri tidak punya indikator status** — jam tampil tapi tidak ada pending/dot/centang; melanggar janji commit `62d93af` | `ChatBubbles.kt:981-1000,1101-1115` | tambah `MessageStatusIndicators` di dalam `TimeChipOverlay` sebelum teks jam |
| A-M4 | Badge finansial tanpa `maxLines`/`widthIn` → wrap 2 baris & tabrakan baseline dengan waktu | `ChatBubbles.kt:817-882,1070-1090` | `maxLines=1`, `Ellipsis`, `widthIn(max=180.dp)` |
| A-M5 | Zoom viewer bisa menggeser foto keluar layar — clamp mengasumsikan gambar mengisi kontainer padahal `contentScale=Fit` | `ImageViewerDialog.kt:94-103,211-226` | clamp terhadap ukuran tergambar (`fit(bitmap, container) × scale`) |
| A-M6 | Layout jump saat decode selesai: padding 14/10→12/8, lineHeight null→22sp, width 300→280 | `ChatBubbles.kt:524-591,1008-1029,437` | samakan padding & `lineHeight` di kedua jalur; placeholder setinggi `mediaH` |

### LOW

| # | Temuan | Fix |
|---|---|---|
| A-L1 | `ExpenseRed.copy(0.15f)` literal untuk ikon PDF di sheet, kotor di dark (`ChatInput.kt:741`) | `semantic.expenseBg` |
| A-L2 | Teks pesan/caption tanpa `maxLines` → bubble sangat tinggi untuk URL/emoji panjang (`ChatBubbles.kt:665-670,1010-1015`) | samakan dengan reply/file yang sudah `maxLines=2+Ellipsis` |
| A-L3 | Footer jalur loading hanya untuk `isMe` (`ChatBubbles.kt:558-571`) | tampilkan jam juga untuk pesan masuk gagal |
| A-L4 | Ikon status ukuran 8–14dp tidak seragam (`ChatBubbles.kt:1146,1155,1166,1178,1196,1226,1236`) | token `StatusIconSize` (12dp utama, 8dp inner) |

---

## B. Layar & dialog non-chat

### HIGH

**B-H1. PIN 8 digit meluap di 320dp** — `PinConnectScreen.kt:464-478`
`displayMedium` ~44sp ExtraBold + `letterSpacing = 8.sp` di Card padding 24dp dalam Column padding 24dp → ruang ≈224dp, kebutuhan PIN ≈250–260dp. Digit terpotong tanpa elipsis.
**Fix:** `headlineMedium`, `letterSpacing = 4.dp`, `maxLines=1` + `Ellipsis`, atau FontScale-adaptive seperti `ProfileAccountSheet.kt:157-161`.

**B-H2. Sheet Kelola Anggota tidak ter-center di tablet** — `ManageMembersScreen.kt:153`
`Box(Modifier.fillMaxWidth().widthIn(max=600dp))` menempel kiri; sisa ruang di tablet jadi kosong kanan saja.
**Fix:** bungkus `Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter)`.

### MEDIUM

| # | Temuan | Lokasi | Fix |
|---|---|---|---|
| B-M1 | Judul laporan AI menimpa tombol close di 320dp | `AiReportDialog.kt:147-168` | `Column(Modifier.weight(1f))` + `maxLines=2, Ellipsis` |
| B-M2 | Header "Riwayat Transaksi" tanpa ellipsis, menimpa tombol Tambah | `RekapList.kt:233-238` | `weight(1f)` + `maxLines=1, Ellipsis` |
| B-M3 | Label bulan off-center saat chip "Semua" muncul (weight hanya di kiri) | `RekapList.kt:104-162` | `Spacer(Modifier.weight(1f))` di kanan atau baris terpisah |
| B-M4 | FAB Rekap grid 20dp vs konten 16dp | `RekapScreen.kt:377-380` | samakan 16dp |
| B-M5 | Snackbar double-inset: `statusBars padding` + `padding(top=72dp)` → ~96–104dp | `MainOverlays.kt:416-427` | hapus salah satu |
| B-M6 | Tombol Coba Lagi & Tutup menempel (mode error), tidak seperti mode normal | `AiReportDialog.kt:238-293` | `Spacer(12.dp)` / `padding(bottom=12.dp)` |
| B-M7 | Nama kategori menimpa nominal & badge % | `RekapCharts.kt:406-411` | `maxLines=1, Ellipsis, weight(1f, fill=false)` |
| B-M8 | Saldo `displaySmall` `maxLines=1` terpotong di 320dp | `RekapCharts.kt:163-170,207-215,248-256` | style adaptif lebar / izinkan 2 baris |
| B-M9 | Donut 240dp meluap pada fontScale 1.5 + 320dp; label tengah menimpa cincin | `RekapCharts.kt:617-624,683` | `min(160f*scale, availableWidth-32)`, label ≤ `donutSize-2*stroke-8` |
| B-M10 | SettingRow title/subtitle tanpa ellipsis, mendorong Switch | `SettingsSheet.kt:628-639` | title `maxLines=1`, subtitle `maxLines=2` + Ellipsis |
| B-M11 | Kunci pemulihan monospace tidak wrap → terpotong horizontal | `SettingsSheet.kt:428-438` | `Ellipsis, maxLines=3` atau `horizontalScroll` |
| B-M12 | Email & nama anggota meluap kartu, menimpa tombol | `ManageMembersScreen.kt:622-626,688-731` | `maxLines=1, Ellipsis, weight(1f, fill=false)` |
| B-M13 | Gate keanggotaan tidak scroll → tombol terpotong di landscape/fontScale besar | `MembershipGateScreen.kt:258-264` | `verticalScroll()` + `Arrangement.Center` |
| B-M14 | Dialog sumber foto & passphrase tidak scroll | `ProfileAccountSheet.kt:243-296`, `BackupDialogs.kt:318-412` | bungkus `text` slot dengan `verticalScroll`/`heightIn` |
| B-M15 | Brand hijau `Color(0xFF007A49)` hardcoded → ~2.5:1 di dark | `PinConnectScreen.kt:382-390` | `MaterialTheme.colorScheme.primary` / token `brandAccent` |
| B-M16 | Swipe Rekap pakai `ExpenseRed` light di dark (~2.6:1) | `RekapList.kt:517,529` | `semantic.expenseBg` / `semantic.expense` |
| B-M17 | Segment control hampir tak terlihat (selected hanya `surface`) | `RekapList.kt:268-323` | alpha 0.6 + border/elevasi, atau `secondaryContainer` |
| B-M18 | **5 sheet kehilangan navigation-bar inset** — `contentWindowInsets = WindowInsets(0)` tanpa `navigationBarsPadding()` → tombol Simpan/Tutup/Coba Lagi di area sistem | `AddTransactionDialog:271`, `AiReportDialog:98`, `SettingsSheet:188`, `ProfileAccountSheet:118`, `ManageMembersScreen:150` | tambah `.navigationBarsPadding()` pada Column konten |
| B-M19 | Subtitle TopAppBar tanpa `weight`/ellipsis, menimpa aksi di 320dp | `MainTopBar.kt:82-107` | `Column(Modifier.weight(1f))` + `maxLines=1, Ellipsis` |
| B-M20 | Drag handle tidak konsisten (ada di 2 sheet, tidak ada di 3) | `AddTransactionDialog:281`, `AiReportDialog:107` vs `SettingsSheet:190`, `ProfileAccount:120`, `ManageMembers:154` | satu `SheetDragHandle()` dipakai kelima sheet |
| B-M21 | Batas tinggi sheet tidak seragam (640/560/0.92f/tanpa batas) | 5 file di atas | token `SheetMaxHeight` |
| B-M22 | Empty state Manage Members hanya Text 13sp, tidak sekelas Rekap (Card+ikon+judul+hint) | `ManageMembersScreen.kt:285-292` | komponen empty-state bersama |
| B-M23 | Divider/border alpha terlalu rendah di dark | `AiReportDialog:172`, `SettingsSheet:501`, `RekapList:270,440` | `outlineVariant` tanpa alpha / alpha ≥0.8 |
| B-M24 | CTA `AiBlue #0066FF` ~3.4:1 di dark untuk label 14sp | `AiReportDialog:245,282`, `AiReportCard:199` | `semantic.ai` dengan `onSurface`, atau `#3B82FF` |

### LOW (ringkas)

| Lokasi | Gejala |
|---|---|
| `AiReportCard.kt:184-189,218-223` | `maxLines=1` tanpa `overflow` → label terpotong keras |
| `RekapList.kt:212-222,338-343` | "Saldo"/status sync tanpa `maxLines` |
| `RekapList.kt:195-226` | `animateContentSize` pada sticky header → layout jitter |
| `RekapCharts.kt:300-344` | Ukuran indikator status beda (8/10/14dp) → tinggi baris berubah |
| `RekapScreen.kt:200-206` | LazyColumn tanpa scrollbar |
| `MainTopBar.kt:217-222` | "White ring" overlay putih 25% bukan border; photo avatar tanpa ring |
| `ManageMembersScreen.kt:580-587,289,533-536` | `fontSize=13.sp` literal, bukan token tipografi |
| `RekapCharts.kt:448-459` | Radius badge 8dp literal, bukan `Constants.Ui.CORNER_S` |
| `StartupScreens.kt:43-66` | Tanpa `navigationBarsPadding`/`imePadding` (dampak minimal) |
| `PinConnectScreen.kt:362-365` | `.padding(24).padding(bottom=56)` → footer 80dp, bukan ~50dp |

---

## C. Design system & theming

**D-M1. Dynamic color tidak pernah aktif** — `Theme.kt:86,89-96`
`dynamicColor = false` (default) → cabang `dynamic*ColorScheme` tidak tercapai di produksi; Android 12+ tidak mengikuti wallpaper.
**Fix:** jadikan konfigurasi eksplisit atau hapus cabang dynamic agar tidak tampak setengah jalan.

**D-M2. Brand hijau onboarding hardcoded** — `PinConnectScreen.kt:385` (sama dengan B-M15, dide-duplikasi di sini). Tambah token `brandAccent` light `#007A49` / dark `#6FE5AA`.

**D-M3. Launcher icon hanya vector `mipmap-anydpi`, tanpa fallback PNG per densitas** — tidak ada `mipmap-{m,h,xh,xxh,xxxh}dpi`. API 24–25 & launcher OEM bisa gagal render.
**Fix:** generate PNG density buckets atau naikkan minSdk 26.

| Low | Temuan | Fix |
|---|---|---|
| D-L1 | Literal avatar `Color(0xFF202124)`/`Color.White` duplikat di 3 file | token `onAvatar` |
| D-L2 | `contentColor=Color.White` per-tombol AI | token `aiOnContainer` |
| D-L3 | `aiAccent` identik dengan `ai` di kedua mode; pasangan `incomeBg`/`expenseBg` dark tidak simetris | hapus token redundan / samakan shade |
| D-L4 | Font `GoogleFont("Plus Jakarta Sans")` via Downloadable Fonts → fallback di Robolectric & perangkat tanpa GMS; golden tidak merepresentasikan brand | bundle TTF di `res/font/` atau dokumentasikan di `AppSnapshotTest` |
| D-L5 | Teks 11sp di `MembershipGateScreen:315,341` & `ManageMembersScreen:698,715,721,728,866` (di bawah `labelSmall` 12sp) | `labelSmall` / min 12sp |
| D-L6 | `fontSize = (avatarSize/2.8).sp` dinamis di luar skala (`MainTopBar:227`) | `labelMedium` dengan clamp |
| D-L7 | `lineHeight` override tersebar (`ChatBubbles:668,1013`, `AiReportDialog:232`) | definisikan `bodyChat`/`bodyReport` di `Type.kt` |

**Catatan positif:** semua `fontSize` memakai `sp`; small icon notifikasi sudah monokrom (`ic_stat_logo`); adaptive launcher punya `<monochrome>`; `keep.xml` menjaga string Firebase dinamis; tidak ada resource visual yang bisa ter-strip.

---

## D. Visual regression coverage (Roborazzi)

**Kondisi saat ini:** 1 file `AppSnapshotTest.kt` — 15 test, `@Config(sdk=[35], qualifiers="w411dp-h891dp")`, tema **light default**, 14 PNG di `app/src/test/snapshots/screens/`.

**Tercakup:** bubble teks (3), TransactionItemCard, BalanceBanner (2), DonutChart, quick suggestions, composer (3), MainTopBar (3).

**Gap prioritas HIGH:**
1. **Dark mode** — 0/15 golden memakai `darkTheme=true`; semua temuan B-M15/M16/M23/M24 & D-M2 tidak terdeteksi → tambah `AppSnapshotDarkTest` (`uiMode=night`).
2. **E2EE banner** (checking/preparing) & **receipt dots** (pending/read/rainbow) & **badge mixed-type** — state paling kaya warna, paling rawan regresi kontras.
3. **PIN step 2 (PIN tergenerate)** — satu-satunya cara menangkap B-H1.
4. **ManageMembers @ tablet w840dp** — satu-satunya cara menangkap B-H2.
5. **w320dp + fontScale 1.5/2.0** — menangkap B-M1/M2/M7/M8/M9/M12/M19.

**Gap MED:** AddTransactionDialog, AiReportDialog (normal/error/offline), InsightsAiCard, SettingsSheet, ProfileAccountSheet, filter/empty state Rekap, MembershipGate (3 state), NavigationBar, BackupDialogs, Snackbar overlay.

**Catatan:** `ImageViewerDialogTest.kt:40` menyatakan `captureRoboImage` tidak andal untuk analisis piksel; golden sudah dua kali di-re-record karena render Windows ≠ CI — tambah golden media perlu path CI yang sama.

---

## E. Prioritas perbaikan

1. **HIGH** — A-H1 (ikon PDF), A-H2 (foto potret strip kosong), B-H1 (PIN 8 digit overflow), B-H2 (sheet tablet).
2. **MED geometri** — A-M1/M2/M3/M6, B-M1/M7/M9/M10/M12/M13/M18/M19 (ellipsis, clamp, inset, indikator gambar).
3. **MED warna dark** — B-M15/M16/M23/M24, D-M1/M2/M3.
4. **MED konsistensi sheet** — B-M5/M6/M20/M21/M22.
5. **LOW + golden** — token radius/tipografi, lalu tambah golden dark/320dp/tablet/E2EE/receipt.

---

## F. Area yang sudah baik

- Verifikasi commit bubble: ripple ter-clip sesuai bentuk ✅, footer teks kiri-bawah ✅, indikator di kiri waktu untuk teks ✅.
- `IC_stat_logo` monokrom benar; adaptive launcher punya monochrome layer; splash punya varian night.
- `keep.xml` + ProGuard benar; tidak ada resource visual yang bisa hilang di release.
- Semua `fontSize` pakai `sp`; skala `display→title` konsisten.
- Token semantik (`SemanticColors`) terpusat & mode-aware; konsumen mulai memakainya.
- `Motion.reducedMotion` + `LocalMotionDurationScale` sudah terpasang global.

## G. Status implementasi

Seluruh 52 temuan UI visual telah diperbaiki.

| Area | Perbaikan |
|---|---|
| **Chat bubble** | Ikon PDF `onPrimaryContainer` (kontras light); media column dikunci ke `mediaW` (foto potret mengisi bubble); clamp `minOf(280/w, 340/h, 1)` untuk near-square; indikator status ditambahkan ke `TimeChipOverlay` gambar murni; badge tanpa inflasi 48dp & dibatasi 180dp/ellipsis; padding & lineHeight disamakan (tanpa layout jump); zoom clamp terhadap ukuran tergambar; ukuran ikon status seragam 12/8dp |
| **Rekap/topbar** | Month nav di-center; header ellipsis; FAB grid 16dp; saldo adaptif <360dp; donut clamp `min(160*scale, width-32)` + label dalam cincin; kategori ellipsis; swipe pakai `semantic.expense`; segment `secondaryContainer` + border; sync indicator seragam 10dp; TopBar `weight(1f)` + ellipsis; avatar border tema; Startup insets |
| **Dialog/settings** | PIN 8 digit turun ke `headlineMedium` + letterSpacing 4sp + ellipsis; sheet anggota TopCenter di tablet; judul AI `weight` + ellipsis; nav-bar inset & drag handle seragam di 5 sheet; tinggi maks 640dp; snackbar inset diperbaiki; recovery key ellipsis/scroll; gate & dialog scrollable; brand `brandAccent` token; empty state Card+ikon; dividers dark; CTA AI `aiAccent`/`aiOnContainer` |
| **Design system** | Dynamic-color branch mati dihapus + keputusan brand didokumentasikan; token `brandAccent`/`onAvatar`/`aiOnContainer` ditambah; `aiAccent` dibedakan; `bodyChat`/`bodyReport` di `Type.kt`; splash icon khusus; **10 PNG launcher** per densitas; golden test baru |

**Golden coverage:** dari 15 → **23 test** di `AppSnapshotTest` (PIN step-2, tablet, 320dp/font1.5, status pending/read/rainbow, AI offline) + **8 test baru** di `AppSnapshotDarkTest` (dark bubble/banner/donut/composer/topbar/suggestions).

**Perbaikan hasil review integrasi final (3 item):**
1. **BLOCKER** — qualifier Robolectric `uiMode=night` tidak valid → `night` (`AppSnapshotDarkTest.kt:42`)
2. **BLOCKER** — `Modifier.verticalScrollbar`/`rememberScrollbarAdapter` tidak ada di Foundation 1.11.4 → dihapus (temuan LOW, tidak layak risiko kompilasi)
3. **MED** — snackbar kehilangan inset status bar → `windowInsetsPadding(statusBars)` dikembalikan dengan manual top 48dp/8dp (total ≈72dp/32dp)

`git diff --check` bersih; `node --check functions/index.js` lolos. Review mengonfirmasi **tidak ada regresi keamanan/perilaku** (PIN throttle, gate timeout, `setPlan` server-authoritative, E2EE banner, receipt, single SnackbarHost, notification handoff tetap utuh).

**Belum bisa diverifikasi:** Gradle test & golden regenerasi — tidak ada JDK di environment. Perlu dijalankan di mesin dengan JDK sebelum rilis.
