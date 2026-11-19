# Nyachat — Laporan Audit UI/UX (r1.7.3)

**Tanggal:** 2026-11-19
**Metode:** static review komponen Compose, state management, aksesibilitas, dan alur navigasi. Bukan pengujian di perangkat (-environment tidak memiliki JDK/emulator).
**Cakupan:** Chat & messaging · Membership/onboarding/settings/dialog · Rekap/finance/navigasi/theming.

---

## Ringkasan eksekutif

| Area | HIGH | MED | LOW |
|---|---|---|---|
| Rekap, navigasi & theming | 3 | 9 | 12 |
| Chat & messaging | 3 | 8 | 3 |
| Membership, onboarding & settings | 2 | 13 | 8 |
| **Total** | **8** | **30** | **23** |

Prioritas lintas-area: status E2EE `UNKNOWN` membuat pesan terlihat terkirim padahal hanya antre (H1-chat), throttle PIN dilaporkan sebagai error jaringan (F1-membership), laporan AI markdown tidak dirender (H1-rekap), tidak ada date picker transaksi (H2-rekap).

**Catatan:** H1-chat, F1-membership, dan F10-membership adalah regresi/efek dari perbaikan keamanan yang baru saja diterapkan; ketiganya sudah saya perbaiki selama sesi ini (banner `chat_e2ee_checking`, `GateError.THROTTLED`, teks Toast upgrade tanpa klaim pembayaran). Temuan selebihnya bersifat existing (sudah ada sebelum perbaikan keamanan).

---

## 1. Rekap, navigasi & theming

### HIGH

**H1. Laporan AI dirender sebagai plain text, bukan markdown** — `AiReportDialog.kt:184-188`
Prompt AI meminta markdown (`GeminiService.kt:553`) dan fallback offline menghasilkan `**bold**`, `💡`, `•`, `1.`. User melihat karakter mentah dan kehilangan hierarki visual pada laporan panjang.
**Fix:** parser markdown ringan → `AnnotatedString` untuk `**bold**`, bullet, heading; jangan `Text(text = reportText)` langsung.

**H2. Tidak ada date picker — transaksi tidak bisa dicatat/dikoreksi tanggalnya** — `AddTransactionDialog.kt:344-356` (`:352`)
Timestamp selalu `System.currentTimeMillis()` saat tambah, dan `transaction.timestamp` saat edit. Tidak ada cara mencatat transaksi kemarin/lalu atau memperbaiki tanggal salah → kehilangan data nyata.
**Fix:** field tanggal + `DatePickerDialog`, `rememberSaveable` millis, pakai ke `FinancialTransaction.timestamp`.

**H3. Empty state tidak membedakan "data kosong" vs "hasil filter kosong"** — `RekapScreen.kt:232-233`, `RekapList.kt:350-389`
Pesan selalu "Belum Ada Transaksi…" walau filter bulan/kategori/tab aktif; user mengira data hilang. Tidak ada tombol reset filter.
**Fix:** deteksi `selectedCategory/selectedMonth/selectedFilterTab`, tampilkan "Tidak ada hasil untuk filter ini" + tombol "Hapus filter".

### MEDIUM

| # | Temuan | Lokasi | Fix |
|---|---|---|---|
| M1 | Posisi scroll Rekap hilang saat pindah tab (`AnimatedContent` menghancurkan komposisi) | `RekapScreen.kt:160`, `MainActivity.kt:1065-1084` | hoist `LazyListState` / simpan `firstVisibleItemIndex` di `RekapScreenState.Saver` |
| M2 | Palet 7 warna untuk >7 kategori → donut & legend warna duplikat | `RekapCharts.kt:485,553`, `Color.kt:59-78` | Top-7 + "Lainnya", atau generate shade unik |
| M3 | Angka laporan AI tanpa pemisah ribuan (inkonsisten dengan banner Rekap) | `GeminiService.kt:500,503,507,512,540,608` | pakai `NumberFormat` id-ID dengan `maximumFractionDigits=0` |
| M4 | Tombol Simpan disabled tanpa alasan; keterangan tidak divalidasi dengan feedback | `AddTransactionDialog.kt:359` | supporting text permanen atau `isError` + pesan saat klik |
| M5 | Kursor lompat saat mengoreksi nominal di tengah (format ulang seluruh string) | `AddTransactionDialog.kt:245-246` | `TextFieldValue` + pertahankan selection, atau `visualTransformation` |
| M6 | Fallback AI offline tidak transparan (terlihat seperti hasil AI) | `GeminiService.kt:587-613`, `strings.xml:61-62` | beri penanda "Mode AI sedang offline — laporan dihitung lokal" |
| M7 | State form AddTransactionDialog hilang saat rotasi (`remember`, bukan `rememberSaveable`) | `AddTransactionDialog.kt:92-129` | `rememberSaveable` untuk semua field form |
| M8 | Nominal CategoryProgressRow bisa overflow / saling desak | `RekapCharts.kt:372-377` | `maxLines=1`, `ellipsis`, `widthIn`/`weight` |
| M9 | Label bulan tanpa batas text scaling, berisiko tabrakan dengan tombol "Semua" | `RekapList.kt:126-134` | `maxLines=1`, `ellipsis` |
| M10 | Label "Kemarin" pakai aritmetika 86.400.000 ms (salah saat DST) | `DateLabels.kt:34` | `Calendar.add(Calendar.DAY_OF_YEAR, -1)` |

### LOW

| # | Lokasi | Masalah & Fix |
|---|---|---|
| L1 | `AddTransactionDialog.kt:357` vs `:140-144` | Simpan memanggil `onDismiss()` (sheet hilang instan) — pakai `dismiss()` |
| L2 | `AddTransactionDialog.kt:250` | `KeyboardType.Number` tanpa `imeAction` — tambah `ImeAction.Done` + `keyboardActions` |
| L3 | `RekapCharts.kt:178-181,217-220` | Ikon income `ArrowDownward` / expense `ArrowUpward` berlawanan konvensi tren; `contentDescription=null` |
| L4 | `RekapCharts.kt:205,245` | Angka income/expense pakai `onSurface`, bukan warna semantik |
| L5 | `RekapCharts.kt:315,372,515`, `RekapScreen.kt:87` | `remember { idrCurrencyFormat() }` per item → puluhan instance `NumberFormat`; hoist/`CompositionLocal` |
| L6 | `RekapScreen.kt:252` | `animateItem()` tidak mengikuti `Motion.reducedMotion` |
| L7 | `RekapScreen.kt:198,232-233,260` | `item { }` tanpa key (breakdown, empty state, AI card) |
| L8 | `MainTopBar.kt:108-121` | Aksi ikon-only tanpa tooltip — bungkus `Tooltip` |
| L9 | `MainNavigationBar.kt:47,63` | Ikon Chat berubah filled↔outline, Rekap selalu `PieChart` — seragamkan |
| L10 | `RekapList.kt:554-561` | Deskripsi `maxLines=1` tanpa cara lihat penuh |
| L11 | `RekapCharts.kt:483-499,550-565` | Slice <0.5° dilewati donut tapi legend tetap tampil |
| L12 | `DateLabels.kt:41` | `formatClockTime` pakai default locale, label lain `id-ID` |

**Catatan non-defect:** tidak ada `BackHandler`; tombol back dari tab Rekap keluar app. Untuk root bottom-nav ini perilaku standar Android.

---

## 2. Chat & messaging

### HIGH

**H1. Pesan diblokir E2EE tetap terlihat "sudah terkirim"; banner tidak muncul saat UNKNOWN** — `ChatScreen.kt:452-456,737`, `ChatInput.kt:766`, `FirestoreSyncManager.kt:150-155,1097-1104`, `FinanceRepository.kt:96`
Perubahan keamanan `UNKNOWN = BLOCK` benar, tetapi UI belum sinkron: `e2eeStatus.active` masih `false` saat startup sehingga banner tidak tampil, tombol Kirim tetap aktif, dan pesan disimpan lokal dengan `cloudId` → `delivery = SYNCED` (titik abu "0 dari N"), padahal `syncMessageNow` mengembalikan `false` dan hanya mengantri.
**Fix (sudah diterapkan):** banner juga tampil saat `activationState == UNKNOWN` dengan string `chat_e2ee_checking`. **Masih terbuka:** jangan tandai `SYNCED` sebelum `deliveredBy` nyata — pakai `PENDING` sampai sync sukses, dan disable/labeli tombol Kirim saat `BLOCK`.

**H2. Tidak ada state gagal maupun retry untuk pengiriman pesan & lampiran** — `ChatScreen.kt:179-224`, `ChatBubbles.kt:992-999`, `ChatScreen.kt:805-827`
`ImageFileUtil.saveImageFromUri` mengembalikan `null` (disk penuh/URI invalid) tanpa Toast — memilih foto terasa tidak terjadi apa-apa. Pesan gagal sync selamanya memakai ikon jam `Schedule`, tanpa penanda "gagal" dan tanpa aksi retry.
**Fix:** tambah `DeliveryStatus.FAILED` (ikon merah + label "Gagal terkirim") + menu "Kirim ulang"; Snackbar saat penyimpanan lampiran gagal.

**H3. Decode gambar gagal → spinner abadi / bubble kosong** — `ChatBubbles.kt:253-263,438-459`, `ImageViewerDialog.kt:128-135,204-210`
`produceState` mengembalikan `null` baik saat memuat maupun saat file hilang/corrupt. Viewer menampilkan spinner tanpa batas; bubble ber-gambar tanpa teks hanya berisi jam.
**Fix:** bungkus decode dalam sealed state; placeholder "Gagal memuat foto" + tombol coba lagi, dan placeholder teks di bubble.

### MEDIUM

| # | Temuan | Lokasi | Fix |
|---|---|---|---|
| M1 | Target sentuh <48dp (badge finansial ~20dp tapi `clickable`, ✕ balas 36dp, hapus lampiran 40dp, tombol AI 40dp, chip saran 40dp, FAB jump 40dp). Badge finansial menang atas `combinedClickable` bubble → salah ketuk memicu menu | `ChatBubbles.kt:654-674`, `ChatInput.kt:283,336,400,577`, `ChatScreen.kt:678` | `minimumInteractiveComponentSize()` / perbesar area klik |
| M2 | Stale capture: `LaunchedEffect(imeVisible)` membaca `rows.size` tanpa key; `LifecycleResumeEffect(Unit)` menangkap `rows` lalu baca setelah `delay(120)` → scroll ke index lama | `ChatScreen.kt:332-339,311-326` | masukkan `rows.size` sebagai key |
| M3 | Auto-scroll "untouched" menarik user yang sedang membaca riwayat jika anchor lama masih terlihat di layar besar | `ChatScreen.kt:278-295` | persempit `untouched` / andalkan `atBottom` |
| M4 | Empty state hilang saat `messages` kosong tetapi `isAiThinking = true` — hanya background kosong | `ChatScreen.kt:405,236-238` | empty state + "Menyiapkan jawaban AI…" + spinner |
| M5 | Bubble teks `onClick` no-op tetapi tetap `Role.Button`; TalkBack double-tap tidak ada hasil; Salin tanpa feedback | `ChatBubbles.kt:378-392`, `ChatScreen.kt:540-547` | arahkan onClick ke menu, `contentDescription` ringkas, Snackbar "Disalin" |
| M6 | Chip saran mengisi draf tetapi tidak memfokuskan input → keyboard tidak naik | `ChatScreen.kt:605-611`, `ChatInput.kt:216` | `inputFocusRequester.requestFocus()` |
| M7 | Fokus edit dialog hanya diminta sekali (`LaunchedEffect(Unit)`) | `ChatScreen.kt:831-838` | `remember(msg.id)` + `LaunchedEffect(msg.id)` |
| M8 | Kontras waktu `labelSmall` alpha 0.7 ≈3.9:1 (<4.5:1); indikator abu vs hijau 8dp dibedakan hanya oleh warna | `ChatBubbles.kt:211-214,328,1005-1021,1041-1057` | warna penuh + perbedaan bentuk (centang) selain warna |

### LOW

| # | Temuan | Fix |
|---|---|---|
| L1 | Zoom viewer tidak bisa dioperasikan TalkBack (`ImageViewerDialog.kt:156-177`) | tombol zoom +/− dengan `contentDescription` |
| L2 | Bubble incoming tidak punya label pengirim di `contentDescription` (`ChatBubbles.kt:290-323`) | gabungkan nama + isi pesan |
| L3 | Perubahan `setPlan`/PIN 8 digit/FCM tidak punya permukaan UI di chat — tidak ada temuan tambahan | — |

**Positif:** konfirmasi hapus + undo ada; custom action "Buka menu pesan" mengompensasi tap-to-menu; reduced-motion konsisten; `key()` LazyColumn benar; `clearAndSetSemantics` pada ikon dekoratif tepat.

---

## 3. Membership, onboarding & settings

### HIGH

**F1. Throttle dilaporkan sebagai error jaringan generik; tombol Coba Lagi memperparah lockout** — `MembershipGateScreen.kt:186-193,269-285,296-316`, `MembershipManager.kt:918-924`
Saat `registerPinAttempt` mengembalikan `RESOURCE_EXHAUSTED`, `FAILED` dipetakan ke "Gagal terhubung… periksa internet". Karena `FAILED` bukan kondisi terminal, Retry tetap muncul dan `requestedOnce` di-reset → user mengulang, lockout bertambah. Detail mentah `RESOURCE_EXHAUSTED: Too Many Requests` tampil 11sp.
**Fix (sudah diterapkan):** `GateError.THROTTLED` + string khusus + Retry disembunyikan.

**F10. Toast gagal upgrade menjanjikan pembayaran yang tidak ada** — `ManageMembersScreen.kt:396-403`, `strings.xml:408`
Pesan lama: "Paket akan diubah setelah pembayaran terverifikasi", padahal tidak ada alur Play Billing. **Fix (sudah diterapkan):** teks diganti "Pastikan langganan Pro aktif pada akun Google kamu…".

### MEDIUM (ringkasan)

| # | Temuan | Lokasi |
|---|---|---|
| F2 | Syarat 8 digit tidak dijelaskan; tombol Gabung abu-abu tanpa alasan; PIN 6 digit legacy mengira app rusak | `PinConnectScreen.kt:463-481,494-504,521-527` |
| F3 | Tidak ada `imeAction` pada field nama & PIN (jalur onboarding utama) | `PinConnectScreen.kt:336-357,463-481` |
| F4 | Tidak ada loading state / double-submit guard pada tombol connect | `PinConnectScreen.kt:444-458,485-501` |
| F5 | Deadline 30 menit di-reset setiap "Coba Lagi" (key `attempt` pada LaunchedEffect) | `MembershipGateScreen.kt:89-91,300-306` |
| F6 | `OWNED_ELSEWHERE` masih menampilkan Coba Lagi (kondisi terminal) — **sudah diterapkan: Retry disembunyikan** | `MembershipGateScreen.kt:296-299`, `strings.xml:322` |
| F9 | Setujui/tolak tanpa feedback hasil; Tolak tanpa konfirmasi; tidak ada indikator loading | `ManageMembersScreen.kt:217-233` |
| F11 | Dialog konfirmasi destruktif menutup diri sebelum operasi selesai; tidak ada guard double-tap | `ManageMembersScreen.kt:319-358` |
| F12 | `contentDescription` "Aksi" terlalu generik untuk TalkBack (tidak menyebut nama anggota) | `ManageMembersScreen.kt:578-585`, `strings.xml:369` |
| F14 | Dialog API key: tanpa reveal, validasi, atau konfirmasi simpan | `MainDialogs.kt:60-114` |
| F15 | Row toggle dengan dua handler klik (Row `.clickable` + Switch `onCheckedChange`) → risiko toggle dua kali & pengumuman TalkBack ganda | `SettingsSheet.kt:215-223,280-288,486-488` |
| F16 | Dialog passphrase backup tanpa field konfirmasi & reveal (typo = data hilang permanen) | `BackupDialogs.kt:258-310`, `strings.xml:307-309` |
| F17 | Auto-passphrase tidak menjelaskan kunci berbeda & tidak ada akses lihat kunci pemulihan | `SettingsSheet.kt:276-288`, `strings.xml:313,317-319` |
| F21 | State teks dialog memakai `remember` (hilang saat rotasi): WorkspaceNameDialog, LabelEditDialog, Profile name, ApiKeyDialog, PassphraseDialog | `ManageMembersScreen.kt:734,776`, `ProfileAccountSheet.kt:270`, `MainDialogs.kt:68`, `BackupDialogs.kt:266` |
| F23 | Gate tidak menangani tombol Back fisik; back bisa menutup aktivitas | `MembershipGateScreen.kt:319-329`, `MainOverlays.kt:111-120` |

### LOW (ringkasan)

| # | Temuan | Fix |
|---|---|---|
| F7 | Paste PIN >8 digit diabaikan total (silent no-op) — `PinConnectScreen.kt:465-471` | `digits.take(PIN_LENGTH)` |
| F8 | Indikator "PIN tersalin!" bisa menetap setelah rotasi — `PinConnectScreen.kt:99,413-423` | `LaunchedEffect(pinCopied)` + delay |
| F13 | Ikon approve pakai `Modifier.width` bukan `size` — `ManageMembersScreen.kt:469-474` | `size(18.dp)` |
| F18 | Picker daftar backup tidak bisa di-scroll — `BackupDialogs.kt:120-201` | `verticalScroll`/`LazyColumn` |
| F19 | Progress restore bisa dibatalkan tap di luar/back tanpa konfirmasi — `BackupDialogs.kt:64-107` | blokir dismiss saat restore |
| F20 | Brand "Nya" hitam hardcoded, tidak terbaca di dark mode — `PinConnectScreen.kt:305-309` | `onBackground` |
| F22 | Focus requester bergantung `delay(120)` magic number — `ManageMembersScreen.kt:737-741`, `MainDialogs.kt:71-75` | `LaunchedEffect(Unit)` tanpa delay |

**Positif:** empty state join request & daftar anggota kosong ada; `LazyColumn` + key unik; guard owner tidak bisa dihapus/demote diri benar; alur kick/leave (`MainActivity.kt:687-716`) menutup semua overlay & membersihkan data lokal dengan Snackbar; konfirmasi pindah workspace menjelaskan konsekuensi.

---

## 4. Status implementasi perbaikan

Seluruh 61 temuan UI/UX telah diperbaiki di working tree.

| Area | Perbaikan utama |
|---|---|
| **Chat** | Status `PENDING` sampai receipt nyata; `DeliveryStatus.FAILED` + menu "Kirim ulang" (wire ke `FirestoreSyncManager.syncMessage`); Snackbar gagal simpan lampiran & "Disalin"; decode gambar tri-state + placeholder + retry; zoom ± untuk TalkBack; target sentuh ≥48dp; kontras waktu & indikator baca; stale capture `rows.size`; auto-scroll lebih konservatif; empty state AI; fokus chip saran & edit dialog; `contentDescription` pengirim |
| **Membership/settings** | Helper & progres 8 digit, imeAction, guard double-submit; deadline gate tidak di-reset; throttle & OWNED_ELSEWHERE terminal; paste PIN dipotong + warning; timer "PIN tersalin" tahan rotasi; feedback approve/reject/remove/promote + konfirmasi destruktif & reject; `contentDescription` aksi per anggota; dialog API key (reveal/validasi/save); toggle single-target; passphrase konfirmasi + reveal; penjelasan auto-passphrase + "Lihat kunci pemulihan"; daftar backup scrollable; konfirmasi batal restore; brand "Nya" mengikuti tema; `rememberSaveable` dialog; focus tanpa `delay(120)`; BackHandler gate |
| **Rekap/navigasi** | Renderer markdown `AnnotatedString` untuk laporan AI; `DatePickerDialog` + konversi UTC↔lokal; empty state terfilter + "Hapus filter"; scroll position di-hoist & di-Saver; Top-7 + "Lainnya"; angka laporan pakai formatter id-ID; supporting text nominal; grouping via `VisualTransformation`; penanda offline AI; helper teks + ellipsis; Kalender untuk "Kemarin"; `dismiss()` saat simpan; imeAction; ikon tren + deskripsi; warna semantik; formatter di-hoist; reduced-motion; key pada `item`; Tooltip TopBar; ikon nav seragam; tap-to-expand deskripsi; legenda sinkron donut; locale jam id-ID |

**Hasil review integrasi final (4 perbaikan tambahan):**
1. **HIGH kompilasi** — `PaddingValues` belum di-import di `AddTransactionDialog.kt` → ditambahkan.
2. **MED** — `passphrase` & `confirmPassphrase` memakai key `rememberSaveable` sama → dipisah (`.passphrase` / `.confirm`).
3. **MED** — `DatePicker` Material3 memakai UTC millis sehingga tanggal bisa mundur 1 hari di WIB → ditambah konversi `localDateToUtcMidnight` / `utcMidnightToLocalDate`.
4. **LOW** — "Kirim ulang" ditawarkan untuk pesan tanpa `cloudId` (no-op) → menu disembunyikan.

`git diff --check` bersih; `node --check functions/index.js` lolos. Review juga mengonfirmasi **tidak ada regresi keamanan**: jalur kirim tetap through E2EE, banner UNKNOWN tetap ada, receipt/rainbow utuh, `setPlan` tetap server-authoritative, throttle PIN tetap tampil, dan cleanup kick/leave tidak berubah. Kompatibilitas test juga aman (signature `ChatScreen` dengan 8 arg default, `RekapScreenState.Saver` memakai `getOrNull`).

**Belum bisa diverifikasi:** Gradle test — environment tidak memiliki JDK/`JAVA_HOME`. Perlu dijalankan di mesin dengan JDK sebelum rilis.
